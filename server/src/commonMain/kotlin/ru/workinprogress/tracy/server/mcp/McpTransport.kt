package ru.workinprogress.tracy.server.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import ru.workinprogress.tracy.server.ServerConfig

/**
 * Installs the MCP transport — and only when a token is configured.
 *
 * Authorisation is a pipeline interceptor rather than `authenticate { }`, because
 * `mcpStatelessStreamableHttp` is an extension on `Application` that installs its own routing and
 * cannot be nested inside an auth block (research 1.8). Stateless is the right shape here: read-only
 * tools have no session worth resuming.
 */
public fun Application.installMcp(
    config: ServerConfig,
    facade: ToolFacade,
) {
    val token = config.mcpToken ?: return
    val auth = McpAuth(token, config.mcpAllowedHosts)

    intercept(ApplicationCallPipeline.Plugins) {
        val call = context
        if (call.request.path() != MCP_PATH) return@intercept

        when (val verdict = auth.check(call)) {
            is McpAuthResult.InvalidHost -> {
                call.respondText(
                    """{"error":"Invalid Host: ${verdict.host}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                finish()
            }

            McpAuthResult.Unauthorized -> {
                // A code, not a login page: the client here is a machine, and a global redirect
                // to /login is exactly what broke katcher's endpoint from the outside.
                call.respondText(
                    """{"error":"unauthorized"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
                finish()
            }

            // Nothing to do: the call falls through to the transport below.
            McpAuthResult.Allowed -> {}
        }
    }

    mcpStatelessStreamableHttp(
        path = MCP_PATH,
        // The SDK's own defence against DNS rebinding. Left on whenever hosts are configured;
        // its default allows localhost only, which is why the failure it prevents cannot be
        // reproduced on a developer machine.
        enableDnsRebindingProtection = config.mcpAllowedHosts.isNotEmpty(),
        allowedHosts = config.mcpAllowedHosts,
    ) {
        // The block is a factory returning a Server, not a receiver on one — the kind of detail
        // the docs site gets wrong and the klib does not (research 1.8).
        Server(
            serverInfo = Implementation(name = "tracy", version = "0.1"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null)),
                ),
        ).apply { registerTools(facade) }
    }
}

internal const val MCP_PATH: String = "/mcp"
