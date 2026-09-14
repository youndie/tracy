package io.github.youndie.tracy.server

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.github.youndie.kore.generated.KoreBuildIdentity
import io.github.youndie.kore.ktor.EngineDrain
import io.github.youndie.kore.ktor.installKoreProbes
import io.github.youndie.kore.ktor.installKoreVersion
import io.github.youndie.kore.ktor.installShutdownRefusal
import io.github.youndie.kore.lifecycle.AnnounceNotReady
import io.github.youndie.kore.lifecycle.ShutdownDeadlines
import io.github.youndie.kore.lifecycle.ShutdownParticipant
import io.github.youndie.kore.lifecycle.runUntilSignal
import io.github.youndie.tracy.server.db.WalCheckpoint
import io.github.youndie.tracy.server.db.migrateDb
import io.github.youndie.tracy.server.ingest.ingestRoutes
import io.github.youndie.tracy.server.mcp.installMcp
import io.github.youndie.tracy.server.query.queryRoutes
import io.github.youndie.tracy.server.retention.Retention
import io.github.youndie.tracy.server.trace.traceRoutes
import io.github.youndie.tracy.wire.Level
import io.github.youndie.tracy.wire.TracyJson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * How the process stops, as numbers.
 *
 * 2 + 10 + 3×3 = 21 seconds inside a declared 30, which is also `terminationGracePeriodSeconds` in
 * the chart: nothing tells a process its real budget on any platform, so kore is *told* one and the
 * chart has to keep saying the same number.
 *
 * **The pre-drain wait is shorter than kore's default five seconds, and that is a choice about
 * deploys rather than a measurement of this cluster.** kore measured endpoint propagation at 61 ms
 * on one node, and tracy is a single replica with `strategy: Recreate` — there is no second pod for
 * traffic to move to, so every second here is a second of deploy downtime and nothing else.
 */
private val DEADLINES =
    ShutdownDeadlines(
        preDrainWait = 2.seconds,
        drain = 10.seconds,
        releaseGroup = 3.seconds,
        gracePeriod = 30.seconds,
    )

public fun main() {
    val config = ServerConfig.fromEnv()

    // Migrations run in here, before anything serves and before the startup gate opens.
    val db = openDatabase(config.dbPath, config.dbMaxConnections, config.dbIdleTimeoutSeconds)
    val probes = TracyProbes(db)

    val server =
        embeddedServer(
            CIO,
            configure = {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        port = config.httpPort
                        host = "0.0.0.0"
                    },
                )
                // The engine gets the same number the drain stage uses. Ktor's own default is one
                // second, which is shorter than a great many real requests — and an ingest batch
                // arrives in one.
                shutdownGracePeriod = DEADLINES.drain.inWholeMilliseconds
                shutdownTimeout = (DEADLINES.drain + 5.seconds).inWholeMilliseconds
            },
            module = { module(config, db, probes) },
        )

    // NOT `wait = true`. The main thread has to reach the await below, or the signal arrives at a
    // process with no sequence to run and it is killed at the end of the grace period instead —
    // which, from outside, is indistinguishable from having stopped.
    server.start(wait = false)

    val checksScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    probes.start(checksScope)
    probes.startup.markStarted()

    runBlocking {
        runUntilSignal(
            DEADLINES,
            // Inside the callback rather than on the line after the call: on the JVM this function
            // returning means the shutdown hook has returned and the process is already on its way
            // out. Native carries on, which is what makes the racing version easy to write and never
            // see.
            onFinished = { run -> println(run.transcript) },
        ) {
            announce(AnnounceNotReady(probes.readiness))
            drain(EngineDrain(server, DEADLINES.drain, DEADLINES.drain + 5.seconds))

            // Last, because every write above it goes through this pool. `ApplicationStopping` —
            // where this would otherwise be closed — runs BEFORE the drain on Kotlin/Native and
            // after it on the JVM, from identical source, which is the asymmetry kore is here for.
            pool(participant("sqlite pool") { db.close().getOrThrow() })

            telemetry(
                participant("health checks") {
                    probes.stop()
                    checksScope.cancel()
                },
            )
        }
    }
}

/**
 * A participant out of a name and a lambda.
 *
 * `label` and `block` rather than `name` and `stop`: inside the object those two names belong to the
 * members being overridden, and `stop()` calling `stop` would be the function calling itself.
 */
private fun participant(
    label: String,
    block: suspend () -> Unit,
): ShutdownParticipant =
    object : ShutdownParticipant {
        override val name: String = label

        override suspend fun stop() {
            block()
        }
    }

/**
 * Opens the database and runs migrations **before** the engine starts.
 *
 * `runBlocking` here is deliberate: a server that opened its port ahead of a ready schema would
 * answer the first requests with errors, and the first requests are exactly the ones an agent
 * retries hardest.
 */
public fun openDatabase(
    path: String,
    maxConnections: Int = ServerConfig.DEFAULT_DB_MAX_CONNECTIONS,
    idleTimeoutSeconds: Long? = null,
): ISQLite {
    val dbPath = path.toPath()
    val fileSystem = FileSystem.SYSTEM

    if (!fileSystem.exists(dbPath)) {
        dbPath.parent?.let { parent -> if (!fileSystem.exists(parent)) fileSystem.createDirectories(parent) }
        fileSystem.write(dbPath) { }
    }

    val db =
        sqlite(
            url = "sqlite://$path",
            options =
                ConnectionPool.Options
                    .builder()
                    .maxConnections(maxConnections)
                    .apply { idleTimeoutSeconds?.let { idleTimeout(it.seconds) } }
                    .build(),
        )

    runBlocking { db.migrateDb() }
    return db
}

public fun Application.module(
    config: ServerConfig,
    db: ISQLite,
    probes: TracyProbes,
) {
    // BEFORE the probes and before the routes. An interceptor installed later would let through
    // every call that arrived first, and the one request this must not miss is the first one after
    // readiness has gone false. kore's own routes are exempt: a 503 from `/health/live` is a failed
    // liveness probe, which restarts the pod in the middle of the shutdown it is reporting.
    installShutdownRefusal(isShuttingDown = { probes.readiness.isShuttingDown })
    installKoreProbes(probes.startup, probes.readiness, probes.liveness)

    // The version and the commit, compiled in by the Gradle plugin because Kotlin/Native has neither
    // resources nor a manifest to read them from.
    installKoreVersion(KoreBuildIdentity)

    // One container, one instance of each collaborator. What this replaces: four repositories
    // constructed twice — once for the MCP facade, once for the HTTP routes.
    install(Koin) { modules(serverModule(config, db)) }

    // Typed routes need this installed, and the failure without it is at runtime rather than at
    // compile time — the route simply never matches.
    install(Resources)

    val retention = get<Retention>()
    val self = if (config.selfService != null) get<SelfObservation>() else null

    if (self != null) {
        launch {
            self.log(
                Level.INFO,
                "Boot",
                "tracy started",
                mapOf("retentionDays" to config.retentionDays.toString()),
            )
        }
    }

    // The log sweep. Separate from retention and far more often: retention is about days of data
    // and runs hourly, this is about a file that grows between one read and the next (see
    // WalCheckpoint). Zero turns it off — for a test that wants SQLite's own behaviour, and for the
    // day somebody has to answer "is this sweep the problem?" without building an image.
    if (config.walCheckpointSeconds > 0) {
        val wal = get<WalCheckpoint>()
        launch {
            var quietMillis = 0L
            while (true) {
                delay(WAL_POLL_MILLIS)
                quietMillis += WAL_POLL_MILLIS
                // Two triggers, and the size one is the reason this survives its own bad day: by
                // the time the clock comes round, a log that is growing because reads are slow is
                // already the thing making them slow. `walBytes` is one `stat`.
                val overflowing = wal.walBytes() >= config.walMaxBytes
                if (!overflowing && quietMillis < config.walCheckpointSeconds * 1000) continue
                quietMillis = 0
                val state =
                    try {
                        wal.checkpoint()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        self?.log(
                            Level.WARN,
                            "Wal",
                            "wal checkpoint failed",
                            mapOf("failure" to (failure::class.simpleName ?: "unknown")),
                        )
                        null
                    }
                // Only the runs that could not reset the log are worth a line. A checkpoint that
                // worked is the normal case sixty times an hour, and logging it would be tracy
                // filling its own database with the news that it is keeping its database small.
                if (state != null && state.busy) {
                    self?.log(
                        Level.WARN,
                        "Wal",
                        "wal checkpoint left frames behind",
                        mapOf(
                            "walBytes" to state.bytes.toString(),
                            "framesInLog" to state.framesInLog.toString(),
                            "framesCheckpointed" to state.framesCheckpointed.toString(),
                        ),
                    )
                }
            }
        }
    }

    // Retention has to be *run*, not merely configured. Until M7 `enforce()` had no caller: the
    // sweep was written, tested and never scheduled, which means the size cap could not have
    // fired and the disk would have filled with the feature reporting itself as present.
    launch {
        while (true) {
            val state =
                try {
                    retention.enforce()
                } catch (cancelled: CancellationException) {
                    // The server stopping is not a sweep that failed: answered as one, this loop
                    // goes round again after its own cancellation.
                    throw cancelled
                } catch (failure: Throwable) {
                    // The loop must survive a failed sweep -- the next interval tries again -- but
                    // it used to survive it in silence, and a size cap that never fires looks
                    // exactly like one that has nothing to do.
                    self?.log(
                        Level.WARN,
                        "Retention",
                        "retention sweep failed",
                        mapOf("failure" to (failure::class.simpleName ?: "unknown")),
                    )
                    null
                }
            if (state != null && self != null) {
                // A sweep is rare by construction — once an hour — so logging it cannot feed
                // the loop that logging every batch would.
                self.log(
                    Level.INFO,
                    "Retention",
                    "retention swept",
                    mapOf(
                        "liveDays" to state.liveDays.size.toString(),
                        "databaseBytes" to state.databaseBytes.toString(),
                    ),
                )
            }
            delay(RETENTION_INTERVAL_MILLIS)
        }
    }

    // Installed outside `routing`: the SDK extension puts up its own routing and cannot be nested.
    // No token, no MCP at all — absence of configuration yields a closed state (research D9).
    installMcp(config, get())

    routing {
        // MOVED FROM `/health`, WHICH IS KORE'S LIVENESS ALIAS NOW. The body is unchanged and it is
        // still the thing an operator finds out about too late otherwise — the size cap and the
        // eviction state — but it was never a probe: mixed into one route with liveness it could not
        // fail without restarting the pod, and mixed into readiness it would take the only instance
        // out of service over a disk problem, which is an outage of the thing you read to diagnose
        // the disk problem.
        get("/health/retention") {
            val state = retention.state()
            call.respondText(TracyJson.encodeToString(state), io.ktor.http.ContentType.Application.Json)
        }
        ingestRoutes()
        traceRoutes()
        queryRoutes()
    }
}

/** How often the log is looked at. The sweep itself is far rarer — see the two triggers above. */
private const val WAL_POLL_MILLIS: Long = 1000

/** Hourly. Partitions are daily, so anything finer only re-checks the size cap. */
private const val RETENTION_INTERVAL_MILLIS: Long = 60 * 60 * 1000
