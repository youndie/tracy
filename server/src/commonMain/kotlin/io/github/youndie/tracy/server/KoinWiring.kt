package io.github.youndie.tracy.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.ktor.plugin.setKoin

/**
 * Koin for the application, WITHOUT the per-call scope that `install(Koin)` opens.
 *
 * koin-ktor's plugin creates a `RequestScope` on every call and closes it when the response is
 * sent. On Kotlin/Native every Koin scope owns a stately `Lock`, and on Linux that lock is a
 * `pthread_mutex_t` in a cinterop `Arena` that only `Lock.close()` frees — which neither
 * `Scope.close()` nor a cleaner ever calls. Every request, a 404 included, left one or two of them
 * in malloc (16 + 48 bytes each): 2.9 million chunks, 154 MB, after four days on the stand, against
 * 0.6 MB held by SQLite. Apple targets build that lock on `NSRecursiveLock` and do not leak, which
 * is why nothing on a phone ever showed it.
 *
 * tracy resolves nothing from `call.scope`, so the scope is dropped rather than repaired. `get` and
 * `inject` in routes keep working: they read the application attribute that [setKoin] writes. The
 * container is a `koinApplication`, not `startKoin`, so two applications in one test process no
 * longer share (and stop) a global one.
 */
public fun Application.installKoinWithoutCallScope(vararg modules: Module): Koin {
    val koin = koinApplication { modules(*modules) }.koin
    setKoin(koin)
    monitor.subscribe(ApplicationStopping) { koin.close() }
    return koin
}
