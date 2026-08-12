package ru.workinprogress.tracy.agent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.workinprogress.tracy.wire.Level
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * M-28 — the cost tracy adds to the hot path of somebody else's service (research risk 1).
 *
 * Two numbers matter, for different reasons:
 *
 * - a **suppressed** record must be nearly free, because a service with `DEBUG` calls all over it
 *   pays this on every one of them and gets nothing back;
 * - an **accepted** record must be cheap enough that a few per request do not show in latency.
 *
 * The first version of this benchmark wrapped every iteration in its own `runBlocking` and
 * reported 2.3 µs for a suppressed record — a measurement of coroutine construction, not of
 * logging. Same mistake as M-26 in a different costume: the harness was the thing being measured.
 * The loop now lives inside a single coroutine.
 *
 * This is an indicator, not a gate. It shares a machine with everything else, so the thresholds
 * have room: an order-of-magnitude regression trips them, a 20% drift does not and should not.
 */
class HotPathBenchmarkTest {
    private fun agent(level: Level) =
        TracyAgent(
            config =
                AgentConfig(
                    service = "orders-api",
                    apiKey = "k",
                    endpoint = "http://x",
                    instanceId = "i",
                    level = level,
                    maxBufferBytes = 256 * 1024 * 1024,
                ),
            clock = { 1754049600000L },
        )

    private suspend inline fun nanosPerOp(
        iterations: Int,
        block: (Int) -> Unit,
    ): Double {
        val started = TimeSource.Monotonic.markNow()
        for (i in 0 until iterations) block(i)
        return started.elapsedNow().inWholeNanoseconds.toDouble() / iterations
    }

    @Test
    fun `a suppressed record is nearly free`() =
        runBlocking {
            val agent = agent(Level.INFO)
            val log = agent.logger("OrdersRouting")

            nanosPerOp(100_000) { log.debug("warm up") }

            val perOp =
                nanosPerOp(2_000_000) {
                    log.debug("this never happens") { field("payload", "expensive") }
                }

            println("M-28 suppressed: $perOp ns/op")
            // Measured at 30-60 ns on linuxX64. The threshold is a regression guard with room,
            // not the target: an order of magnitude trips it, ordinary noise does not.
            assertTrue(
                perOp < 300,
                "a suppressed record cost $perOp ns — the level check is no longer " +
                    "short-circuiting before the block",
            )
            assertTrue(agent.drainBatch().isEmpty())
        }

    @Test
    fun `an accepted record stays cheap`() =
        runBlocking {
            val agent = agent(Level.INFO)
            val log = agent.logger("OrdersRouting")
            val trace = TracyTraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", false)

            withContext(trace) {
                nanosPerOp(50_000) { log.info("warm up") }
                trace.takePending()

                val perOp =
                    nanosPerOp(500_000) {
                        log.info("order created") {
                            field("orderId", "12345")
                            field("total", 500)
                        }
                    }

                println("M-28 accepted: $perOp ns/op")
                // Measured at ~12 µs on linuxX64 for a record with two fields. Redaction, the
                // template counter, the record and the builder all allocate, and allocation is
                // what dominates here rather than any single rule — see research risk 1.
                assertTrue(
                    perOp < 30_000,
                    "an accepted record cost $perOp ns — something on the hot path got heavy",
                )
            }
        }

    @Test
    fun `redaction does not dominate the accepted path`() =
        runBlocking {
            val agent = agent(Level.INFO)
            val log = agent.logger("L")
            val trace = TracyTraceContext("4bf9", "00f0", false)

            withContext(trace) {
                nanosPerOp(50_000) { log.info("warm up") }
                trace.takePending()

                val plain = nanosPerOp(200_000) { log.info("order created") }
                trace.takePending()

                val withUrl = nanosPerOp(200_000) { log.info("GET https://billing.internal/charge/12345") }
                trace.takePending()

                println("M-28 redaction: $plain ns plain against $withUrl ns with a url")
                // Every message goes through the redaction rules, so a pathological pattern here
                // would be paid by every log call in every service that installs tracy.
                assertTrue(
                    withUrl < plain * 10,
                    "redacting a url cost $withUrl ns against $plain ns plain",
                )
            }
        }
}
