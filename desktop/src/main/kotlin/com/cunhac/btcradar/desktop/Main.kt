package com.cunhac.btcradar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cunhac.btcradar.ui.theme.C
import com.cunhac.btcradar.ui.theme.RadarTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Radar BTC",
        state = rememberWindowState(width = 1440.dp, height = 880.dp),
    ) {
        RadarTheme { Dashboard() }
    }
}

@Composable
private fun Dashboard() {
    val scope = rememberCoroutineScope()
    val map = remember { MapModel(scope) }
    val liq = remember { LiqModel(scope) }
    val etf = remember { EtfModel(scope) }

    Row(Modifier.fillMaxSize().background(C.Bg)) {
        Box(Modifier.weight(1.15f).fillMaxHeight()) { MapPanel(map) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.Line))
        Box(Modifier.weight(1f).fillMaxHeight()) { LiqPanel(liq) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(C.Line))
        Box(Modifier.weight(0.9f).fillMaxHeight()) { EtfPanel(etf) }
    }
}
