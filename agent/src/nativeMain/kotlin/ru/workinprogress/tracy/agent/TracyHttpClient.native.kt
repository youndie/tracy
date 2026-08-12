package ru.workinprogress.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

// The Curl klib carries static libcurl/libssl/libcrypto, so the image needs no libcurl4 —
// only ca-certificates, which OpenSSL reads from the system bundle.
internal actual fun tracyHttpClient(): HttpClient = HttpClient(Curl)
