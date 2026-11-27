package com.cunhac.btcradar.desktop

import com.cunhac.btcradar.engine.HeatData
import com.cunhac.btcradar.engine.HeatRange
import com.cunhac.btcradar.engine.HeatmapModel
import com.cunhac.btcradar.engine.HistoryApi
import com.cunhac.btcradar.engine.OiBtc
import com.cunhac.btcradar.engine.HEAT_VENUES
import com.cunhac.btcradar.engine.Venue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Baixa o histórico público e calcula o heatmap em segundo plano, com cache por período. */
class HeatLoader(private val scope: CoroutineScope) {
    data class St(val loading: Boolean = false, val range: HeatRange = HeatRange.H24, val data: HeatData? = null,
                  val error: String? = null, val progress: String = "")

    val state = MutableStateFlow(St(range = HeatRange.entries.getOrElse(SettingsStore.i("hm.range", 1)) { HeatRange.H24 }))
    private val cache = HashMap<HeatRange, HeatData>()
    private var job: Job? = null

    init {
        load(state.value.range)
        scope.launch { while (isActive) { delay(60_000); val r = state.value.range; if (stale(r)) load(r, force = true) } }
    }

    private fun ttl(r: HeatRange) = when { r.ms <= HeatRange.D3.ms -> 60_000L; r.ms <= HeatRange.W2.ms -> 300_000L; else -> 1_800_000L }
    private fun stale(r: HeatRange) = cache[r]?.let { System.currentTimeMillis() - it.builtAt > ttl(r) } ?: true

    fun load(r: HeatRange, force: Boolean = false) {
        SettingsStore.put("hm.range", r.ordinal)
        val cached = cache[r]
        if (!force && cached != null && !stale(r)) { job?.cancel(); state.value = St(range = r, data = cached); return }
        job?.cancel()
        state.value = St(loading = true, range = r, data = cached, progress = "baixando velas")
        job = scope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val from = now - r.ms - r.warmupMs
                val candles = HistoryApi.candles(r.stepMs, from)
                val oi = HashMap<Venue, List<OiBtc>>()
                val cov = HashMap<Venue, String>()
                for (v in HEAT_VENUES) {
                    state.update { it.copy(progress = "open interest ${v.label}") }
                    val res = runCatching {
                        when (v) {
                            Venue.BINANCE -> HistoryApi.oiBinance(r.stepMs, from)
                            Venue.BYBIT -> HistoryApi.oiBybit(r.stepMs, from)
                            Venue.OKX -> HistoryApi.oiOkx(r.stepMs, from)
                            Venue.GATE -> HistoryApi.oiGate(r.stepMs, from)
                            Venue.HTX -> HistoryApi.oiHtx(r.stepMs, from)
                            Venue.KRAKEN -> HistoryApi.oiKraken(r.stepMs, from)
                            else -> emptyList()
                        }
                    }
                    res.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    oi[v] = res.getOrDefault(emptyList())
                    cov[v] = HistoryApi.coverage(v, oi[v]!!, now - r.ms, res.exceptionOrNull()?.message)
                }
                state.update { it.copy(progress = "calculando") }
                val d = HeatmapModel.build(r, candles, oi.filterValues { it.size >= 2 }, cov, now)
                    ?: error("histórico de preço insuficiente para ${r.label}")
                cache[r] = d
                if (state.value.range == r) state.value = St(range = r, data = d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (state.value.range == r) state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
