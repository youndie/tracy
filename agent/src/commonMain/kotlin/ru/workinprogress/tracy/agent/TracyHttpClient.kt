package ru.workinprogress.tracy.agent

import io.ktor.client.HttpClient

/**
 * The engine is per-platform on purpose, and both reasons were paid for in metrik:
 *
 * - `HttpClient(CIO)` on Kotlin/Native fails with "TLS sessions are not supported on Native
 *   platform", so every https endpoint stays silent (research 1.5, metrik 1.7);
 * - CIO builds on a `SelectorManager`, and a selector permanently occupies a `Dispatchers.Default`
 *   worker — inside a host process that is a worker taken away from the service we promised not to
 *   affect (research D4).
 *
 * Whether Curl actually avoids the second problem is a hypothesis, not a fact; M-26 measures it.
 */
internal expect fun tracyHttpClient(): HttpClient
