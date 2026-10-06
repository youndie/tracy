package io.github.youndie.tracy.sample

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.MainScope

fun main() {
    val telemetry = Telemetry(MainScope(), instanceId = "desktop")
    application {
        Window(onCloseRequest = ::exitApplication, title = "tracy app sample") { App(telemetry) }
    }
}
