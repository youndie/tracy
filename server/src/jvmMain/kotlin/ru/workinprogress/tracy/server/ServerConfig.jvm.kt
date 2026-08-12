package ru.workinprogress.tracy.server

actual fun readEnv(name: String): String? = System.getenv(name)
