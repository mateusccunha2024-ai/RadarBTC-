package com.cunhac.btcradar.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.cunhac.btcradar.engine.Hub
import com.cunhac.btcradar.ui.theme.RadarTheme
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/** Impede duas instâncias abertas ao mesmo tempo (conexões e banco duplicados). */
private fun acquireSingleInstance(): FileLock? = runCatching {
    RandomAccessFile(File(AppDirs.root, "app.lock"), "rw").channel.tryLock()
}.getOrNull()

private fun loadSettings(hub: Hub) {
    hub.wallThresholds.small = SettingsStore.d("w.small", 1.0)
    hub.wallThresholds.medium = SettingsStore.d("w.medium", 3.0)
    hub.wallThresholds.large = SettingsStore.d("w.large", 5.0)
    hub.wallThresholds.extreme = SettingsStore.d("w.extreme", 10.0)
    hub.walls.rangePct = SettingsStore.d("w.range", 5.0)
    hub.liqThresholds.large = SettingsStore.d("lq.large", 1.0)
    hub.liqThresholds.extreme = SettingsStore.d("lq.extreme", 5.0)
    hub.large.minBtc = SettingsStore.d("lt.min", 2.0)
}

fun main() {
    val lock = acquireSingleInstance()
    if (lock == null) {
        JOptionPane.showMessageDialog(null, "O Radar BTC já está aberto.", "Radar BTC", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }
    FileLog.info("Iniciando Radar BTC V8 (Java ${System.getProperty("java.version")})")
    val db = SqliteHistory()
    db.error?.let { FileLog.info("Histórico: $it") }
    val hub = Hub()
    loadSettings(hub)
    hub.history = db
    hub.logSink = { FileLog.write(it) }
    hub.start()
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { hub.stop() }; FileLog.info("Encerrado") })

    application {
        Window(
            onCloseRequest = { hub.stop(); exitApplication() },
            title = "BTC Orderflow Terminal V8 — Radar BTC",
            state = rememberWindowState(width = 1500.dp, height = 920.dp, placement = WindowPlacement.Maximized),
        ) {
            RadarTheme {
                val scope = rememberCoroutineScope()
                val map = remember { MapModel(scope) }
                Terminal(AppContext(hub, db, map))
            }
        }
    }
}
