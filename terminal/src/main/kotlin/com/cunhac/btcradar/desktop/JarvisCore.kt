package com.cunhac.btcradar.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cunhac.btcradar.engine.BookSide
import com.cunhac.btcradar.engine.CORE_VENUES
import com.cunhac.btcradar.engine.LiqSide
import com.cunhac.btcradar.engine.TradeMarket
import com.cunhac.btcradar.engine.TraderClock
import com.cunhac.btcradar.engine.TraderData
import com.cunhac.btcradar.engine.TraderSkill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

// =====================================================================================
// Configurações do assistente (só neste aparelho)
// =====================================================================================

object JarvisSettings {
    /** "off", "llm-0.5b" ou "llm-1.5b". */
    var llm by mutableStateOf(SettingsStore.s("jv.llm", if (Platform.isMobile) "llm-0.5b" else "llm-1.5b")); private set
    var voiceOn by mutableStateOf(SettingsStore.b("jv.voiceOn", true)); private set
    var voiceId by mutableStateOf(SettingsStore.i("jv.voice", -1)); private set
    var speed by mutableStateOf(SettingsStore.d("jv.speed", 1.0).toFloat()); private set
    var mic by mutableStateOf(SettingsStore.s("jv.mic", if (Platform.isMobile) "mic-tiny" else "mic-base")); private set
    var watchlist by mutableStateOf(SettingsStore.s("jv.watch", "SPY,QQQ,TSLA,NVDA,AAPL,AMD,META,MSFT,AMZN,GOOGL,COIN,MSTR,PLTR")); private set
    var rules by mutableStateOf(loadRules()); private set

    fun useLlm(id: String) { llm = id; SettingsStore.put("jv.llm", id) }
    fun useVoiceOn(v: Boolean) { voiceOn = v; SettingsStore.put("jv.voiceOn", v) }
    fun useVoice(sid: Int) { voiceId = sid; SettingsStore.put("jv.voice", sid) }
    fun useSpeed(v: Float) { speed = v; SettingsStore.put("jv.speed", v.toDouble()) }
    fun useMic(id: String) { mic = id; SettingsStore.put("jv.mic", id) }
    fun useWatchlist(v: String) { watchlist = v; SettingsStore.put("jv.watch", v) }

    val voice: JarvisVoices.Voice get() = JarvisVoices.byId(voiceId) ?: JarvisVoices.default(I18n.lang.code)
    val llmItem: JarvisModels.Item? get() = JarvisModels.ALL.firstOrNull { it.id == llm && it.kind == JarvisModels.Kind.LLM }
    val micItem: JarvisModels.Item get() = if (mic == "mic-tiny") JarvisModels.MIC_TINY else JarvisModels.MIC_BASE
    fun symbols(): List<String> = watchlist.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.matches(Regex("[A-Z.\\-]{1,6}")) }.distinct().take(25)

    private fun loadRules() = TraderSkill.Rules(
        budget = SettingsStore.d("jv.r.budget", 5_000.0), cashBufferPct = SettingsStore.d("jv.r.buffer", 10.0),
        maxPositionPct = SettingsStore.d("jv.r.maxpos", 75.0), maxOrderUsd = SettingsStore.d("jv.r.maxorder", 4_000.0),
        maxLossPerTrade = SettingsStore.d("jv.r.maxloss", 50.0), maxDailyLoss = SettingsStore.d("jv.r.daily", 150.0),
        maxTradesPerDay = SettingsStore.i("jv.r.trades", 3), minRewardRisk = SettingsStore.d("jv.r.minrr", 1.0),
    )
    fun setRule(key: String, v: Double) { SettingsStore.put("jv.r.$key", if (key == "trades") v.toInt() else v); rules = loadRules() }
}

/** Diário de paper trades do dia (para os limites diários do Risk Manager). Nunca é uma ordem real. */
object PaperJournal {
    private fun key(d: LocalDate) = "jv.paper.$d"
    fun today(m: TradeMarket): Pair<Int, Double> {
        val d = Instant.now().atZone(m.zone).toLocalDate()
        val p = SettingsStore.s(key(d), "0|0").split('|')
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toDoubleOrNull() ?: 0.0)
    }
    fun record(m: TradeMarket, maxLoss: Double) {
        val (n, r) = today(m)
        SettingsStore.put(key(Instant.now().atZone(m.zone).toLocalDate()), "${n + 1}|${r + maxLoss}")
    }
}

// =====================================================================================
// Dados do app em frases curtas ("fatos"): a IA só pode usar o que está aqui
// =====================================================================================

data class Fact(val text: String, val source: String)

enum class Topic { PRICE, BOOK, WALLS, FLOW, LIQ, DERIV, ETF, MACRO, NEWS, CALENDAR, HOURS, STATUS, HELP, SUMMARY, TRADE, SCAN }

object JarvisFacts {
    private val KW: Map<Topic, List<String>> = mapOf(
        Topic.SCAN to listOf("scanner", "scan", "escane", "setups", "rompendo", "breaking out", "watchlist", "lista de acoes", "quais acoes"),
        Topic.TRADE to listOf("trade", "operacao", "operar", "entrada", "entrar", "plano", "setup", "time machine", "maquina do tempo", "simul",
            "odds", "chance", "probabil", "backtest", "rompimento", "breakout", "orb", "comprar", "compra ", "buy", "alvo", "target",
            "posicao", "position", "quantas acoes", "shares", "how many", "risk check", "boleta", "day trade"),
        Topic.PRICE to listOf("preco", "price", "cotacao", "quanto esta", "quanto ta", "how much", "valor", "precio", "prix", "kurs", "цена", "价格", "価格", "가격"),
        Topic.BOOK to listOf("livro", "book", "ofertas", "spread", "profundidade", "depth", "bid", "ask", "orderbook"),
        Topic.WALLS to listOf("parede", "wall", "muro", "iceberg", "absorc", "absorption", "suporte", "resistencia", "support", "resistance"),
        Topic.FLOW to listOf("fluxo", "flow", "cvd", "delta", "agressor", "comprador", "vendedor", "buyer", "seller", "pressao", "pressure", "sinal", "signal", "divergen"),
        Topic.LIQ to listOf("liquidac", "liquidat", "ima", "magnet", "zona", "heatmap", "liquida"),
        Topic.DERIV to listOf("funding", "financiamento", "open interest", "interesse aberto", "contratos", " oi"),
        Topic.ETF to listOf("etf", "blackrock", "ibit", "fbtc", "fidelity"),
        Topic.MACRO to listOf("macro", "fed", "juro", "rate", "inflac", "inflation", "cpi", "dolar", "dollar", "dxy", "treasury", "yield", "titulo",
            "fomc", "powell", "recess", "emprego", "payroll", "medo", "fear", "greed", "ganancia", "sofr", "liquidez", "stablecoin", "ouro", "gold", "s&p", "nasdaq", "vix"),
        Topic.NEWS to listOf("noticia", "news", "manchete", "headline", "aconteceu", "happened", "por que", "porque", "why", "noticias"),
        Topic.CALENDAR to listOf("calendario", "agenda", "evento", "event", "semana", "payroll", "cpi hoje"),
        Topic.HOURS to listOf("bolsa abre", "abre a bolsa", "abre o mercado", "mercado abre", "abre que horas", "esta aberta", "esta aberto", "is the market open", "market open", "horario", "fechad"),
        Topic.STATUS to listOf("status", "conexao", "conectad", "corretoras", "funcionando", "ao vivo", "online"),
        Topic.HELP to listOf("ajuda", "help", "o que voce faz", "what can you", "como usar", "comandos"),
        Topic.SUMMARY to listOf("resumo", "summary", "como esta", "como ta", "how is", "visao geral", "overview", "situacao", "panorama", "mercado hoje", "o que acha"),
    )

    /** "Quanto está", "valor"… só contam como preço quando a pergunta não cita outro assunto ("quanto está o funding?"). */
    private val GENERIC_PRICE = setOf("quanto esta", "quanto ta", "how much", "valor")

    fun topics(q: String): Set<Topic> {
        val f = " " + JarvisText.fold(q) + " "
        val found = KW.filter { (_, ks) -> ks.any { it in f } }.keys
        val specific = found - setOf(Topic.PRICE, Topic.SUMMARY, Topic.HELP)
        if (Topic.PRICE in found && specific.isNotEmpty() && KW[Topic.PRICE]!!.none { it !in GENERIC_PRICE && it in f }) return found - Topic.PRICE
        return found
    }

    private val NAMES = mapOf(
        "tesla" to "TSLA", "nvidia" to "NVDA", "apple" to "AAPL", "microsoft" to "MSFT", "amazon" to "AMZN", "google" to "GOOGL",
        "alphabet" to "GOOGL", "meta" to "META", "facebook" to "META", "coinbase" to "COIN", "microstrategy" to "MSTR", "strategy" to "MSTR",
        "netflix" to "NFLX", "palantir" to "PLTR", "amd" to "AMD", "intel" to "INTC", "s&p" to "SPY", "nasdaq" to "QQQ",
        "bitcoin" to "BTC", "btc" to "BTC",
    )
    private val NOT_TICKERS = setOf("ETF", "ETFS", "CVD", "OI", "FED", "CPI", "EUA", "USA", "USD", "BRL", "OK", "I", "A", "E", "O", "UTC", "NY",
        "ORB", "IA", "AI", "RSI", "PCE", "GDP", "PIB", "FOMC", "DXY", "VIX", "SOFR", "TGA", "RRP", "HOJE", "BOM", "DIA", "QUAL", "COMO", "ME", "DE", "DO", "DA")

    /** Ticker citado na pergunta (TSLA, $NVDA, "Tesla"); null = nenhum. */
    fun symbol(q: String): String? {
        Regex("\\$([A-Za-z]{1,5})").find(q)?.let { return it.groupValues[1].uppercase() }
        val f = JarvisText.fold(q)
        NAMES.entries.firstOrNull { (k, _) -> Regex("(^|[^a-z])${Regex.escape(k)}([^a-z]|$)").containsMatchIn(f) }?.let { return it.value }
        return Regex("\\b[A-Z]{2,5}\\b").findAll(q).map { it.value }.firstOrNull { it !in NOT_TICKERS }
    }

    fun collect(ctx: AppContext, topics: Set<Topic>): List<Fact> {
        val out = ArrayList<Fact>()
        val t = if (topics.isEmpty() || Topic.SUMMARY in topics) topics + setOf(Topic.PRICE, Topic.FLOW, Topic.LIQ, Topic.MACRO) else topics
        val s = ctx.hub.stateFlow.value
        val hub = ctx.hub
        val src = "Radar BTC (Binance, Bybit, OKX)"
        if (Topic.PRICE in t) {
            s.price?.let { out += Fact("Preço do BTC agora: US$ ${px(it)}", src) }
            listOf(300_000L to "5 min", 3_600_000L to "1 h", 14_400_000L to "4 h").forEach { (w, l) ->
                hub.priceChangePct(w)?.let { out += Fact("Variação do BTC em $l: ${pct(it)}", src) }
            }
        }
        if (Topic.DERIV in t || Topic.PRICE in t) {
            hub.totalOiBtc()?.let { out += Fact("Open interest somado (3 corretoras): ${btc(it, 0)}", src) }
            hub.oiChangePct(3_600_000L)?.let { out += Fact("Variação do open interest em 1 h: ${pct(it)}", src) }
            s.tickers.forEach { (v, tk) -> tk.fundingRate?.let { out += Fact("Funding ${v.label}: ${String.format(BR, "%+.4f%%", it * 100)}", v.label) } }
        }
        if (Topic.BOOK in t) {
            CORE_VENUES.forEach { v ->
                val b = s.books[v] ?: return@forEach
                if (!b.valid) return@forEach
                val bid = b.bids.firstOrNull()?.price; val ask = b.asks.firstOrNull()?.price
                if (bid != null && ask != null) out += Fact("${v.label}: melhor compra ${px(bid)}, melhor venda ${px(ask)}, spread ${num(ask - bid, 1)}", v.label)
                val mid = s.price
                if (mid != null) {
                    val bd = b.bids.filter { it.price >= mid * 0.99 }.sumOf { it.qtyBtc }; val ad = b.asks.filter { it.price <= mid * 1.01 }.sumOf { it.qtyBtc }
                    out += Fact("${v.label}: profundidade até 1% → compras ${btc(bd, 1)}, vendas ${btc(ad, 1)}", v.label)
                }
            }
        }
        if (Topic.WALLS in t || Topic.BOOK in t) {
            val mid = s.price
            listOf(BookSide.BID to "compra (suporte)", BookSide.ASK to "venda (resistência)").forEach { (side, l) ->
                hub.walls.top(side, null, 3).forEach { w ->
                    out += Fact("Parede de $l: ${px(w.price)} na ${w.venue.label}, ${btc(w.currentQty, 1)}" +
                        (mid?.let { ", a ${pct(w.distancePct(it))} do preço" } ?: "") + (w.signal?.let { " · ${it.label}" } ?: ""), w.venue.label)
                }
            }
        }
        if (Topic.FLOW in t) {
            s.flowAll.filter { it.windowMs >= 60_000 }.forEach { f ->
                val win = if (f.windowMs >= 60_000) "${f.windowMs / 60_000} min" else "${f.windowMs / 1000} s"
                out += Fact("Fluxo agressor $win: delta ${String.format(BR, "%+,.1f", f.deltaBtc)} BTC (compra ${btc(f.buyBtc, 1)}, venda ${btc(f.sellBtc, 1)})", src)
            }
            s.signals.firstOrNull()?.let { out += Fact("Leitura do fluxo: ${it.cls.label} (confiança ${it.confidence}). ${it.interpretation}", src) }
            s.divergences.lastOrNull()?.let { out += Fact("Divergência preço × CVD: ${it.text}", src) }
        }
        if (Topic.LIQ in t) {
            val now = System.currentTimeMillis()
            val recent = hub.liqs.recent(2000).filter { now - it.timeMs <= 3_600_000L }
            if (recent.isNotEmpty()) {
                val longs = recent.filter { it.side == LiqSide.LONG }.sumOf { it.qtyBtc * it.price }
                val shorts = recent.filter { it.side == LiqSide.SHORT }.sumOf { it.qtyBtc * it.price }
                out += Fact("Liquidações reais na última hora: longs ${usdM(longs)}, shorts ${usdM(shorts)}", "Binance, Bybit, OKX e outras")
            }
            ctx.map.state.value.magnets.forEach { m ->
                out += Fact("Zona ímã estimada ${if (m.above) "acima" else "abaixo"}: ${px(m.bin.low)}–${px(m.bin.high)} (${pct(m.distancePct)}), ${usdM(m.bin.liqUsd)} estimados", "Mapa de liquidez (estimativa)")
            }
        }
        if (Topic.ETF in t) {
            val e = ctx.etf.state.value
            e.latest?.let { d -> out += Fact("ETFs spot de BTC em ${d.date}: fluxo líquido ${String.format(BR, "%+,.1f", d.total)} mi US$", "Farside") }
            if (e.days.size >= 5) out += Fact("ETFs, soma de 5 dias: ${String.format(BR, "%+,.1f", e.sum(5))} mi US$", "Farside")
        }
        if (Topic.MACRO in t || Topic.CALENDAR in t || Topic.NEWS in t) {
            val m = ctx.macro.state.value
            if (Topic.MACRO in t) {
                m.score?.let { sc -> out += Fact("Score macro do BTC: ${sc.score ?: "—"} (${sc.label}), confiança ${sc.confidence}%", "Radar macro do app") }
                m.fed?.let { out += Fact("Juros do Fed: meta ${num(it.targetLow)}–${num(it.targetHigh)}%, efetiva ${num(it.effr)}% (${it.date})", "Fed / FRED") }
                m.curve?.let { c -> c.last10y?.let { out += Fact("Treasury 10 anos: ${num(it)}%" + (c.last2y?.let { y -> ", 2 anos ${num(y)}%" } ?: ""), "Tesouro dos EUA") } }
                listOf("DXY", "SPX", "NDX", "VIX", "GOLD").forEach { k -> m.markets[k]?.let { ms -> ms.last?.let { out += Fact("${ms.label}: ${num(it)} (${pct(ms.dayPct)} no dia)", "Yahoo Finance") } } }
                m.fearGreed?.let { out += Fact("Índice medo e ganância: ${it.value} (${it.label})", "alternative.me") }
                m.fedOdds.firstOrNull()?.let { o -> out += Fact("Kalshi, ${o.event}: " + o.outcomes.take(3).joinToString("; ") { (k, p) -> "$k ${num(p * 100, 0)}%" }, "Kalshi") }
                m.real10y.firstOrNull()?.let { out += Fact("Juro real 10 anos (TIPS): ${num(it.second)}%", "Tesouro dos EUA") }
                m.stablePct(30)?.let { out += Fact("Estoque de stablecoins em 30 dias: ${pct(it)}", "DefiLlama") }
            }
            if (Topic.CALENDAR in t || Topic.MACRO in t) {
                val now = System.currentTimeMillis()
                m.calendar.filter { it.timeMs >= now - 3_600_000L && it.high }.take(4).forEach { e ->
                    out += Fact("Agenda: ${e.title} (${e.country}) em ${hhmmDate(e.timeMs)}" + (e.forecast.takeIf { it.isNotBlank() }?.let { ", previsão $it" } ?: "") +
                        (e.actual?.let { ", saiu $it" } ?: ""), "Calendário econômico")
                }
            }
            if (Topic.NEWS in t || Topic.SUMMARY in t) m.news.take(4).forEach { n -> out += Fact("Notícia (${n.source}): ${n.title}", n.source) }
        }
        if (Topic.HOURS in t) {
            val now = System.currentTimeMillis()
            out += Fact(if (TraderClock.usOpen(now)) "A bolsa dos EUA está aberta agora (9h30–16h em Nova York)."
                else "A bolsa dos EUA está fechada; abre em ${hhmmDate(TraderClock.nextUsOpen(now))} (9h30 em Nova York).", "NYSE")
            out += Fact("O BTC negocia 24 horas, todos os dias.", "Radar BTC")
        }
        if (Topic.STATUS in t) out += Fact("Livros ao vivo: ${s.liveCount()} de 3; ${String.format(BR, "%,.0f", s.msgsPerSec)} mensagens por segundo", src)
        return out.take(48)
    }

    private fun hhmmDate(ms: Long) = java.text.SimpleDateFormat("dd/MM HH:mm", BR).format(java.util.Date(ms))
}

// =====================================================================================
// O fluxo do vídeo: scanner → notícias → entrada → saída → Time Machine → tamanho → risco → boleta
// =====================================================================================

data class TradeCard(
    val symbol: String, val market: TradeMarket, val name: String?, val last: Double?, val entry: TraderSkill.Entry,
    val plan: TraderSkill.Plan?, val hypothetical: Boolean, val refusal: String?, val mc: TraderSkill.MonteCarlo?,
    val history: TraderSkill.History?, val sizing: TraderSkill.Sizing?, val risk: TraderSkill.RiskVerdict?, val ticket: String?,
    val news: List<Pair<String, String>>, val marketNote: String, val sessions: Int, val dataSource: String, val builtMs: Long,
    /** O que já aconteceu depois da entrada de hoje (stop ou alvo já atingidos encerram o setup). */
    val progress: TraderSkill.Progress? = null,
)

data class ScanRow(val symbol: String, val last: Double?, val status: String, val detail: String, val setups: List<TraderSkill.Setup>)

object JarvisTrade {
    /** Plano completo para um símbolo (BTC ou ação dos EUA). */
    suspend fun run(ctx: AppContext, symbolIn: String, stopPct: Double? = null, targetR: Double? = null): TradeCard = withContext(Dispatchers.Default) {
        val sym = symbolIn.uppercase()
        val m = if (sym == "BTC") TradeMarket.BTC else TradeMarket.US_STOCK
        val now = System.currentTimeMillis()
        val (bars, quote) = if (m == TradeMarket.BTC) TraderData.btc5m(30) to TraderData.Quote("BTC", ctx.hub.price(), null, now, "Bitcoin")
            else TraderData.yahoo5m(sym)
        val sessions = TraderSkill.sessions(bars, m)
        val todayDate = Instant.ofEpochMilli(now).atZone(m.zone).toLocalDate()
        val today = sessions.firstOrNull { it.date == todayDate }
        val full = sessions.filter { it.full && it.date < todayDate }.takeLast(59)
        val e = TraderSkill.entry(today, m, 15, now)
        val rules = JarvisSettings.rules
        val last = e.last ?: quote.price
        val note = when {
            m == TradeMarket.BTC -> "BTC: cada dia UTC é uma sessão (abertura às 0h UTC, zera às 23h55 UTC)."
            TraderClock.usOpen(now) -> "Bolsa aberta (dados do Yahoo podem atrasar até 15 min)."
            else -> "Bolsa fechada; próximo pregão abre ${java.text.SimpleDateFormat("dd/MM HH:mm", BR).format(java.util.Date(TraderClock.nextUsOpen(now)))}."
        }
        // Entrada real (rompeu) ou hipotética (ainda esperando: "se romper a máxima agora").
        var hyp = false
        val eUse = when (e.status) {
            TraderSkill.EntryStatus.TRIGGERED -> e
            TraderSkill.EntryStatus.WAITING -> {
                hyp = true
                val k = (today?.slots?.indexOfLast { it != null && it.t + 300_000L <= now } ?: 0).coerceAtLeast(2)
                e.copy(price = TraderSkill.tickRound(e.orHigh!! + m.tick, m.tick), slot = k, timeMs = null)
            }
            else -> null
        }
        val rule = if (stopPct != null && stopPct > 0) TraderSkill.StopRule.PCT else TraderSkill.StopRule.OR_MID
        val tr = targetR?.takeIf { it in 0.5..10.0 } ?: 2.0
        val exit = eUse?.let { TraderSkill.exitPlan(sym, m, todayDate, it, 15, rule, stopPct ?: 1.0, tr) }
        val plan = (exit as? TraderSkill.ExitResult.Ok)?.plan
        val refusal = (exit as? TraderSkill.ExitResult.Refused)?.reason
        // Entrada real de hoje: o trade pode já ter acabado (bateu no stop ou no alvo). Aí não há entrada agora.
        val progress = if (plan != null && !hyp) TraderSkill.progress(today, plan, now, last) else null
        val done = progress != null && progress.status != TraderSkill.ProgressStatus.OPEN
        val pool = if (full.size >= 10) TraderSkill.pool(full, m) else null
        val mc = if (plan != null && pool != null && !done) TraderSkill.simulate(pool, m, plan.entry, plan.stop, plan.target, plan.entrySlot, m.lastSlot) else null
        val hist = if (full.isNotEmpty()) TraderSkill.history(full, m, 15, rule, stopPct ?: 1.0, tr, m.lastSlot) else null
        val sizing = plan?.let { TraderSkill.size(it, rules, if (hyp) null else last) }
        val (nToday, rToday) = PaperJournal.today(m)
        val risk = if (plan != null && sizing != null) TraderSkill.risk(plan, sizing, rules, rToday, nToday, now, if (hyp) null else last, progress) else null
        val ticket = if (plan != null && sizing != null && sizing.refusal == null && risk?.pass == true) TraderSkill.ticket(plan, sizing) else null
        val news = runCatching { TraderData.headlines(sym, 5).map { it.title to it.source } }.getOrDefault(emptyList())
        TradeCard(sym, m, quote.name, last, e, plan, hyp, refusal, mc, hist, sizing, risk, ticket, news, note, full.size,
            if (m == TradeMarket.BTC) "Binance (candles de 5 min, 30 dias)" else "Yahoo Finance (candles de 5 min, 60 dias)", now, progress)
            .also { runCatching { ScoreBook.record(it) } }
    }

    /** Scanner da lista: rompimento da abertura de 15 min hoje + setups do diário. */
    suspend fun scan(symbols: List<String>): List<ScanRow> = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val m = TradeMarket.US_STOCK
        val todayDate = Instant.ofEpochMilli(now).atZone(m.zone).toLocalDate()
        TraderData.each(symbols) { s ->
            val (bars, q) = TraderData.yahoo5m(s, "1d")
            val today = TraderSkill.sessions(bars, m).firstOrNull { it.date == todayDate }
            val e = TraderSkill.entry(today, m, 15, now)
            val daily = runCatching { TraderData.yahooDaily(s) }.getOrDefault(emptyList())
            Triple(e, q, TraderSkill.dailySetups(daily))
        }.map { (s, r) ->
            r.fold({ (e, q, set) ->
                val (st, det) = when (e.status) {
                    TraderSkill.EntryStatus.TRIGGERED -> "ROMPEU" to "fechou acima de ${num(e.orHigh!!)} às ${hm(e.timeMs!!)} NY"
                    TraderSkill.EntryStatus.WAITING -> "AGUARDANDO" to "a ${pct(e.distancePct)} da máxima da abertura (${num(e.orHigh!!)})"
                    TraderSkill.EntryStatus.EXPIRED -> "SEM GATILHO" to "não rompeu dentro da janela de entradas"
                    TraderSkill.EntryStatus.BUILDING_RANGE -> "ABERTURA" to "formando a abertura de 15 min"
                    TraderSkill.EntryStatus.NO_DATA -> "FECHADO" to "sem pregão hoje"
                }
                ScanRow(s, e.last ?: q.price, st, det, set)
            }, { ScanRow(s, null, "ERRO", it.message?.take(60) ?: "sem dados", emptyList()) })
        }.sortedWith(compareBy<ScanRow>({ when (it.status) { "ROMPEU" -> 0; "AGUARDANDO" -> 1; else -> 2 } }, { it.symbol }))
    }

    private fun hm(ms: Long) = TraderClock.ny(ms).let { "%02d:%02d".format(it.hour, it.minute) }

    // ---------------------------------------------------------------- Price Alerts (toca uma vez cada)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watches = HashMap<String, Job>()

    /** Vigia stop, alvo e "zerar até" do plano e avisa (notificação do sistema) uma vez cada. */
    fun watch(ctx: AppContext, p: TraderSkill.Plan) {
        watches.remove(p.symbol)?.cancel()
        watches[p.symbol] = scope.launch {
            var stopHit = false; var targetHit = false; var flatHit = false
            while (isActive && !(flatHit || stopHit || targetHit)) {
                val price = runCatching { if (p.market == TradeMarket.BTC) ctx.hub.price() else TraderData.yahoo5m(p.symbol, "1d").second.price }.getOrNull()
                val z = Instant.now().atZone(p.market.zone)
                if (price != null) {
                    if (price <= p.stop) { stopHit = true; Platform.notify("${p.symbol}: ${L("stop atingido")}", "${px(price)} ≤ ${px(p.stop)}") }
                    else if (price >= p.target) { targetHit = true; Platform.notify("${p.symbol}: ${L("alvo atingido")}", "${px(price)} ≥ ${px(p.target)}") }
                }
                if (z.hour * 60 + z.minute >= p.market.flatByMin || z.toLocalDate() != p.date) {
                    flatHit = true; Platform.notify("${p.symbol}: ${L("hora de zerar")}", L("O plano termina agora. Feche a posição de teste."))
                }
                delay(if (p.market == TradeMarket.BTC) 10_000L else 30_000L)
            }
        }
    }
    fun watching(symbol: String) = watches[symbol]?.isActive == true
}

// =====================================================================================
// O assistente: entende a pergunta, junta os dados do app e responde (IA local ou regras)
// =====================================================================================

data class JarvisMsg(
    val user: Boolean, val text: String, val facts: List<Fact> = emptyList(), val card: TradeCard? = null,
    val scan: List<ScanRow>? = null, val engine: String = "", val timeMs: Long = System.currentTimeMillis(),
)

enum class BrainState { OFF, NOT_INSTALLED, IDLE, LOADING, READY, ERROR, UNSUPPORTED }

object Jarvis {
    val messages = mutableStateListOf<JarvisMsg>()
    var busy by mutableStateOf(false); private set
    var listening by mutableStateOf(false); private set
    var speaking by mutableStateOf(false); private set
    var brain by mutableStateOf(BrainState.OFF); private set
    var brainError by mutableStateOf<String?>(null); private set
    var voiceReady by mutableStateOf(false); private set
    var micReady by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null)
    private var loadedLlm: String? = null
    private var loadedVoice: String? = null
    private var loadedMic: String? = null

    /** Nenhum erro dentro do assistente pode fechar o app: vira aviso no painel e linha no log. */
    private val guard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        runCatching { FileLog.info("jarvis erro: $e") }
        notice = L("O Jarvis teve um erro e se recuperou") + ": " + (e.message ?: e.javaClass.simpleName).take(120)
        busy = false; listening = false; speaking = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + guard)
    private val speakScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guard)

    /** Carrega (ou troca) os modelos que já estão no aparelho. Chamado ao abrir o painel e depois de baixar. */
    /**
     * Ao abrir o painel: só confere o que está baixado (nada nativo é carregado aqui, então abrir o Jarvis nunca fecha o app).
     * A IA, a voz e o microfone carregam na primeira vez que são usados, protegidos por [guarded].
     */
    fun prepare() = scope.launch {
        val item = JarvisSettings.llmItem
        if (item == null) { runCatching { JarvisNative.unloadLlm() }; loadedLlm = null }
        brain = when {
            item == null -> BrainState.OFF
            !JarvisNative.llmSupported -> BrainState.UNSUPPORTED
            !JarvisModels.installed(item) -> BrainState.NOT_INSTALLED
            crashed("llm-${item.id}") -> {
                brainError = crashReason("llm-${item.id}")?.let { L("A IA local fechou neste aparelho") + ": " + it + ". " + L("Escolha a IA leve em ⚙ ou toque em Tentar de novo.") }
                    ?: L("Este modelo fechou o app neste aparelho. Escolha o modelo leve ou toque em Tentar de novo.")
                BrainState.ERROR
            }
            loadedLlm == item.id && JarvisNative.llmLoaded -> BrainState.READY
            brain == BrainState.ERROR && loadedLlm == null && brainError != null && !crashedAny() -> BrainState.ERROR
            else -> BrainState.IDLE
        }
        // Erro de outro modelo não fica na tela depois da troca (antes o erro do 7B continuava aparecendo com o 0,5B).
        if (brain != BrainState.ERROR) brainError = null
        voiceReady = JarvisModels.installed(JarvisModels.VOICE) && !crashed("voice")
        micReady = JarvisModels.installed(JarvisSettings.micItem) && !crashed("mic-${JarvisSettings.micItem.id}")
        crashedList = listCrashed()
        JarvisCrash.check()
        // Modo seguro: o app fechou com o painel aberto da última vez. Nada roda sozinho ao abrir.
        if (JarvisCrash.safeMode) return@launch
        // Uma vez: tira da memória conceitos aprendidos antes do filtro de relevância (músicas, filmes…).
        if (!SettingsStore.b("jv.cur.purge17", false)) { runCatching { JarvisCuriosity.purgeIrrelevant() }; SettingsStore.put("jv.cur.purge17", true) }
        // Aprende sozinho: sincronização com o cérebro na nuvem e pesquisa do dia (Wi-Fi, 1 vez a cada 20 h).
        // Espera o painel terminar de abrir antes de usar a rede.
        delay(4_000)
        if (!synced) { synced = true; runCatching { CloudBrain.sync() } }
        if (runCatching { JarvisResearch.due() }.getOrDefault(false)) runCatching { JarvisResearch.run() }
    }
    private var synced = false

    // ---------------------------------------------------------------- carga protegida dos modelos nativos
    /** Modelos que fecharam o app ao carregar (o app pula esses até o usuário tocar em "Tentar de novo"). */
    var crashedList by mutableStateOf(emptyList<String>()); private set
    private fun mark(key: String) = java.io.File(JarvisNative.modelsDir, "carregando-$key")
    private fun bad(key: String) = java.io.File(JarvisNative.modelsDir, "falhou-$key")
    /** Se ficou a marca "carregando" de uma vez anterior, o app fechou no meio da carga: vira "falhou". */
    private fun crashed(key: String): Boolean {
        if (mark(key).exists()) { runCatching { bad(key).writeText(mark(key).readText()); mark(key).delete() } }
        // Caiu por instrução do processador e agora há uma biblioteca compatível para tentar: libera sozinho.
        if (key.startsWith("llm-") && bad(key).exists() && runCatching { JarvisNative.hasCpuFallback() }.getOrDefault(false) &&
            crashReason(key)?.contains("processador") == true) runCatching { bad(key).delete() }
        return bad(key).exists()
    }
    /** Motivo guardado quando o processo da IA caiu (o app continuou aberto). */
    private fun crashReason(key: String): String? = runCatching { bad(key).readText() }.getOrNull()?.takeIf { it.startsWith("motivo: ") }?.removePrefix("motivo: ")?.trim()

    /**
     * A IA roda num processo separado. Se ele caiu, o app segue aberto: este modelo fica desligado até "Tentar de novo",
     * o motivo aparece no painel e a resposta sai com os dados do app.
     */
    private fun noteLlmCrash(): Boolean {
        val why = runCatching { JarvisNative.takeCrash() }.getOrNull() ?: return false
        val item = JarvisSettings.llmItem
        if (item != null) runCatching { bad("llm-${item.id}").writeText("motivo: $why") }
        loadedLlm = null
        brain = BrainState.ERROR
        brainError = L("A IA local fechou e o app continuou aberto") + ": " + why + ". " + L("Respondo com os dados do app. Escolha a IA leve em ⚙ ou toque em Tentar de novo.")
        crashedList = listCrashed()
        runCatching { FileLog.info("jarvis: IA ${item?.id} caiu: $why") }
        return true
    }

    private fun listCrashed() = JarvisNative.modelsDir.listFiles()?.filter { it.name.startsWith("falhou-") }?.map { it.name.removePrefix("falhou-") } ?: emptyList()
    private fun crashedAny() = listCrashed().isNotEmpty()
    fun retryCrashed() { JarvisNative.modelsDir.listFiles()?.filter { it.name.startsWith("falhou-") || it.name.startsWith("carregando-") }?.forEach { it.delete() }; brainError = null; prepare() }

    private fun <T> guarded(key: String, block: () -> T): T {
        runCatching { mark(key).writeText(System.currentTimeMillis().toString()) }
        try { return block() } finally { runCatching { mark(key).delete() } }
    }

    private val loadLock = Any()

    /** Carrega a IA na primeira pergunta. */
    fun ensureBrain(): Boolean = synchronized(loadLock) {
        val item = JarvisSettings.llmItem ?: return false
        // A IA pode ter caído parada (entre perguntas): mostra o motivo em vez de recarregar sem avisar.
        runCatching { JarvisNative.checkLlm() }
        if (noteLlmCrash()) return false
        if (loadedLlm == item.id && JarvisNative.llmLoaded) { brain = BrainState.READY; return true }
        if (!JarvisNative.llmSupported || !JarvisModels.installed(item) || crashed("llm-${item.id}")) return false
        // Memória livre: sem espaço, o sistema mata o app no meio da carga. Melhor avisar e responder com os dados.
        val free = runCatching { JarvisNative.freeRamMb() }.getOrDefault(-1L)
        val need = needRamMb(item)
        if (free in 0 until need) {
            brain = BrainState.ERROR
            brainError = L("Pouca memória livre para esta IA") + " (${free} MB " + L("livres") + ", ${need} MB " + L("necessários") + "). " +
                L("Feche outros apps ou escolha a IA leve em ⚙.")
            FileLog.info("jarvis: llm ${item.id} não carregou: ${free} MB livres < ${need} MB")
            return false
        }
        brain = BrainState.LOADING
        val err = guarded("llm-${item.id}") { JarvisNative.loadLlm(JarvisModels.file(item, item.files.first().second).path) }
        return if (err == null) { loadedLlm = item.id; brain = BrainState.READY; brainError = null; true }
        else { brain = BrainState.ERROR; brainError = err; noteLlmCrash(); false }
    }

    /** Memória que a IA precisa livre para carregar (o arquivo é mapeado; o contexto e a conta vão para a RAM). */
    fun needRamMb(item: JarvisModels.Item): Long = if (Platform.isMobile) (item.sizeMb * 0.75).toLong() + 300 else item.sizeMb.toLong() + 600

    /** Carrega a voz na primeira fala. */
    internal fun ensureVoice(): Boolean = synchronized(loadLock) {
        val v = JarvisSettings.voice
        if (loadedVoice == v.lang && JarvisNative.ttsLoaded) return true
        if (!JarvisModels.installed(JarvisModels.VOICE) || crashed("voice")) return false
        val d = JarvisModels.dir(JarvisModels.VOICE)
        val lex = listOf(if (v.lang == "en-gb") "lexicon-gb-en.txt" else "lexicon-us-en.txt", "lexicon-zh.txt").joinToString(",") { java.io.File(d, it).path }
        val err = guarded("voice") { JarvisNative.loadTts(d, "model.int8.onnx", v.lang, lex, java.io.File(d, "dict").path) }
        loadedVoice = if (err == null) v.lang else null
        if (err != null) notice = L("A voz não carregou") + ": " + err
        return err == null
    }

    /** Carrega o microfone no primeiro toque. */
    internal fun ensureMic(): Boolean = synchronized(loadLock) {
        val mi = JarvisSettings.micItem
        val mk = mi.id + I18n.lang.code
        if (loadedMic == mk && JarvisNative.asrLoaded) return true
        if (!JarvisModels.installed(mi) || crashed("mic-${mi.id}")) return false
        val p = if (mi == JarvisModels.MIC_TINY) "tiny" else "base"
        val err = guarded("mic-${mi.id}") { JarvisNative.loadAsr(JarvisModels.file(mi, "$p-encoder.int8.onnx"), JarvisModels.file(mi, "$p-decoder.int8.onnx"),
            JarvisModels.file(mi, "$p-tokens.txt"), whisperLang()) }
        loadedMic = if (err == null) mk else null
        if (err != null) notice = L("O microfone não carregou") + ": " + err
        return err == null
    }

    private fun whisperLang() = when (I18n.lang.code) { "zh" -> "zh"; else -> I18n.lang.code }

    fun clear() { messages.clear() }

    /** Pergunta digitada ou falada. */
    fun ask(ctx: AppContext, text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        messages += JarvisMsg(true, q)
        busy = true
        scope.launch {
            val reply = runCatching { answer(ctx, q) }.getOrElse { JarvisMsg(false, L("Não consegui buscar os dados agora") + ": " + (it.message ?: ""), engine = "erro") }
            messages += reply; busy = false
            // A voz lê só o começo (2 frases); a lista completa fica na tela.
            if (JarvisSettings.voiceOn && voiceReady) say(JarvisChat.forSpeech(reply.text))
            busy = false
        }
    }

    fun say(text: String) {
        speakScope.launch {
            JarvisNative.stopSpeaking()
            speaking = true
            try { if (ensureVoice()) JarvisNative.speak(text, JarvisSettings.voice.sid, JarvisSettings.speed) } finally { speaking = false }
        }
    }
    fun stopVoice() { JarvisNative.stopSpeaking(); speaking = false }

    /** Microfone: toque para falar, toque de novo para enviar. */
    fun toggleMic(ctx: AppContext) {
        if (listening) {
            listening = false
            scope.launch {
                val t = runCatching { JarvisNative.stopRecordingAndTranscribe() }.getOrDefault("")
                when {
                    t.isBlank() -> notice = L("Não ouvi ninguém falando. Toque no microfone, fale perto dele e toque de novo para enviar.")
                    JarvisChat.suspicious(t) -> notice = L("Parecia só ruído") + " (\"$t\"). " + L("Não enviei. Tente de novo, mais perto do microfone.")
                    else -> ask(ctx, t)
                }
            }
            return
        }
        if (!micReady) { notice = L("Baixe o modelo do microfone em ⚙ para falar com o assistente."); return }
        JarvisNative.requestMic { ok ->
            if (!ok) { notice = L("Sem permissão do microfone."); return@requestMic }
            stopVoice()
            // Carregar o Whisper leva segundos: fora da tela, senão o sistema acha que o app travou e fecha.
            scope.launch {
                if (!ensureMic()) { micReady = false; return@launch }
                val err = JarvisNative.startRecording()
                if (err == null) listening = true else notice = L(err)
            }
        }
    }

    /** Para onde a pergunta vai: dados do app, plano de trade, scanner ou ajuda. */
    data class Route(val kind: String, val topics: Set<Topic>, val symbol: String?)

    fun route(q: String): Route {
        val topics = JarvisFacts.topics(q)
        val sym = JarvisFacts.symbol(q)
        return when {
            Topic.HELP in topics && topics.size == 1 -> Route("help", topics, sym)
            Topic.SCAN in topics && !(sym != null && Topic.TRADE in topics) -> Route("scan", topics, sym)
            Topic.TRADE in topics || (sym != null && sym != "BTC" && topics.isEmpty()) -> Route("trade", topics, sym ?: "BTC")
            else -> Route("facts", topics, sym)
        }
    }

    private val STOP_RX = Regex("stop\\s*(?:de|em|of|at)?\\s*(\\d+(?:[.,]\\d+)?)\\s*%")
    private val TARGET_RX = Regex("(?:alvo|target)\\s*(?:de|em|of|at)?\\s*(\\d+(?:[.,]\\d+)?)\\s*r\\b|(\\d+(?:[.,]\\d+)?)\\s*r\\b")

    private suspend fun answer(ctx: AppContext, q: String): JarvisMsg {
        // Conversa e conceitos funcionam sem a IA: cumprimento responde cumprimento; "o que é X" explica X com o valor ao vivo.
        JarvisChat.smallTalk(q)?.let { return JarvisMsg(false, it, engine = "conversa") }
        JarvisChat.concept(q)?.let { c ->
            val live = c.topic?.let { tp -> runCatching { JarvisFacts.collect(ctx, setOf(tp)) }.getOrDefault(emptyList()) }.orEmpty()
            val (text, used) = JarvisChat.conceptAnswer(c, live)
            return JarvisMsg(false, text, used, engine = "glossário do app")
        }
        val r = route(q)
        if (r.kind == "help") return JarvisMsg(false, help(), engine = "app")
        // Scanner da lista de ações
        if (r.kind == "scan") {
            val rows = JarvisTrade.scan(JarvisSettings.symbols())
            val hits = rows.filter { it.status == "ROMPEU" }
            val near = rows.filter { it.status == "AGUARDANDO" }.take(3)
            val now = System.currentTimeMillis()
            val closedDay = rows.isNotEmpty() && rows.all { it.status == "FECHADO" || it.status == "ERRO" } && !TraderClock.tradingDay(TraderClock.ny(now).toLocalDate())
            val text = buildString {
                append(L("Scanner de ${rows.size} ações da sua lista") + ". ")
                if (closedDay) append(L("Bolsa dos EUA fechada hoje (fim de semana ou feriado); o rompimento da abertura volta no próximo pregão") + ", " +
                    java.text.SimpleDateFormat("dd/MM HH:mm", BR).format(java.util.Date(TraderClock.nextUsOpen(now))) + ". ")
                else if (hits.isEmpty()) append(L("Nenhuma rompeu a abertura de 15 min hoje") + ". ")
                else append(L("Romperam a abertura") + ": " + hits.joinToString(", ") { it.symbol } + ". ")
                if (near.isNotEmpty()) append(L("Mais perto de romper") + ": " + near.joinToString(", ") { "${it.symbol} (${it.detail.substringBefore(" da")})" } + ". ")
                val daily = rows.filter { it.setups.isNotEmpty() }.take(4)
                if (daily.isNotEmpty()) append(L("Setups no diário") + ": " + daily.joinToString(", ") { r -> r.symbol + " " + r.setups.joinToString("/") { setupLabel(it) } } + ".")
            }
            return JarvisMsg(false, text.trim(), scan = rows, engine = "app")
        }
        // Plano de trade completo (o vídeo), com stop/alvo pedidos na pergunta ("stop de 1%", "alvo 3R")
        if (r.kind == "trade") {
            val f = JarvisText.fold(q)
            val stop = STOP_RX.find(f)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            val tgt = TARGET_RX.find(f)?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(',', '.').toDoubleOrNull() }
            val card = JarvisTrade.run(ctx, r.symbol ?: "BTC", stop, tgt)
            return JarvisMsg(false, cardSummary(card), card = card, engine = "app")
        }
        val a = answerFacts(ctx, q, r.topics)
        return JarvisMsg(false, a.text, a.facts, card = a.card, engine = a.engine)
    }

    data class FactsAnswer(val text: String, val facts: List<Fact>, val engine: String, val card: TradeCard? = null)

    /**
     * Pergunta sobre dados: fatos do app + memória + habilidades + placar; com a IA pronta, ela escolhe ferramentas,
     * responde e a resposta é conferida número a número (se inventar, cai para a resposta com os dados).
     */
    /** Sempre fora da tela principal: a IA pode levar segundos para carregar e responder. */
    suspend fun answerFacts(ctx: AppContext, q: String, topics: Set<Topic>): FactsAnswer =
        withContext(Dispatchers.Default) { answerFactsOffMain(ctx, q, topics) }

    private suspend fun answerFactsOffMain(ctx: AppContext, q: String, topics: Set<Topic>): FactsAnswer {
        val facts = ArrayList(JarvisFacts.collect(ctx, topics))
        facts += JarvisMemory.asFacts(q)
        facts += runCatching { JarvisSkills.run(q) }.getOrDefault(emptyList())
        JarvisFacts.symbol(q)?.let { s -> ScoreBook.fact(s)?.let { facts += it } }
        var card: TradeCard? = null
        var ai = ensureBrain()
        if (ai) {
            val calls = runCatching { JarvisTools.parse(JarvisNative.complete(plannerPrompt(q), 60, 0.1f, listOf("<|im_end|>", "<|im_start|>"))) }.getOrDefault(emptyList())
            if (noteLlmCrash()) ai = false
            for (c in calls) {
                val (f, cd) = runCatching { JarvisTools.run(ctx, c, q) }.getOrDefault(emptyList<Fact>() to null)
                facts += f.filter { n -> facts.none { it.text == n.text } }
                if (cd != null) card = cd
            }
        }
        if (facts.isEmpty()) { JarvisSkills.noteUnknown(q); JarvisResearch.enqueue(q) }
        // Assunto perguntado primeiro; num "o que é…", o conceito aprendido (Wikipedia) vem antes de tudo.
        val ordered = JarvisChat.focus(facts, topics).let { f -> if (JarvisChat.asksConcept(q)) f.sortedBy { if (it.text.startsWith("Conceito")) 0 else 1 } else f }
        val used = ordered.take(56)
        if (ai && used.isNotEmpty()) {
            for (strict in listOf(false, true)) {
                val text = runCatching { llmAnswer(q, used, strict) }.getOrNull() ?: break
                if (text.isNotBlank() && Grounding.ungrounded(text, used.map { it.text }).isEmpty()) return FactsAnswer(text, used, "IA local", card)
            }
            if (noteLlmCrash()) return FactsAnswer(ruleAnswer(used, topics), used, "dados do app (a IA fechou; o app continuou aberto)", card)
            return FactsAnswer(ruleAnswer(used, topics), used, "dados do app (a IA citou um número fora dos dados)", card)
        }
        return FactsAnswer(ruleAnswer(used, topics), used, "dados do app", card)
    }

    private fun plannerPrompt(q: String) = "<|im_start|>system\nYou plan which tools to call before answering a question in a trading app. Tools:\n" +
        JarvisTools.HELP + "\nReply ONLY with up to 3 lines, each exactly 'tool arguments', or the single word NONE.<|im_end|>\n" +
        "<|im_start|>user\n" + q.take(400) + "<|im_end|>\n<|im_start|>assistant\n"

    /** Resumo curto de manchetes (pesquisa diária). */
    fun summarize(topic: String, headlines: List<String>): String {
        val p = "<|im_start|>system\nSummarize these headlines about \"$topic\" in 1 or 2 short sentences in ${langName()}. Use only what the headlines say; do not add numbers that are not in them.<|im_end|>\n" +
            "<|im_start|>user\n" + headlines.joinToString("\n") { "- $it" } + "<|im_end|>\n<|im_start|>assistant\n"
        return runCatching { JarvisNative.complete(p, 120, 0.2f, listOf("<|im_end|>", "<|im_start|>")).trim() }
            .getOrElse { noteLlmCrash(); throw it }
    }

    private fun langName() = when (I18n.lang.code) {
        "pt" -> "Brazilian Portuguese"; "en" -> "English"; "es" -> "Spanish"; "fr" -> "French"; "de" -> "German"; "it" -> "Italian"
        "ru" -> "Russian"; "zh" -> "Simplified Chinese"; "ja" -> "Japanese"; "ko" -> "Korean"; else -> "Brazilian Portuguese"
    }

    private fun llmAnswer(q: String, facts: List<Fact>, strict: Boolean = false): String {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        val sys = """You are JARVIS, the voice assistant inside the "Radar BTC" app (a Bitcoin order-flow terminal that also covers US stocks).
Always answer in ${langName()}. Speak like a calm, sharp butler-analyst: 2 to 5 short sentences, no markdown, no lists.
Use ONLY the FACTS below. Never invent prices, numbers, probabilities, news, dates or sources. If the facts do not answer the question, say you don't have that data now and suggest the app tab that shows it.
You never place orders and never promise profits; trading involves risk. Do not give financial advice; describe what the data shows.
${if (strict) "IMPORTANT: copy every number exactly as written in the FACTS; do not compute, round or estimate new numbers." else ""}
Current time (UTC): $now
FACTS:
""" + facts.joinToString("\n") { "- ${it.text} [${it.source}]" }.ifBlank { "- (no data loaded yet)" }
        val hist = messages.takeLast(7).dropLast(1).filter { it.card == null && it.scan == null }.takeLast(4)
        val prompt = buildString {
            append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
            hist.forEach { m -> append(if (m.user) "<|im_start|>user\n" else "<|im_start|>assistant\n").append(m.text.take(600)).append("<|im_end|>\n") }
            append("<|im_start|>user\n").append(q.take(800)).append("<|im_end|>\n<|im_start|>assistant\n")
        }
        return JarvisNative.complete(prompt, 260, if (strict) 0.1f else 0.3f, listOf("<|im_end|>", "<|im_start|>")).trim()
    }

    private fun ruleAnswer(facts: List<Fact>, topics: Set<Topic> = emptySet()): String {
        if (facts.isEmpty()) return L("Ainda não tenho dados para isso. Espere os livros conectarem ou abra a aba correspondente.")
        val head = when (brain) {
            BrainState.READY, BrainState.LOADING, BrainState.IDLE -> ""
            else -> L("Com os dados do app") + ": "
        }
        return head + JarvisChat.ruleAnswer(facts, topics)
    }

    fun setupLabel(s: TraderSkill.Setup) = L(when (s) {
        TraderSkill.Setup.BREAKOUT_20D -> "máxima de 20 dias"; TraderSkill.Setup.RECLAIM_20MA -> "voltou acima da média de 20"
        TraderSkill.Setup.OVERSOLD -> "sobrevendido"; TraderSkill.Setup.OVERSOLD_BOUNCE -> "repique de sobrevenda"
    })

    fun leanText(l: TraderSkill.Lean) = L(when (l) {
        TraderSkill.Lean.NONE -> "Nenhum replay tocou o alvo nem o stop: o preço na hora de zerar decide."
        TraderSkill.Lean.COIN_FLIP -> "Perto de cara ou coroa"
        TraderSkill.Lean.TOWARD_TARGET -> "Pende para o alvo"
        TraderSkill.Lean.TOWARD_STOP -> "Pende para o stop"
        TraderSkill.Lean.STRONG_TARGET -> "Forte para o alvo"
        TraderSkill.Lean.STRONG_STOP -> "Forte para o stop"
    })

    private fun hmLocal(ms: Long, m: TradeMarket) = java.time.Instant.ofEpochMilli(ms).atZone(m.zone).let { "%02d:%02d %s".format(it.hour, it.minute, if (m == TradeMarket.BTC) "UTC" else "NY") }

    /** Resumo falado do plano. */
    fun cardSummary(c: TradeCard): String = buildString {
        val sym = if (c.symbol == "BTC") "BTC" else c.symbol
        when (c.entry.status) {
            TraderSkill.EntryStatus.TRIGGERED -> {
                append("$sym " + L("rompeu a abertura de 15 minutos") + " ${L("em")} ${px(c.entry.price)}. ")
                when (c.progress?.status) {
                    TraderSkill.ProgressStatus.STOPPED -> { append(L("Mas o trade de hoje já bateu no stop") + " (${px(c.plan?.stop)}" +
                        (c.progress?.timeMs?.let { ", " + hmLocal(it, c.market) } ?: "") + "). " + L("O setup acabou: não há entrada agora.") + " "); return@buildString }
                    TraderSkill.ProgressStatus.TARGET -> { append(L("E já chegou ao alvo") + " (${px(c.plan?.target)}" +
                        (c.progress?.timeMs?.let { ", " + hmLocal(it, c.market) } ?: "") + "). " + L("O movimento já aconteceu: não há entrada agora.") + " "); return@buildString }
                    else -> {}
                }
            }
            TraderSkill.EntryStatus.WAITING -> append("$sym " + L("ainda não rompeu a máxima da abertura") + " (${px(c.entry.orHigh)}); " + L("simulei a entrada no rompimento") + ". ")
            TraderSkill.EntryStatus.BUILDING_RANGE -> append("$sym: " + L("a abertura de 15 minutos ainda está se formando") + ". ")
            TraderSkill.EntryStatus.EXPIRED -> append("$sym: " + L("sem gatilho hoje dentro da janela de entradas") + ". ")
            TraderSkill.EntryStatus.NO_DATA -> append("$sym: " + L("sem pregão hoje") + ". ")
        }
        c.refusal?.let { append(L(it)).append(' ') }
        c.plan?.let { p -> append(L("Stop") + " ${px(p.stop)}, " + L("alvo") + " ${px(p.target)} (${num(p.targetR, 0)}R). ") }
        c.mc?.let { mc ->
            val (l, _) = TraderSkill.lean(mc)
            append(L("Time Machine, 10 mil replays") + ": " + L("alvo primeiro") + " ${num(mc.pTarget * 100, 1)}%, " + L("stop primeiro") + " ${num(mc.pStop * 100, 1)}%, " +
                L("nenhum") + " ${num(mc.pNeither * 100, 1)}%. " + leanText(l) + ". ")
        }
        ScoreBook.fact(c.symbol)?.let { append(L(it.text)).append(". ") }
        c.history?.let { h -> if (h.triggered > 0) append(L("No histórico") + " (${h.sessions} " + L("sessões") + "): ${h.triggered} " + L("gatilhos") + ", ${h.target} " + L("no alvo") + ", ${h.stop} " + L("no stop") + ". ") }
        c.sizing?.let { s -> if (s.refusal == null) append(L("Tamanho") + ": ${TraderSkill.fmtQty(s.qty, c.market)} " + L(if (c.market == TradeMarket.BTC) "BTC" else "ações") + ", " + L("perda máxima") + " US$ ${num(s.maxLoss)}. ") else append(L(s.refusal) + " ") }
        c.risk?.let { r -> append(if (r.pass) L("Risco: PASSA.") else L("Risco: BLOQUEADO") + " (" + r.checks.filter { !it.pass }.joinToString(", ") { L(it.name) } + ").") }
        append(" " + L("Nada foi enviado a nenhuma corretora."))
    }.trim()

    private fun help() = L("Pergunte qualquer coisa do app: preço, livro, paredes, fluxo, liquidações, funding, ETFs, macro, notícias e agenda. " +
        "Para o fluxo de trade, diga por exemplo: plano de trade BTC, time machine TSLA, ou scanner de ações. " +
        "Eu calculo entrada, stop, alvo, 10 mil simulações, tamanho e risco, mas nunca envio ordens.")
}
