package io.github.youndie.tracy.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.MainScope

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val telemetry = Telemetry(MainScope(), instanceId = "browser")
    ComposeViewport(document.body!!) { App(telemetry) }
}
