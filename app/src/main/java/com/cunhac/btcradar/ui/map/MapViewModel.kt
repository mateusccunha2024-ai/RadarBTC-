package com.cunhac.btcradar.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cunhac.btcradar.data.exchanges.Binance
import com.cunhac.btcradar.data.exchanges.Bybit
import com.cunhac.btcradar.data.exchanges.Okx
import com.cunhac.btcradar.data.model.Exchange
import com.cunhac.btcradar.data.model.MarketStats
import com.cunhac.btcradar.data.model.OrderBookSnapshot
import com.cunhac.btcradar.data.model.PriceBin
import com.cunhac.btcradar.domain.LiquidityMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MapState(
    val loading: Boolean = true,
    val error: String? = null,
    val stats: MarketStats? = null,
    val bins: List<PriceBin> = emptyList(),
    val magnets: List<LiquidityMap.Magnet> = emptyList(),
    val rangeIndex: Int = 1,
    val booksOk: List<Exchange> = emptyList(),
    val updatedAt: Long = 0,
)

class MapViewModel : ViewModel() {
    val ranges = listOf(0.02, 0.05, 0.10)

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state

    // guardamos os dados brutos para recalcular na hora quando o usuário troca o intervalo
    private var levels: List<LiquidityMap.Level> = emptyList()
    private var books: List<OrderBookSnapshot> = emptyList()
    private var loop: Job? = null

    init { start() }

    fun start() {
        loop?.cancel()
        loop = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(30_000)
            }
        }
    }

    fun setRange(i: Int) {
        _state.update { it.copy(rangeIndex = i) }
        rebuild()
    }

    private suspend fun refresh() {
        try {
            coroutineScope {
                // 15m × 500 ≈ 5 dias de histórico de posições
                val candles = async { Binance.klines("15m", 500) }
                val oi = async { Binance.openInterestHist("15m", 500) }
                val stats = async { Binance.stats() }
                val bookJobs = listOf(
                    async { runCatching { Binance.orderBook() }.getOrNull() },
                    async { runCatching { Bybit.orderBook() }.getOrNull() },
                    async { runCatching { Okx.orderBook() }.getOrNull() },
                )
                levels = LiquidityMap.estimateLevels(candles.await(), oi.await())
                books = bookJobs.mapNotNull { it.await() }
                val st = stats.await()
                _state.update {
                    it.copy(
                        loading = false, error = null, stats = st,
                        booksOk = books.map { b -> b.exchange }, updatedAt = System.currentTimeMillis(),
                    )
                }
                rebuild()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = it.error ?: "Sem conexão com as corretoras (${e.message})") }
        }
    }

    private fun rebuild() {
        val s = _state.value
        val price = s.stats?.price ?: return
        val bins = LiquidityMap.buildBins(price, ranges[s.rangeIndex], levels, books)
        _state.update { it.copy(bins = bins, magnets = LiquidityMap.magnets(price, bins)) }
    }
}
