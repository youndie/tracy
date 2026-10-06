package io.github.youndie.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

// OkHttp: the engine an Android app already has, with the platform's TLS and connection pool.
internal actual fun tracyHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp, configure)
