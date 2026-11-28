package com.cunhac.btcradar.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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

/**
 * Segunda abertura do app: em vez do aviso "já está aberto", pede para a janela que já está aberta vir para a frente
 * (porta local 127.0.0.1 guardada em app.port).
 */
private object Reopen {
    private val portFile get() = File(AppDirs.root, "app.port")
    @Volatile var onShow: (() -> Unit)? = null

    fun listen() = Thread {
        runCatching {
            val ss = java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
            portFile.writeText(ss.localPort.toString())
            while (true) runCatching {
                ss.accept().use { c -> c.soTimeout = 2_000; if (c.getInputStream().bufferedReader().readLine() == "show") onShow?.invoke() }
            }
        }
    }.apply { isDaemon = true; name = "reabrir"; start() }

    /** true se a janela aberta recebeu o pedido. */
    fun signal(): Boolean = runCatching {
        val port = portFile.readText().trim().toInt()
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 2_000)
            s.getOutputStream().write("show\n".toByteArray()); s.getOutputStream().flush()
        }
        true
    }.getOrDefault(false)
}

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
    hub.refreshMs = SettingsStore.i("ui.refresh", 1_500).toLong().coerceIn(500L, 3_000L)
}

fun main(args: Array<String>) {
    // Processo separado da IA do Jarvis (aberto pelo próprio app): só roda a IA, sem janela e sem trava de instância.
    if (args.firstOrNull() == JarvisLlmWorker.ARG) { runCatching { JarvisLlmWorker.serve(args) }; exitProcess(0) }
    if (args.firstOrNull() == LlmProcessSelfTest.ARG) { LlmProcessSelfTest.run(args); exitProcess(0) }
    val lock = acquireSingleInstance()
    if (lock == null) {
        if (Reopen.signal()) exitProcess(0)
        JOptionPane.showMessageDialog(null, "O Radar BTC já está aberto.", "Radar BTC", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }
    runCatching { JarvisCrash.install() } // guarda o motivo se algum erro fechar o app
    Reopen.listen()
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
            onPreviewKeyEvent = { e ->
                if (e.type == KeyEventType.KeyDown && e.isCtrlPressed) when (e.key) {
                    Key.Equals, Key.Plus, Key.NumPadAdd -> { UiScale.inc(); true }
                    Key.Minus, Key.NumPadSubtract -> { UiScale.dec(); true }
                    Key.Zero, Key.NumPad0 -> { UiScale.reset(); true }
                    else -> false
                } else false
            },
        ) {
            // Segunda abertura do app traz esta janela para a frente
            androidx.compose.runtime.LaunchedEffect(Unit) {
                Reopen.onShow = {
                    javax.swing.SwingUtilities.invokeLater {
                        if (window.extendedState and java.awt.Frame.ICONIFIED != 0) window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                        window.isVisible = true; window.toFront(); window.requestFocus()
                    }
                }
            }
            RadarTheme {
                val scope = rememberCoroutineScope()
                // O contexto (e o carregador do heatmap) é criado uma vez só, não a cada redesenho.
                val ctx = remember { AppContext(hub, db, MapModel(scope)).also { it.alerts.start(); it.macro.start(); runCatching { JarvisLearner.resumeIfOn() } } }
                Terminal(ctx)
            }
        }
    }
}
