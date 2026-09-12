package io.github.youndie.tracy.server

import io.github.youndie.sborka.probe.configuredProbeTarget
import io.github.youndie.sborka.probe.orFail
import io.github.youndie.sborka.probe.probePlatform
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/*
 * Does the platform under THIS target do what this server assumes?
 *
 * Every divergence this portfolio has paid for was here rather than in the standard library: a
 * hostname that would not resolve on Kotlin/Native, a client engine with no TLS, a Ktor plugin
 * published for one target. The assertions are `sborka:platform-probe`, and they go through ktor's
 * own API rather than the syscall under it — the resolution failure was in that API while every
 * syscall below it worked.
 *
 * `runBlocking`, not `runTest`: the test dispatcher's clock is virtual, so a socket with a timeout
 * around it reports a timeout before it has done anything, which reads as a platform verdict and is
 * a harness one.
 */
class PlatformTest {
    @Test
    fun theBuildsProbeTargetReachesThisTest() {
        // NOT DECORATION. The host reaches the test through an environment variable that
        // `sborka.parity` sets and `platform-probe` reads, and the two spell it in different builds.
        // Without this assertion the names could drift apart and every probe below would quietly
        // fall back to its default — a lookup test that passes because it looked nowhere.
        val configured = assertNotNull(configuredProbeTarget(), "sborka.parity did not reach the test")
        assertEquals("localhost", configured.host)
    }

    @Test
    fun thePlatformDoesWhatThisServerAssumes() {
        runBlocking {
            SelectorManager(Dispatchers.Default).use { selector ->
                // The port is bound here rather than configured, so the suite needs nothing running
                // beside it. What that costs is stated rather than hidden: `localhost` is answered
                // from the hosts file, so this exercises ktor's address handling and the socket on
                // this target and NOT the DNS resolver. Pointing `parityProbe` at a service name
                // would, and needs a stand in CI that this repository does not have.
                val listener = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
                listener.use {
                    val port = (it.localAddress as InetSocketAddress).port
                    val report = probePlatform(configuredProbeTarget()?.host ?: "localhost", port)
                    println(report)
                    report.orFail()
                }
            }
        }
    }
}
