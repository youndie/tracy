package ru.workinprogress.tracy.agent

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.workinprogress.tracy.wire.Level
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * The thresholds are **relative**, and that is the second lesson. The first version asserted wall
 * clock — under 300 ns suppressed, under 30 µs accepted — calibrated on an idle twenty-core box.
 * CI runs on a shared four-core runner about 3.4x slower and the build went red on code that had
 * not changed: an absolute threshold on shared hardware measures the hardware. The ratio between
 * the two paths does not: 335 on the reference box against 309 on CI, across that 3.4x. So the
 * accepted path is measured against the suppressed one, and the suppressed path is checked by
 * what actually matters — that the block is never evaluated — rather than by a stopwatch.
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

            var blockRuns = 0
            val perOp =
                nanosPerOp(2_000_000) {
                    log.debug("this never happens") {
                        blockRuns++
                        field("payload", "expensive")
                    }
                }

            println("M-28 suppressed: $perOp ns/op")
            // The invariant, stated as itself rather than as a stopwatch reading: the block is
            // what costs money — a string built, a map filled — and below the level threshold it
            // must never run at all. This holds on any hardware, which a nanosecond bound does not.
            assertEquals(0, blockRuns, "the block was evaluated $blockRuns times below the level threshold")
            assertTrue(agent.drainBatch().isEmpty())
            // Kept as a loose catastrophe guard: 30-60 ns on the reference box, 137 on CI.
            assertTrue(perOp < 5_000, "a suppressed record cost $perOp ns — something is running that should not")
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

                // The baseline is measured here, on this machine, in this run — that is the whole
                // point. A number carried in from another machine is a number about that machine.
                val suppressed = nanosPerOp(200_000) { log.trace("below the threshold") }

                val perOp =
                    nanosPerOp(500_000) {
                        log.info("order created") {
                            field("orderId", "12345")
                            field("total", 500)
                        }
                    }

                val ratio = perOp / suppressed
                println("M-28 accepted: $perOp ns/op, suppressed $suppressed ns/op, ratio $ratio")
                // ~10.5 µs on the reference box and ~42 µs on CI — different machines, same ratio
                // (335 against 309). Redaction, the template counter, the record and the builder
                // all allocate, and allocation dominates rather than any single rule (risk 1).
                assertTrue(
                    ratio < 1_000,
                    "an accepted record cost $ratio times a suppressed one ($perOp ns against " +
                        "$suppressed ns) — something on the hot path got heavy",
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
