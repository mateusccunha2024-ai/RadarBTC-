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
import java.util.concurrent.CopyOnWriteArrayList

/** Tipos de alerta que podem sair do app (Telegram / e-mail). */
enum class AlertKind(val label: String) {
    LIQ_EXTREME("Liquidação EXTREME"),
    WALL_PULLED("Parede grande saiu do livro"),
    WALL_SIGNAL("Absorção / iceberg"),
    SIGNAL_CHANGE("Mudança de sinal LONG/SHORT"),
    DIVERGENCE("Divergência preço × CVD"),
    HEAT_ZONE("Preço perto de zona do heatmap"),
}

data class MarketAlert(val kind: AlertKind, val key: String, val text: String, val timeMs: Long = System.currentTimeMillis())

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

/** Tipo de evento do painel de alertas (também usado no filtro da tela). */
enum class FeedKind(val label: String, val group: String) {
    ABSORPTION("Absorção", "Paredes"), ICEBERG("Iceberg", "Paredes"),
    LIQUIDATION("Liquidação", "Liquidações"), BIG_TRADE("Execução grande", "Execuções"),
    DIVERGENCE("Divergência", "Divergências"),
}

/**
 * Um alerta do painel, com os campos separados para a tela organizar (tipo, lado, preço, corretora, números)
 * em vez de uma frase longa que era cortada. [buy] define a cor: compra/long/alta = true, venda/short/baixa = false.
 * Eventos iguais seguidos (mesmo tipo, lado, preço e corretora em até 2 min) viram uma linha só com [count].
 */
data class FeedAlert(
    val timeMs: Long, val kind: FeedKind, val buy: Boolean?, val sideLabel: String,
    val price: Double?, val where: String?, val detail: String, val strong: Boolean = false, val count: Int = 1,
)

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
    val alerts: List<FeedAlert> = emptyList(),
    val okxCtVal: String = "",
    val divergences: List<Divergence> = emptyList(),
) {
    /** Uma corretora só é LIVE se a conexão recebe dados recentes E o livro está válido. */
    fun liveCount(): Int = CORE_VENUES.count { v ->
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
    private val books = CORE_VENUES.associateWith { LocalBook(it) }
    override val bybitRpi = LocalBook(Venue.BYBIT)
    private val statuses = ConcurrentHashMap<String, SourceStatus>()
    private val tickers = ConcurrentHashMap<Venue, DerivTicker>()

    val flow = FlowEngine()
    val large = LargeTrades()
    val wallThresholds = WallThresholds()
    val walls = WallTracker(wallThresholds)
    val liqThresholds = LiqThresholds()
    val liqs = LiqRadar(liqThresholds)
    val footprint = FootprintStore()
    /** Ouvintes de eventos relevantes (alertas externos). */
    val listeners = CopyOnWriteArrayList<(MarketAlert) -> Unit>()
    private val divergences = ArrayDeque<Divergence>()
    @Volatile private var lastSide: String? = null

    @Volatile var history: HistorySink? = null
    @Volatile var logSink: ((LogLine) -> Unit)? = null
    private val logs = ArrayDeque<LogLine>()
    private val alerts = ArrayDeque<FeedAlert>()
    private val samples = ArrayDeque<Sample>()
    private val lock = Any()

    private val binance = BinanceFeed(this, scope)
    private val bybit = BybitFeed(this, scope)
    private val okx = OkxFeed(this, scope)
    private val gate = GateFeed(this, scope)
    private val bitget = BitgetFeed(this, scope)
    private val deribit = DeribitFeed(this, scope)

    val state = MutableStateFlow(TerminalState())
    val stateFlow: StateFlow<TerminalState> get() = state
    private var tick = 0L
    private var lastMsgs = 0L
    private var lastMsgsAt = System.currentTimeMillis()
    val startedAt = System.currentTimeMillis()

    fun start() {
        walls.onEvent = { w ->
            history?.wallEvent(w)
            val gone = w.status == WallStatus.DISAPPEARED || w.status == WallStatus.POSSIBLY_EXECUTED || w.status == WallStatus.EXECUTION_CORROBORATED
            if (gone && (w.cls == WallClass.LARGE || w.cls == WallClass.EXTREME))
                emit(AlertKind.WALL_PULLED, "wall|${w.venue}|${w.side}|${w.price}", "Parede %s %s de %.2f BTC em %,.1f (%s): %s".format(PT_BR, 
                    w.cls.name, if (w.side == BookSide.BID) "de compra" else "de venda", w.peakQty, w.price, w.venue.label, wallStatusText(w.status)))
        }
        walls.onSignal = { w ->
            val sig = w.signal ?: WallSignal.ABSORPTION
            val txt = "%s em %,.1f (%s, %s): executado %.2f BTC, restante %.2f BTC".format(PT_BR, sig.label, w.price, w.venue.label,
                if (w.side == BookSide.BID) "compra" else "venda", w.executedBtc, w.currentQty)
            val bid = w.side == BookSide.BID
            alert(FeedAlert(System.currentTimeMillis(), if (sig == WallSignal.ICEBERG) FeedKind.ICEBERG else FeedKind.ABSORPTION,
                bid, if (bid) "Parede de compra" else "Parede de venda", w.price, w.venue.label,
                "executado %.2f BTC · restante %.2f BTC".format(PT_BR, w.executedBtc, w.currentQty)))
            emit(AlertKind.WALL_SIGNAL, "sig|${w.venue}|${w.price}|${sig.name}", txt)
        }
        log(LogLevel.INFO, null, "Iniciando conexões (Binance, Bybit, OKX; liquidações também de Gate, Bitget e Deribit)")
        binance.start(); bybit.start(); okx.start()
        gate.start(); bitget.start(); deribit.start()
        scope.launch { while (isActive) { delay(1_000); sample() } }
        scope.launch { while (isActive) { delay(refreshMs); runCatching { publish() }.onFailure { log(LogLevel.ERROR, null, "publicação: ${it.message}") } } }
    }

    fun stop() {
        binance.stop(); bybit.stop(); okx.stop()
        gate.stop(); bitget.stop(); deribit.stop()
        log(LogLevel.INFO, null, "Conexões encerradas")
        scope.cancel()
    }

    // ---------------- FeedSink ----------------
    override fun book(v: Venue) = books.getValue(v)
    override fun status(v: Venue, channel: String): SourceStatus = statuses.getOrPut("${v.name}|$channel") { SourceStatus(v, channel) }

    override fun onTrade(t: TradeEvent) {
        flow.add(t)
        footprint.add(t)
        if (large.offer(t)) {
            history?.largeTrade(t)
            if (t.qtyBtc >= large.minBtc * 5) {
                val buy = t.aggressor == Aggressor.BUY
                alert(FeedAlert(System.currentTimeMillis(), FeedKind.BIG_TRADE, buy, if (buy) "Compra" else "Venda", t.price, t.venue.label,
                    "%.2f BTC agressor".format(PT_BR, t.qtyBtc)))
            }
        }
    }

    override fun onLiq(l: LiqEvent) {
        liqs.add(l)
        history?.liquidation(l)
        val c = liqThresholds.classify(l.qtyBtc)
        if (c != LiqClass.SMALL) alert(FeedAlert(System.currentTimeMillis(), FeedKind.LIQUIDATION, l.side == LiqSide.LONG,
            if (l.side == LiqSide.LONG) "Long" else "Short", l.price, l.venue.label, "%.3f BTC · %s".format(PT_BR, l.qtyBtc, c.name),
            strong = c == LiqClass.EXTREME))
        if (c == LiqClass.EXTREME) emit(AlertKind.LIQ_EXTREME, "liq|${l.venue}|${l.timeMs}|${l.price}",
            "Liquidação EXTREME: %s %.3f BTC @ %,.1f (%s)".format(PT_BR, l.side.name, l.qtyBtc, l.price, l.venue.label))
    }

    override fun onTicker(v: Venue, update: (DerivTicker) -> DerivTicker) {
        tickers.compute(v) { _, old -> update(old ?: DerivTicker()) }
    }

    override fun log(level: LogLevel, venue: Venue?, text: String) {
        val line = LogLine(System.currentTimeMillis(), level, venue, text)
        synchronized(lock) { logs.addFirst(line); while (logs.size > 1_000) logs.removeLast() }
        logSink?.invoke(line)
    }

    private fun alert(a: FeedAlert) = synchronized(lock) {
        val last = alerts.firstOrNull()
        if (last != null && last.kind == a.kind && last.buy == a.buy && last.price == a.price && last.where == a.where &&
            a.timeMs - last.timeMs < 120_000) {
            // Mesmo evento repetido no mesmo nível: atualiza a linha em vez de empilhar cópias.
            alerts[0] = a.copy(count = last.count + 1, strong = a.strong || last.strong)
        } else {
            alerts.addFirst(a)
            while (alerts.size > 100) alerts.removeLast()
        }
    }

    fun emit(kind: AlertKind, key: String, text: String) {
        val a = MarketAlert(kind, key, text)
        listeners.forEach { runCatching { it(a) } }
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
        val ois = CORE_VENUES.map { tickers[it]?.oiBtc }
        return if (ois.all { it != null }) ois.sumOf { it!! } else null
    }

    private fun sample() = synchronized(lock) {
        val now = System.currentTimeMillis()
        samples.addLast(Sample(now, price(), totalOiBtc()))
        while (samples.isNotEmpty() && now - samples.first().tMs > 14_500_000) samples.removeFirst() // 4 h de preço/OI (1 por segundo)
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
        val byVenue = CORE_VENUES.associateWith { flow.allWindows(it, now) }
        val fundings = tickers.values.mapNotNull { it.fundingRate }
        val funding = if (fundings.isNotEmpty()) fundings.average() else null
        val signals = flowAll.map { Signals.classify(it, priceChangePct(it.windowMs), oiChangePct(it.windowMs), funding) }

        // Mudança de lado do sinal na janela de 5 min (ignora "sem viés").
        signals.getOrNull(5)?.let { sg ->
            val side = sg.cls.side
            if (side != "—") {
                val prev = lastSide
                lastSide = side
                if (prev != null && prev != side) emit(AlertKind.SIGNAL_CHANGE, "side|$side|${now / 60_000}",
                    "Sinal mudou para $side (5 min): ${sg.cls.label}, confiança ${sg.confidence}")
            }
        }
        if (tick % 10 == 0L) detectDivergences(now)

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
            divergences = synchronized(lock) { divergences.toList() },
        )
    }

    /** Divergências preço × CVD em 5 min, 15 min e 1 h (uma por janela a cada 5 min no máximo). */
    private fun detectDivergences(now: Long) {
        for (w in listOf(300_000L, 900_000L, 3_600_000L)) {
            val prices = samples(w).mapNotNull { s -> s.price?.let { s.tMs to it } }
            val cvd = flow.cvdSeries(w, now).map { it.first to it.second.sum() }
            val d = Analytics.divergence(prices, cvd, w, now) ?: continue
            val fresh = synchronized(lock) {
                val last = divergences.firstOrNull { it.windowMs == w }
                if (last != null && now - last.detectedMs < 300_000 && last.kind == d.kind) false
                else { divergences.addFirst(d); while (divergences.size > 60) divergences.removeLast(); true }
            }
            if (fresh) {
                val label = "${d.kind.label} (${w / 60_000} min)"
                alert(FeedAlert(now, FeedKind.DIVERGENCE, d.kind.bullish, if (d.kind.bullish) "De alta" else "De baixa", null,
                    "janela ${w / 60_000} min", "preço %,.1f → %,.1f · CVD %+.2f → %+.2f BTC".format(PT_BR, d.price1, d.price2, d.cvd1, d.cvd2)))
                emit(AlertKind.DIVERGENCE, "div|$w|${d.kind}|${now / 300_000}", "$label. ${d.text}")
            }
        }
    }
}

fun wallStatusText(s: WallStatus): String = when (s) {
    WallStatus.DISAPPEARED -> "retirada sem execução (provável cancelamento)"
    WallStatus.POSSIBLY_EXECUTED -> "preço atravessou, execução não confirmada"
    WallStatus.EXECUTION_CORROBORATED -> "execução confirmada pelos trades"
    else -> s.name
}
