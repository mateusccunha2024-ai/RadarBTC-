package com.cunhac.btcradar.desktop

import com.cunhac.btcradar.engine.Hub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Um único hub por processo: girar a tela ou trocar de aba nunca reinicia conexões. */
object RadarCore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ctx: AppContext by lazy {
        val db = SqliteHistory()
        db.error?.let { FileLog.info("Histórico: $it") }
        val hub = Hub()
        hub.wallThresholds.small = SettingsStore.d("w.small", 1.0)
        hub.wallThresholds.medium = SettingsStore.d("w.medium", 3.0)
        hub.wallThresholds.large = SettingsStore.d("w.large", 5.0)
        hub.wallThresholds.extreme = SettingsStore.d("w.extreme", 10.0)
        hub.walls.rangePct = SettingsStore.d("w.range", 5.0)
        hub.liqThresholds.large = SettingsStore.d("lq.large", 1.0)
        hub.liqThresholds.extreme = SettingsStore.d("lq.extreme", 5.0)
        hub.large.minBtc = SettingsStore.d("lt.min", 2.0)
        hub.refreshMs = SettingsStore.i("ui.refresh", 1_500).toLong().coerceIn(500L, 3_000L)
        hub.history = db
        hub.logSink = { FileLog.write(it) }
        hub.start()
        AppContext(hub, db, MapModel(scope)).also { it.alerts.start() }
    }
    fun ensureStarted() { ctx }
}
