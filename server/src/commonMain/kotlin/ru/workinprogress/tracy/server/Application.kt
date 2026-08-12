package ru.workinprogress.tracy.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val config = ServerConfig.fromEnv()

    embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

/**
 * M0 keeps this deliberately minimal: it exists to prove the toolchain, not to serve traffic.
 * A linked native binary that boots CIO and reads its configuration is the only evidence that
 * cross-compilation is wired correctly — and it is cheaper to get that evidence now than after
 * the ingest path is built on top of it.
 */
fun Application.module(config: ServerConfig) {
    routing {
        get("/health") {
            call.respondText("ok")
        }
    }

    // Referenced so that an unused-configuration mistake surfaces at compile time rather than
    // as a silently ignored environment variable.
    check(config.httpPort > 0)
}
