package io.github.youndie.tracy.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the sample sends, and with which client key (README). */
const val TRACY_ENDPOINT: String = "http://localhost:8080"
const val CLIENT_KEY: String = "dev-app-key"

/**
 * Two things an app reports: an action a person took, which opens a trace (research-clients K2), and a
 * screen that degraded because the server sent a component this build does not know, recorded with
 * the wire type as an indexed field so it can be counted later.
 */
@Composable
fun App(telemetry: Telemetry) {
    var status by remember { mutableStateOf("Nothing sent yet") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("tracy — an app writing to it (${telemetry.name})")
        Button("Open the order screen") {
            scope.launch {
                telemetry.action("order.open") { delay(50) }
                status = "Action recorded"
            }
        }
        Button("Draw an unknown component") {
            scope.launch {
                telemetry.degraded(originalType = "promo-carousel-v3")
                status = "Degradation recorded"
            }
        }
        BasicText(status)
    }
}

@Composable
private fun Button(
    label: String,
    onClick: () -> Unit,
) {
    BasicText(label, Modifier.border(1.dp, Color.DarkGray).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp))
}
