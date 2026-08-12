package ru.workinprogress.tracy.server.mcp

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Authorisation and host checking for the MCP endpoint.
 *
 * Everything here is a lesson from katcher, where the same endpoint went to production:
 *
 * - **nothing is installed without a token.** Absence of configuration yields a closed state, not
 *   an open one — no route, no secret in the chart, no ingress bypass;
 * - **the browser contour's trust is not reused.** A reverse proxy that authorises by
 *   `X-Auth-Request-*` accepts whatever identity is claimed, which is acceptable for a browser and
 *   a gift to an attacker for a machine endpoint;
 * - **`Host` is checked.** The SDK's transport defends against DNS rebinding by comparing `Host`
 *   against `allowedHosts`, which defaults to localhost only — and that class of failure cannot be
 *   reproduced locally, because locally the host always *is* localhost;
 * - **a 401 stays a 401.** A global redirect to `/login` sends a machine client a login page
 *   instead of an error code, which is what broke katcher's endpoint from the outside.
 */
public class McpAuth(
    private val token: String,
    private val allowedHosts: List<String>,
) {
    public fun check(call: ApplicationCall): McpAuthResult {
        val host = call.request.header(HttpHeaders.Host)?.substringBefore(':')
        if (allowedHosts.isNotEmpty() && host != null && allowedHosts.none { it.equals(host, ignoreCase = true) }) {
            return McpAuthResult.InvalidHost(host)
        }

        val header = call.request.header(HttpHeaders.Authorization) ?: return McpAuthResult.Unauthorized
        val presented = header.removePrefix("Bearer ").trim()
        return if (constantTimeEquals(presented, token)) McpAuthResult.Allowed else McpAuthResult.Unauthorized
    }

    /** Length is not secret; the value is. Comparison does not stop at the first difference. */
    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

public sealed interface McpAuthResult {
    public data object Allowed : McpAuthResult

    public data object Unauthorized : McpAuthResult

    public data class InvalidHost(
        val host: String,
    ) : McpAuthResult
}
