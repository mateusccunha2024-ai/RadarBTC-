package com.cunhac.btcradar.desktop

import com.cunhac.btcradar.engine.BookSide
import com.cunhac.btcradar.engine.CalEvent
import com.cunhac.btcradar.engine.Corr
import com.cunhac.btcradar.engine.EconSeries
import com.cunhac.btcradar.engine.FedRate
import com.cunhac.btcradar.engine.FeedAlert
import com.cunhac.btcradar.engine.FeedKind
import com.cunhac.btcradar.engine.FlowSnapshot
import com.cunhac.btcradar.engine.Hub
import com.cunhac.btcradar.engine.IntegratedView
import com.cunhac.btcradar.engine.LiqSide
import com.cunhac.btcradar.engine.MacroActuals
import com.cunhac.btcradar.engine.MacroConfluence
import com.cunhac.btcradar.engine.MacroCorr
import com.cunhac.btcradar.engine.MacroDir
import com.cunhac.btcradar.engine.MacroEvents
import com.cunhac.btcradar.engine.MacroFactor
import com.cunhac.btcradar.engine.MacroInputs
import com.cunhac.btcradar.engine.MacroIntensity
import com.cunhac.btcradar.engine.MacroNews
import com.cunhac.btcradar.engine.MacroRules
import com.cunhac.btcradar.engine.MacroScore
import com.cunhac.btcradar.engine.MacroScoring
import com.cunhac.btcradar.engine.MacroSources
import com.cunhac.btcradar.engine.MarketSeries
import com.cunhac.btcradar.engine.AlertKind
import com.cunhac.btcradar.engine.PT_BR
import com.cunhac.btcradar.engine.Reliability
import com.cunhac.btcradar.engine.TgaSeries
import com.cunhac.btcradar.engine.TreasuryCurve
import com.cunhac.btcradar.engine.WallSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/** Situação de cada fonte (aba Radar macro → Fontes). */
data class SourceStatus(val name: String, val ok: Boolean, val lastOkMs: Long, val error: String?, val fromCache: Boolean)

data class MacroState(
    val markets: Map<String, MarketSeries> = emptyMap(),
    val curve: TreasuryCurve? = null,
    val fed: FedRate? = null,
    val econ: Map<String, EconSeries> = emptyMap(),
    val tga: TgaSeries? = null,
    val calendar: List<CalEvent> = emptyList(),
    val fomc: List<Long> = emptyList(),
    val news: List<MacroNews> = emptyList(),
    val score: MacroScore? = null,
    val corr: List<Corr> = emptyList(),
    val flow: FlowSnapshot? = null,
    val integrated: IntegratedView? = null,
    val sources: List<SourceStatus> = emptyList(),
    val updatedMs: Long = 0,
)

/** Configuração do Radar Macro (guardada só neste aparelho). */
object MacroSettings {
    fun weight(f: MacroFactor) = SettingsStore.i("mc.w.${f.name}", f.defaultWeight)
    fun setWeight(f: MacroFactor, w: Int) = SettingsStore.put("mc.w.${f.name}", w.coerceIn(0, 50))
    fun weights() = MacroFactor.entries.associateWith { weight(it) }
    fun resetWeights() = MacroFactor.entries.forEach { SettingsStore.put("mc.w.${it.name}", it.defaultWeight) }
    /** Minutos de antecedência do aviso de evento de alto impacto. */
    var leadMin: Int
        get() = SettingsStore.i("mc.lead", 30)
        set(v) = SettingsStore.put("mc.lead", v.coerceIn(5, 240))
    /** Chave opcional da API v2 do BLS: variável de ambiente BLS_API_KEY ou campo no app. Nunca vai no código. */
    var blsKey: String
        get() = SettingsStore.s("mc.bls", "").ifBlank { System.getenv("BLS_API_KEY") ?: "" }
        set(v) = SettingsStore.put("mc.bls", v.trim())
}

/**
 * Coleta das fontes macro com cache em disco, intervalos próprios por fonte (respeita limites como o do BLS)
 * e espera após falha. Calcula o score, as correlações e a leitura integrada com o order flow, e dispara os alertas
 * uma única vez por ocorrência.
 */
class MacroEngine(private val hub: Hub, private val etf: () -> EtfModel?) {
    val state = MutableStateFlow(MacroState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private val dir: File by lazy { File(AppDirs.logs.parentFile, "macro").apply { mkdirs() } }

    private val markets = HashMap<String, MarketSeries>()
    private var curve: TreasuryCurve? = null
    private var fed: FedRate? = null
    private val econ = HashMap<String, EconSeries>()
    private var tga: TgaSeries? = null
    private var calendar: List<CalEvent> = emptyList()
    private var fomc: List<Long> = emptyList()
    private val rawNews = HashMap<String, List<MacroSources.RawNews>>()

    private val loaded = HashMap<String, Long>()
    private val failUntil = HashMap<String, Long>()
    private val status = LinkedHashMap<String, SourceStatus>()
    private val fired = LinkedHashSet<String>()
    private var lastScore: MacroScore? = null

    fun start() {
        if (started) return
        started = true
        runCatching { File(dir, "alerts-fired.txt").takeIf { it.exists() }?.readLines()?.let { fired.addAll(it.takeLast(400)) } }
        scope.launch {
            while (true) {
                runCatching { refresh() }.onFailure { hub.log(com.cunhac.btcradar.engine.LogLevel.WARN, null, "Radar macro: ${it.message}") }
                delay(60_000)
            }
        }
    }

    /** Força nova coleta de tudo (botão Atualizar), respeitando só a espera após falha. */
    fun refreshNow() = scope.launch {
        dir.listFiles()?.filter { it.name.endsWith(".txt") && !it.name.startsWith("alerts") && !it.name.startsWith("bls") }?.forEach { it.setLastModified(0) }
        refresh()
    }

    /** Recalcula (pesos mudaram) sem baixar nada. */
    fun recompute() = scope.launch { publish(System.currentTimeMillis()) }

    private suspend fun src(name: String, label: String, everyMs: Long, fetch: suspend () -> String, apply: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val f = File(dir, "$name.txt")
        val age = if (f.exists()) now - f.lastModified() else Long.MAX_VALUE
        var err: String? = null
        if (age >= everyMs && now >= (failUntil[name] ?: 0)) {
            try {
                val t = fetch()
                if (t.isBlank()) error("resposta vazia")
                f.writeText(t)
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                failUntil[name] = now + minOf(everyMs, 15 * 60_000L)
            }
        }
        if (f.exists() && loaded[name] != f.lastModified()) {
            try { apply(f.readText()); loaded[name] = f.lastModified() } catch (e: Exception) { err = "formato inesperado (${e.message?.take(80)})" }
        }
        status[name] = SourceStatus(label, err == null && f.exists(), if (f.exists()) f.lastModified() else 0, err, fromCache = err != null && f.exists())
    }

    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        val year = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).year
        for (m in MacroSources.MARKETS) {
            val (key, label, sym, unit) = m
            src("yh_$key", "Yahoo Finance · $label", 5 * 60_000L, { MacroSources.get(MacroSources.yahooUrl(sym)) }) {
                markets[key] = MacroSources.parseYahoo(key, label, unit, it, System.currentTimeMillis())
            }
        }
        src("ust", "U.S. Treasury · yields oficiais", 60 * 60_000L, {
            MacroSources.get(MacroSources.treasuryUrl(year)) + "\n" + runCatching { MacroSources.get(MacroSources.treasuryUrl(year - 1)) }.getOrDefault("")
        }) { curve = TreasuryCurve(MacroSources.parseTreasury(it), System.currentTimeMillis()) }
        src("effr", "NY Fed · taxa efetiva (EFFR)", 3 * 3_600_000L, { MacroSources.get(MacroSources.EFFR_URL) }) { fed = MacroSources.parseEffr(it, System.currentTimeMillis()) }
        val key = MacroSettings.blsKey
        src("bls", "BLS · CPI, emprego, salários", if (key.isBlank()) 12 * 3_600_000L else 3 * 3_600_000L,
            { MacroSources.postJson(MacroSources.blsUrl(key), MacroSources.blsBody(key, year)) }) { econ.putAll(MacroSources.parseBls(it, System.currentTimeMillis())) }
        for ((id, v) in MacroSources.PCE) {
            src("pce_$id", "BEA · ${v.second}", 12 * 3_600_000L, { MacroSources.get(MacroSources.dbnomicsUrl(v.first)) }) {
                econ[id] = MacroSources.parseDbnomics(id, v.second, it, System.currentTimeMillis())
            }
        }
        src("tga", "Tesouro · saldo do TGA", 6 * 3_600_000L, { MacroSources.get(MacroSources.TGA_URL) }) { tga = MacroSources.parseTga(it, System.currentTimeMillis()) }
        src("cal", "Calendário econômico (Forex Factory)", 60 * 60_000L, { MacroSources.get(MacroSources.CAL_URL) }) { calendar = MacroSources.parseCalendar(it) }
        src("fomc", "Federal Reserve · reuniões do FOMC", 24 * 3_600_000L, { MacroSources.get(MacroSources.FOMC_URL) }) { fomc = MacroSources.parseFomc(it) }
        MacroSources.NEWS_QUERIES.forEachIndexed { i, q ->
            src("gn_$i", "Google News · tema ${i + 1}", 20 * 60_000L, { MacroSources.get(MacroSources.gnewsUrl(q)) }) { rawNews["gn_$i"] = MacroSources.parseRss(it, "Google News") }
        }
        src("fedrss", "Federal Reserve · comunicados", 30 * 60_000L, { MacroSources.get(MacroSources.FED_RSS) }) {
            rawNews["fedrss"] = MacroSources.parseRss(it, "Federal Reserve", "Federal Reserve")
        }
        src("gdelt", "GDELT · notícias globais", 30 * 60_000L, { MacroSources.get(MacroSources.gdeltUrl()) }) { rawNews["gdelt"] = MacroSources.parseGdelt(it) }
        publish(System.currentTimeMillis())
    }

    private fun flowSnapshot(now: Long): FlowSnapshot? {
        val s = hub.state.value
        val f15 = s.flowAll.getOrNull(6)
        if (s.price == null && f15 == null) return null
        val liq = hub.liqs.since(now - 3_600_000L)
        val walls = hub.walls.signals(60).filter { it.signal == WallSignal.ABSORPTION && now - it.lastSeenMs < 15 * 60_000L }
        val first = hub.samples(3_600_000L).firstOrNull { it.price != null }?.price
        val p = s.price
        val chg = if (first != null && p != null && first > 0) (p - first) / first * 100 else null
        return FlowSnapshot(f15?.deltaBtc, f15?.volumeBtc, liq.filter { it.side == LiqSide.LONG }.sumOf { it.qtyBtc },
            liq.filter { it.side == LiqSide.SHORT }.sumOf { it.qtyBtc }, walls.count { it.side == BookSide.BID }, walls.count { it.side == BookSide.ASK }, chg)
    }

    private fun publish(now: Long) {
        val news = MacroRules.dedupe(MacroSources.classify(rawNews.values.flatten()), now)
        val cal = MacroActuals.fill(calendar, econ, now)
        val e = etf()?.state?.value
        val etfNet = e?.takeIf { it.days.size >= 5 }?.sum(5)
        val inp = MacroInputs(fed, curve, markets["TNX"], econ["CORE_CPI"], econ["UNEMP"], econ["AHE"], markets["DXY"], markets["NDX"], markets["VIX"], tga, news,
            etfNet, e?.latest?.date?.toString())
        val score = MacroScoring.compute(inp, MacroSettings.weights(), now)
        val btc = markets["BTC"]
        val corr = if (btc == null) emptyList() else MacroCorr.all(btc, listOfNotNull(markets["SPX"], markets["NDX"], markets["DXY"], markets["GOLD"], markets["TNX"], markets["VIX"]), now)
        val flow = flowSnapshot(now)
        val integ = flow?.let { MacroConfluence.evaluate(score.state, it) }
        val st = MacroState(HashMap(markets), curve, fed, HashMap(econ), tga, cal, fomc, news, score, corr, flow, integ, status.values.toList(), now)
        state.value = st
        runCatching { alerts(st, now) }
    }

    // ------------------------------------------------------------------ alertas

    private fun fire(kind: AlertKind, key: String, text: String, d: MacroDir?, side: String, where: String, strong: Boolean) {
        if (!fired.add(key)) return
        while (fired.size > 400) fired.remove(fired.first())
        runCatching { File(dir, "alerts-fired.txt").writeText(fired.joinToString("\n")) }
        hub.emit(kind, "macro|$key", text)
        hub.feed(FeedAlert(System.currentTimeMillis(), FeedKind.MACRO, when (d) { MacroDir.FAVORABLE -> true; MacroDir.UNFAVORABLE -> false; else -> null },
            side, null, where, text, strong))
    }

    private fun alerts(s: MacroState, now: Long) {
        val lead = MacroSettings.leadMin * 60_000L
        for (e in s.calendar) {
            val dt = e.timeMs - now
            if (e.high && dt in 1..lead) {
                fire(AlertKind.MACRO_EVENT, "ev|${e.key}", String.format(PT_BR, "Em %d min: %s (EUA). Expectativa %s · anterior %s. Impacto alto no mercado.",
                    dt / 60_000, e.title, e.forecast.ifBlank { "—" }, e.previous.ifBlank { "—" }), null, "Evento", "Calendário", true)
            }
            val sp = MacroEvents.surprise(e)
            if (sp != null && sp.dir != MacroDir.NEUTRAL && now - e.timeMs < 6 * 3_600_000L) {
                fire(AlertKind.MACRO_SURPRISE, "sp|${e.key}", "${e.title}: ${e.actual} contra expectativa ${e.forecast} (${sp.label.lowercase()}). " +
                    "Leitura provável: ${sp.dir.label.lowercase()}. ${sp.chain.joinToString(" → ")}", sp.dir, "Dado", e.actualSource ?: "Oficial", true)
            }
        }
        val fomcNext = s.fomc.firstOrNull { it > now }
        if (fomcNext != null && fomcNext - now in 1..lead) {
            fire(AlertKind.MACRO_EVENT, "fomc|$fomcNext", "Decisão de juros do FOMC em ${(fomcNext - now) / 60_000} min.", null, "FOMC", "Federal Reserve", true)
        }
        val day = MacroScoring.isoDay(now)
        s.markets["DXY"]?.dayPct?.let { p ->
            if (abs(p) >= 0.6) fire(AlertKind.MACRO_MARKET, "dxy|$day|${p > 0}", String.format(PT_BR, "Dólar (DXY) %+.2f%% no dia. Dólar %s costuma %s o BTC.", p,
                if (p > 0) "forte" else "fraco", if (p > 0) "pressionar" else "favorecer"), if (p > 0) MacroDir.UNFAVORABLE else MacroDir.FAVORABLE, "DXY", "Yahoo Finance", abs(p) >= 1.0)
        }
        s.markets["TNX"]?.dayChange?.let { c ->
            if (abs(c) >= 0.10) fire(AlertKind.MACRO_MARKET, "tnx|$day|${c > 0}", String.format(PT_BR, "Treasury 10 anos %+.0f bp no dia (%.2f%%).", c * 100, s.markets["TNX"]?.last ?: 0.0),
                if (c > 0) MacroDir.UNFAVORABLE else MacroDir.FAVORABLE, "Yields", "Yahoo Finance", abs(c) >= 0.15)
        }
        s.markets["VIX"]?.let { v ->
            val p = v.dayPct
            if (p != null && p >= 15) fire(AlertKind.MACRO_MARKET, "vix|$day", String.format(PT_BR, "VIX dispara %+.1f%% no dia (%.1f): aversão a risco.", p, v.last),
                MacroDir.UNFAVORABLE, "VIX", "Yahoo Finance", p >= 25)
        }
        var geo = 0
        for (n in s.news) {
            if (geo >= 3) break
            if (now - n.timeMs > 3 * 3_600_000L) continue
            if (n.intensity.weight < MacroIntensity.HIGH.weight || n.reliability.weight < Reliability.HIGH.weight) continue
            fire(AlertKind.MACRO_GEO, "geo|${MacroRules.normTitle(n.title).hashCode()}", "${n.category.label}: ${n.title} (${n.source}). ${n.dir.label}.", n.dir,
                n.category.label, n.source, n.intensity == MacroIntensity.CRITICAL)
            geo++
        }
        val btc = s.markets["BTC"]; val ndx = s.markets["NDX"]
        if (btc != null && ndx != null && ndx.times.isNotEmpty() && now - ndx.times.last() < 90 * 60_000L) {
            val b0 = btc.valueAt(now - 3_600_000L); val n0 = ndx.valueAt(now - 3_600_000L)
            val b1 = btc.last; val n1 = ndx.last
            if (b0 != null && n0 != null && b1 != null && n1 != null) {
                val bp = (b1 - b0) / b0 * 100; val np = (n1 - n0) / n0 * 100
                if ((bp >= 0.8 && np <= -0.5) || (bp <= -0.8 && np >= 0.5)) {
                    fire(AlertKind.MACRO_DIVERGENCE, "div|${now / 3_600_000L}", String.format(PT_BR, "BTC %+.2f%% e Nasdaq 100 %+.2f%% na última hora: movimento próprio do BTC, " +
                        "não acompanhando o mercado americano.", bp, np), if (bp > 0) MacroDir.FAVORABLE else MacroDir.UNFAVORABLE, "Divergência", "Yahoo Finance", false)
                }
            }
        }
        val sc = s.score ?: return
        val cur = sc.score ?: return
        val prev = lastScore
        val old = prev?.score
        if (prev == null || old == null) { lastScore = sc; return }
        if (abs(cur - old) >= 25 || sc.state != prev.state) {
            fire(AlertKind.MACRO_SCORE, "sc|${sc.state}|${now / 3_600_000L}", "BTC Macro Sentiment: $old → $cur (${sc.label}). Confiança ${sc.confidence}%.",
                sc.state, "Score", "Radar macro", abs(cur - old) >= 40)
            lastScore = sc
        }
    }
}
