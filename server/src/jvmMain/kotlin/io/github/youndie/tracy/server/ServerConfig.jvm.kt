package io.github.youndie.tracy.server

actual fun readEnv(name: String): String? = System.getenv(name)
