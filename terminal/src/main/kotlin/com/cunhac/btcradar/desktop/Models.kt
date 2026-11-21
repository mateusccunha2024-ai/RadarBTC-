package com.cunhac.btcradar.desktop

import com.cunhac.btcradar.data.etf.FarsideEtfSource
import com.cunhac.btcradar.data.exchanges.Binance
import com.cunhac.btcradar.data.exchanges.Bybit
import com.cunhac.btcradar.data.exchanges.LiquidationStream
import com.cunhac.btcradar.data.exchanges.Okx
import com.cunhac.btcradar.data.model.EtfDay
import com.cunhac.btcradar.data.model.Exchange
import com.cunhac.btcradar.data.model.Liquidation
import com.cunhac.btcradar.data.model.MarketStats
import com.cunhac.btcradar.data.model.OrderBookSnapshot
import com.cunhac.btcradar.data.model.PriceBin
import com.cunhac.btcradar.data.model.Side
import com.cunhac.btcradar.domain.LiquidityMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------- Mapa de liquidez ----------

data class MapState(
    val loading: Boolean = true,
    val error: String? = null,
    val stats: MarketStats? = null,
    val bins: List<PriceBin> = emptyList(),
    val magnets: List<LiquidityMap.Magnet> = emptyList(),
    val rangeIndex: Int = 1,
    val booksOk: List<Exchange> = emptyList(),
)

class MapModel(private val scope: CoroutineScope) {
    val ranges = listOf(0.02, 0.05, 0.10)
    val state = MutableStateFlow(MapState())
    private var levels: List<LiquidityMap.Level> = emptyList()
    private var books: List<OrderBookSnapshot> = emptyList()
    private var loop: Job? = null

    init { start() }

    fun start() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                refresh()
                delay(30_000)
            }
        }
    }

    fun setRange(i: Int) {
        state.update { it.copy(rangeIndex = i) }
        rebuild()
    }

    private suspend fun refresh() {
        try {
            coroutineScope {
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
                state.update { it.copy(loading = false, error = null, stats = st, booksOk = books.map { b -> b.exchange }) }
                rebuild()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(loading = false, error = it.error ?: "Sem conexão com as corretoras (${e.message})") }
        }
    }

    private fun rebuild() {
        val s = state.value
        val price = s.stats?.price ?: return
        val bins = LiquidityMap.buildBins(price, ranges[s.rangeIndex], levels, books)
        state.update { it.copy(bins = bins, magnets = LiquidityMap.magnets(price, bins)) }
    }
}

// ---------- Liquidações ao vivo ----------

data class LiqState(
    val recent: List<Liquidation> = emptyList(),
    val longUsd1h: Double = 0.0,
    val shortUsd1h: Double = 0.0,
    val byExchange1h: Map<Exchange, Double> = emptyMap(),
    val largest1h: Liquidation? = null,
    val minIndex: Int = 0,
)

class LiqModel(scope: CoroutineScope) {
    val minOptions = listOf(0.0, 10_000.0, 100_000.0)
    private val window = ArrayDeque<Liquidation>()
    val state = MutableStateFlow(LiqState())

    init {
        scope.launch { LiquidationStream.all().collect { add(it) } }
    }

    fun setMin(i: Int) {
        state.update { it.copy(minIndex = i) }
        publish()
    }

    private fun add(l: Liquidation) {
        window.addFirst(l)
        val cutoff = System.currentTimeMillis() - 3_600_000
        while (window.isNotEmpty() && window.last().timeMs < cutoff) window.removeAt(window.size - 1)
        publish()
    }

    private fun publish() {
        val min = minOptions[state.value.minIndex]
        state.update {
            it.copy(
                recent = window.asSequence().filter { l -> l.usd >= min }.take(300).toList(),
                longUsd1h = window.filter { l -> l.side == Side.LONG }.sumOf { l -> l.usd },
                shortUsd1h = window.filter { l -> l.side == Side.SHORT }.sumOf { l -> l.usd },
                byExchange1h = window.groupBy { l -> l.exchange }.mapValues { e -> e.value.sumOf { l -> l.usd } },
                largest1h = window.maxByOrNull { l -> l.usd },
            )
        }
    }
}

// ---------- ETFs ----------

data class EtfState(
    val loading: Boolean = true,
    val error: String? = null,
    val days: List<EtfDay> = emptyList(),
) {
    val latest: EtfDay? get() = days.firstOrNull()
    fun sum(n: Int) = days.take(n).sumOf { it.total }
}

class EtfModel(private val scope: CoroutineScope) {
    val state = MutableStateFlow(EtfState())

    init { load() }

    fun load() {
        state.value = state.value.copy(loading = true, error = null)
        scope.launch {
            state.value = runCatching { FarsideEtfSource.fetch() }.fold(
                onSuccess = { EtfState(loading = false, days = it) },
                onFailure = { EtfState(loading = false, error = "Não foi possível carregar os fluxos dos ETFs (${it.message})") },
            )
        }
    }
}
