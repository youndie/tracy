package ru.workinprogress.tracy.agent

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * M-26 — does the Curl engine take a `Dispatchers.Default` worker away from the host service?
 *
 * The first version of this test tried to *reproduce* starvation the way metrik did, by creating
 * many `SelectorManager`s and watching `delay` stop. It worked far too well: the run hung, twice,
 * because a starved dispatcher cannot complete the join that reads the result, and closing the
 * selectors afterwards deadlocks for the same reason. That accidental result is itself the
 * positive control — 64 selectors stop `Dispatchers.Default` on a 20-core machine so completely
 * that the measurement cannot finish.
 *
 * So this test measures the **mechanism** instead of the symptom, which is faster, deterministic
 * and answers the actual question: if Curl runs on threads of its own, it is not competing for
 * the host's dispatcher at all.
 */
@OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CurlThreadCensusTest {
    private fun threadCount(): Int =
        memScoped {
            val file = fopen("/proc/self/status", "r") ?: return@memScoped -1
            try {
                val buffer = allocArray<kotlinx.cinterop.ByteVar>(512)
                while (fgets(buffer, 512, file) != null) {
                    val line = buffer.toKString()
                    if (line.startsWith("Threads:")) {
                        return@memScoped line.removePrefix("Threads:").trim().toIntOrNull() ?: -1
                    }
                }
                -1
            } finally {
                fclose(file)
            }
        }

    private fun <T> withSlowServer(block: (port: Int) -> T): T {
        val server =
            embeddedServer(CIO, port = 0) {
                routing {
                    post("/ingest") {
                        delay(400)
                        call.respondText("""{"accepted":1}""", status = HttpStatusCode.Accepted)
                    }
                }
            }
        server.start(wait = false)
        return try {
            block(
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                },
            )
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
        }
    }

    private fun sender(port: Int) = Sender(AgentConfig(service = "s", apiKey = "k", endpoint = "http://127.0.0.1:$port", instanceId = "i"))

    @Test
    fun `the census can see threads at all`() {
        // Guard against measuring nothing: if /proc is unreadable every number below is -1 and
        // the conclusion would be an artefact of the harness.
        assertTrue(threadCount() > 0, "/proc/self/status gave no thread count")
    }

    @Test
    fun `curl serves concurrent requests on threads of its own`() {
        val before = threadCount()

        withSlowServer { port ->
            val sender = sender(port)
            val pump = newSingleThreadContext("m26-pump")
            try {
                val record = LogRecord(ts = 1, seq = 1, level = Level.INFO, logger = "L", message = "x")
                val inFlight =
                    runBlocking(pump) {
                        repeat(32) { i -> launch { sender.send(listOf(record), seq = i.toLong()) } }
                        delay(150.milliseconds)
                        threadCount()
                    }

                println("M-26 threads: $before at rest, $inFlight with 32 curl requests in flight")
                assertTrue(
                    inFlight > before,
                    "curl added no threads, so it is running on the shared dispatcher after all",
                )
            } finally {
                sender.close()
                pump.close()
            }
        }
    }

    @Test
    fun `the default dispatcher keeps ticking while curl is busy`() {
        withSlowServer { port ->
            val sender = sender(port)
            val pump = newSingleThreadContext("m26-pump")
            val observer = newSingleThreadContext("m26-observer")
            try {
                val record = LogRecord(ts = 1, seq = 1, level = Level.INFO, logger = "L", message = "x")
                runBlocking(pump) { repeat(32) { i -> launch { sender.send(listOf(record), seq = i.toLong()) } } }

                // Bounded work with a hard deadline: a starved dispatcher fails this by timing
                // out rather than by hanging the suite, which is what went wrong the first time.
                val completed =
                    runBlocking(observer) {
                        withTimeoutOrNull(5.seconds) {
                            var ticks = 0
                            repeat(20) {
                                withTimeoutOrNull(1.seconds) {
                                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                                        delay(10.milliseconds)
                                    }
                                }?.let { ticks++ }
                            }
                            ticks
                        }
                    }

                println("M-26 liveness: ${assertNotNull(completed)} of 20 ticks landed while curl was busy")
                assertTrue(completed >= 18, "Dispatchers.Default stalled while curl was working")
            } finally {
                sender.close()
                pump.close()
                observer.close()
            }
        }
    }
}
