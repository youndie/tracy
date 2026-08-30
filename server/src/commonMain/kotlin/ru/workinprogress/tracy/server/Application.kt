package ru.workinprogress.tracy.server

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import ru.workinprogress.tracy.server.db.migrateDb
import ru.workinprogress.tracy.server.ingest.ingestRoutes
import ru.workinprogress.tracy.server.mcp.installMcp
import ru.workinprogress.tracy.server.query.queryRoutes
import ru.workinprogress.tracy.server.retention.Retention
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
    installMcp(config, get())

    routing {
        get("/health") {
            // Not liveness alone: the size cap and eviction are the two things an operator finds
            // out about too late otherwise.
            val state = retention.state()
            call.respondText(TracyJson.encodeToString(state), io.ktor.http.ContentType.Application.Json)
        }
        ingestRoutes()
        traceRoutes()
        queryRoutes()
    }
}

/** Hourly. Partitions are daily, so anything finer only re-checks the size cap. */
private const val RETENTION_INTERVAL_MILLIS: Long = 60 * 60 * 1000
