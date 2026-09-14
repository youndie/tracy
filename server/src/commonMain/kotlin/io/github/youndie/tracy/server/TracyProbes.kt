package io.github.youndie.tracy.server

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.kore.health.HealthRegistry
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.health.storeCheck
import kotlinx.coroutines.CoroutineScope

/**
 * The three questions a deployment asks this process, and the one dependency behind them.
 *
 * They are three because they fail for different reasons and are read by different machinery. Until
 * this, the chart pointed **both** its probes at `/health`, which is a readiness probe that cannot
 * fail while the process is alive: a tracy that had lost its database stayed in the Service and went
 * on being sent batches, and the only thing that could have taken it out was the liveness probe
 * restarting it — the heaviest possible response to a store problem.
 *
 * * **startup** — a latch. Migrations run before the engine serves, so this answers `503` only
 *   while they do, and never again: Kubernetes runs the startup probe only at startup, and a later
 *   failure there would restart a pod that is trying to stop.
 * * **readiness** — the store check below, plus the shutdown latch. The one that is *meant* to
 *   fail: it goes false in the announce stage, so batches stop arriving before anything closes.
 * * **liveness** — nothing declares this process wedged today. The gate exists because `/health`
 *   and `/health/live` have to answer something, and an untripped gate is the honest state.
 *
 * **Retention is deliberately not a readiness check.** A full disk is a real problem and it is
 * reported at `/health/retention`, unchanged — but a tracy that cannot evict can still answer
 * queries and still accept batches for the day it has room for, and taking the only instance out of
 * the Service would turn a disk problem into an outage of the thing you read to diagnose it.
 *
 * The store check is a `SELECT 1`, not `pool.acquire()`: a pool hands back an idle connection while
 * the store behind it is gone, and only a statement that reaches SQLite says the database answered.
 */
public class TracyProbes(
    db: ISQLite,
) {
    private val checks =
        HealthRegistry(
            listOf(
                storeCheck("sqlite") { db.fetchAll("SELECT 1;").getOrThrow() },
            ),
        )

    public val startup: StartupGate = StartupGate()
    public val readiness: ReadinessGate = ReadinessGate(checks)
    public val liveness: LivenessGate = LivenessGate()

    /**
     * Starts the polling loop. **Nothing else does**: the registry caches results and refreshes them
     * on a loop of its own, so without this call `/health/ready` answers from checks that have never
     * run — `UNKNOWN`, for ever, which reads as a broken dependency rather than a missing call.
     */
    public fun start(scope: CoroutineScope) {
        checks.start(scope)
    }

    public fun stop() {
        checks.stop()
    }
}
