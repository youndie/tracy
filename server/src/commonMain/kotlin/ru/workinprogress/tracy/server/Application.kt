package ru.workinprogress.tracy.server

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.tracy.server.db.EntityKeyBudget
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.db.migrateDb
import ru.workinprogress.tracy.server.ingest.ingestRoutes
import ru.workinprogress.tracy.server.mcp.ToolFacade
import ru.workinprogress.tracy.server.mcp.installMcp
import ru.workinprogress.tracy.server.query.EntityRepository
import ru.workinprogress.tracy.server.query.QueryRepository
import ru.workinprogress.tracy.server.query.queryRoutes
import ru.workinprogress.tracy.server.retention.Retention
import ru.workinprogress.tracy.server.trace.SpanSearchRepository
import ru.workinprogress.tracy.server.trace.TraceRepository
import ru.workinprogress.tracy.server.trace.traceRoutes
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.TracyJson

public fun main() {
    val config = ServerConfig.fromEnv()
    val db = openDatabase(config.dbPath)

    embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
        module(config, db)
    }.start(wait = true)
}

/**
 * Opens the database and runs migrations **before** the engine starts.
 *
 * `runBlocking` here is deliberate: a server that opened its port ahead of a ready schema would
 * answer the first requests with errors, and the first requests are exactly the ones an agent
 * retries hardest.
 */
public fun openDatabase(path: String): ISQLite {
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
                    .maxConnections(10)
                    .build(),
        )

    runBlocking { db.migrateDb() }
    return db
}

public fun Application.module(
    config: ServerConfig,
    db: ISQLite,
) {
    val budget =
        EntityKeyBudget(
            db = db,
            refsPerMinute = config.entityRefsPerMinute,
            suppressedTtlMillis = config.suppressedTtlDays * 86_400_000L,
            clock = { currentTimeMillis() },
        )
    val repository = IngestRepository(db, budget = budget, clock = { currentTimeMillis() })
    val retention =
        Retention(
            db = db,
            retentionDays = config.retentionDays,
            countsRetentionDays = config.countsRetentionDays,
            maxBytes = config.maxDbBytes,
            clock = { currentTimeMillis() },
        )

    // Self-observation. Written straight to the repository — see SelfObservation for why the
    // loopback version was reverted.
    val self =
        config.selfService?.let {
            SelfObservation(repository, it, config.instanceId, release = null, clock = { currentTimeMillis() })
        }
    if (self != null) {
        launch { self.log(Level.INFO, "Boot", "tracy started", mapOf("retentionDays" to config.retentionDays.toString())) }
    }

    // Retention has to be *run*, not merely configured. Until M7 `enforce()` had no caller: the
    // sweep was written, tested and never scheduled, which means the size cap could not have
    // fired and the disk would have filled with the feature reporting itself as present.
    launch {
        while (true) {
            val state = runCatching { retention.enforce() }.getOrNull()
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
    installMcp(
        config,
        ToolFacade(QueryRepository(db), TraceRepository(db), SpanSearchRepository(db), EntityRepository(db)),
    )

    routing {
        get("/health") {
            // Not liveness alone: the size cap and eviction are the two things an operator finds
            // out about too late otherwise.
            val state = retention.state()
            call.respondText(TracyJson.encodeToString(state), io.ktor.http.ContentType.Application.Json)
        }
        ingestRoutes(config, repository) { service -> budget.suppressedFor(service) }
        traceRoutes(TraceRepository(db), SpanSearchRepository(db))
        queryRoutes(db, QueryRepository(db), EntityRepository(db), budget)
    }
}

/** Hourly. Partitions are daily, so anything finer only re-checks the size cap. */
private const val RETENTION_INTERVAL_MILLIS: Long = 60 * 60 * 1000
