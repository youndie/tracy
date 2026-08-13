package ru.workinprogress.tracy.server

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import org.koin.dsl.module
import ru.workinprogress.tracy.server.db.EntityKeyBudget
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.mcp.ToolFacade
import ru.workinprogress.tracy.server.query.EntityRepository
import ru.workinprogress.tracy.server.query.QueryRepository
import ru.workinprogress.tracy.server.retention.Retention
import ru.workinprogress.tracy.server.trace.SpanSearchRepository
import ru.workinprogress.tracy.server.trace.TraceRepository

/**
 * Everything the server owns, built once.
 *
 * Before this, `Application.module` constructed the repositories inline and each of the four read
 * repositories was built **twice** — one set for [ToolFacade], another for the HTTP routes. Two
 * instances of a stateless class are harmless, which is exactly why it survived six milestones;
 * the first one to gain a cache or a counter would have diverged silently, and diverged between
 * MCP and HTTP — between two answers to the same question.
 *
 * Koin rather than `ktor-server-di`: `koin-ktor` publishes for the native targets, so
 * `by inject<T>()` works inside `Route` extensions the same way it does on the JVM. The
 * alternative's `resolve()` is suspend, which means threading dependencies through every route
 * function as parameters — the shape this replaces, and the reason the DI added in M0 was never
 * wired up at all.
 */
public fun serverModule(
    config: ServerConfig,
    db: ISQLite,
): org.koin.core.module.Module =
    module {
        single { config }
        single { db }

        single {
            EntityKeyBudget(
                db = db,
                refsPerMinute = config.entityRefsPerMinute,
                suppressedTtlMillis = config.suppressedTtlDays * 86_400_000L,
                clock = { currentTimeMillis() },
            )
        }
        single { IngestRepository(db, budget = get(), clock = { currentTimeMillis() }) }

        single { QueryRepository(db) }
        single { TraceRepository(db) }
        single { SpanSearchRepository(db) }
        single { EntityRepository(db) }

        single {
            Retention(
                db = db,
                retentionDays = config.retentionDays,
                countsRetentionDays = config.countsRetentionDays,
                maxBytes = config.maxDbBytes,
                clock = { currentTimeMillis() },
            )
        }

        // A singleton for a reason beyond tidiness: the facade remembers which entry ids it has
        // shown, and that memory is what the two-phase gate checks a report against. A second
        // instance would answer "you were never shown this" about entries it had just shown.
        single { ToolFacade(get(), get(), get(), get()) }

        config.selfService?.let { service ->
            single {
                SelfObservation(
                    repository = get(),
                    service = service,
                    instanceId = config.instanceId,
                    release = null,
                    clock = { currentTimeMillis() },
                )
            }
        }
    }
