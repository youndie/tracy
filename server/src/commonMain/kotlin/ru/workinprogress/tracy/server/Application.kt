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
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import ru.workinprogress.tracy.server.db.EntityKeyBudget
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.db.migrateDb
import ru.workinprogress.tracy.server.ingest.ingestRoutes

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

    routing {
        get("/health") { call.respondText("ok") }
        ingestRoutes(config, repository) { service -> budget.suppressedFor(service) }
    }
}
