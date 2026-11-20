package com.cunhac.btcradar.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Destino opcional para histórico persistente (SQLite no Windows). */
interface HistorySink {
    fun liquidation(e: LiqEvent)
    fun largeTrade(t: TradeEvent)
    fun wallEvent(w: Wall)
}

data class StatusRow(
    val venue: Venue, val channel: String, val state: ConnState, val lastMsgAgoMs: Long,
    val messages: Long, val reconnects: Int, val lastError: String?,
)

data class Sample(val tMs: Long, val price: Double?, val oiBtc: Double?)

data class TerminalState(
    val tick: Long = 0,
    val now: Long = 0,
    val price: Double? = null,
    val books: Map<Venue, BookView> = emptyMap(),
    val rpi: BookView? = null,
    val statuses: List<StatusRow> = emptyList(),
    val tickers: Map<Venue, DerivTicker> = emptyMap(),
    val flowAll: List<FlowStats> = emptyList(),
    val flowByVenue: Map<Venue, List<FlowStats>> = emptyMap(),
    val signals: List<FlowSignal> = emptyList(),
    val tradesTotal: Long = 0,
    val liqTotal: Long = 0,
    val msgsPerSec: Double = 0.0,
    val alerts: List<String> = emptyList(),
    val okxCtVal: String = "",
) {
    /** Uma corretora só é LIVE se a conexão recebe dados recentes E o livro está válido. */
    fun liveCount(): Int = Venue.entries.count { v ->
        books[v]?.valid == true && statuses.any { it.venue == v && it.state == ConnState.LIVE }
    }
}

/**
 * Estrutura central: uma conexão por rota de cada corretora, um livro por corretora,
 * um motor de fluxo, um rastreador de paredes e um radar de liquidações.
 * Os painéis apenas leem daqui; trocar de aba nunca reinicia conexões.
 */
class Hub(@Volatile var refreshMs: Long = 1_500) : FeedSink {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val books = Venue.entries.associateWith { LocalBook(it) }
    override val bybitRpi = LocalBook(Venue.BYBIT)
    private val statuses = ConcurrentHashMap<String, SourceStatus>()
    private val tickers = ConcurrentHashMap<Venue, DerivTicker>()

    val flow = FlowEngine()
    val large = LargeTrades()
    val wallThresholds = WallThresholds()
    val walls = WallTracker(wallThresholds)
    val liqThresholds = LiqThresholds()
    val liqs = LiqRadar(liqThresholds)

    @Volatile var history: HistorySink? = null
    @Volatile var logSink: ((LogLine) -> Unit)? = null
    private val logs = ArrayDeque<LogLine>()
    private val alerts = ArrayDeque<String>()
    private val samples = ArrayDeque<Sample>()
    private val lock = Any()

    private val binance = BinanceFeed(this, scope)
    private val bybit = BybitFeed(this, scope)
    private val okx = OkxFeed(this, scope)

    val state = MutableStateFlow(TerminalState())
    val stateFlow: StateFlow<TerminalState> get() = state
    private var tick = 0L
    private var lastMsgs = 0L
    private var lastMsgsAt = System.currentTimeMillis()
    val startedAt = System.currentTimeMillis()

    fun start() {
        walls.onEvent = { w -> history?.wallEvent(w) }
        log(LogLevel.INFO, null, "Iniciando conexões (Binance, Bybit, OKX)")
        binance.start(); bybit.start(); okx.start()
        scope.launch { while (isActive) { delay(1_000); sample() } }
        scope.launch { while (isActive) { delay(refreshMs); runCatching { publish() }.onFailure { log(LogLevel.ERROR, null, "publicação: ${it.message}") } } }
    }

    fun stop() {
        binance.stop(); bybit.stop(); okx.stop()
        log(LogLevel.INFO, null, "Conexões encerradas")
        scope.cancel()
    }

    // ---------------- FeedSink ----------------
    override fun book(v: Venue) = books.getValue(v)
    override fun status(v: Venue, channel: String): SourceStatus = statuses.getOrPut("${v.name}|$channel") { SourceStatus(v, channel) }

    override fun onTrade(t: TradeEvent) {
        flow.add(t)
        if (large.offer(t)) {
            history?.largeTrade(t)
            if (t.qtyBtc >= large.minBtc * 5) alert("Execução grande: %s %.2f BTC @ %.1f (%s)".format(
                if (t.aggressor == Aggressor.BUY) "COMPRA" else "VENDA", t.qtyBtc, t.price, t.venue.label))
        }
    }

    override fun onLiq(l: LiqEvent) {
        liqs.add(l)
        history?.liquidation(l)
        val c = liqThresholds.classify(l.qtyBtc)
        if (c != LiqClass.SMALL) alert("Liquidação %s: %s %.3f BTC @ %.1f (%s)".format(c.name, l.side.name, l.qtyBtc, l.price, l.venue.label))
    }

    override fun onTicker(v: Venue, update: (DerivTicker) -> DerivTicker) {
        tickers.compute(v) { _, old -> update(old ?: DerivTicker()) }
    }

    override fun log(level: LogLevel, venue: Venue?, text: String) {
        val line = LogLine(System.currentTimeMillis(), level, venue, text)
        synchronized(lock) { logs.addFirst(line); while (logs.size > 1_000) logs.removeLast() }
        logSink?.invoke(line)
    }

    private fun alert(text: String) = synchronized(lock) {
        alerts.addFirst(String.format("%tT  %s", System.currentTimeMillis(), text))
        while (alerts.size > 100) alerts.removeLast()
    }

    fun logs(n: Int = 300): List<LogLine> = synchronized(lock) { logs.take(n) }

    // ---------------- preço, OI e amostras ----------------
    fun price(): Double? {
        val mids = books.values.filter { it.valid }.mapNotNull { it.view(1).mid }
        if (mids.isNotEmpty()) return mids.average()
        val marks = tickers.values.mapNotNull { it.markPrice }
        return if (marks.isNotEmpty()) marks.average() else null
    }

    /** OI somado só quando as três corretoras reportaram (evita saltos artificiais). */
    fun totalOiBtc(): Double? {
        val ois = Venue.entries.map { tickers[it]?.oiBtc }
        return if (ois.all { it != null }) ois.sumOf { it!! } else null
    }

    private fun sample() = synchronized(lock) {
        val now = System.currentTimeMillis()
        samples.addLast(Sample(now, price(), totalOiBtc()))
        while (samples.isNotEmpty() && now - samples.first().tMs > 3_700_000) samples.removeFirst()
    }

    private fun changePct(windowMs: Long, sel: (Sample) -> Double?): Double? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val last = samples.lastOrNull()?.let(sel) ?: return null
        val first = samples.firstOrNull { now - it.tMs <= windowMs && sel(it) != null }?.let(sel) ?: return null
        if (first == 0.0) null else (last / first - 1) * 100
    }

    fun priceChangePct(windowMs: Long) = changePct(windowMs) { it.price }
    fun oiChangePct(windowMs: Long) = changePct(windowMs) { it.oiBtc }
    fun samples(windowMs: Long): List<Sample> = synchronized(lock) {
        val now = System.currentTimeMillis(); samples.filter { now - it.tMs <= windowMs }
    }

    // ---------------- publicação periódica ----------------
    private fun publish() {
        val now = System.currentTimeMillis()
        val views = books.mapValues { it.value.view() }
        val price = price()
        walls.scan(views.values.toList(), price, flow, now)

        val flowAll = flow.allWindows(null, now)
        val byVenue = Venue.entries.associateWith { flow.allWindows(it, now) }
        val fundings = tickers.values.mapNotNull { it.fundingRate }
        val funding = if (fundings.isNotEmpty()) fundings.average() else null
        val signals = flowAll.map { Signals.classify(it, priceChangePct(it.windowMs), oiChangePct(it.windowMs), funding) }

        val rows = statuses.values.sortedWith(compareBy({ it.venue.ordinal }, { it.channel })).map {
            StatusRow(it.venue, it.channel, it.effectiveState(now), if (it.lastMsgMs > 0) now - it.lastMsgMs else -1,
                it.messages, it.reconnects, it.lastError)
        }
        val msgs = statuses.values.sumOf { it.messages }
        val rate = (msgs - lastMsgs) * 1000.0 / (now - lastMsgsAt).coerceAtLeast(1)
        lastMsgs = msgs; lastMsgsAt = now

        state.value = TerminalState(
            tick = ++tick, now = now, price = price, books = views, rpi = bybitRpi.view(),
            statuses = rows, tickers = HashMap(tickers), flowAll = flowAll, flowByVenue = byVenue, signals = signals,
            tradesTotal = flow.totalTrades, liqTotal = liqs.received, msgsPerSec = rate,
            alerts = synchronized(lock) { alerts.toList() }, okxCtVal = okx.ctValSource,
        )
    }
}
