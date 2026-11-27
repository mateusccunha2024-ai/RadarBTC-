package com.cunhac.btcradar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import com.cunhac.btcradar.engine.Aggressor
import com.cunhac.btcradar.engine.BookSide
import com.cunhac.btcradar.engine.FLOW_WINDOWS
import com.cunhac.btcradar.engine.FlowClass
import com.cunhac.btcradar.engine.FlowStats
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.TradeEvent
import com.cunhac.btcradar.engine.CORE_VENUES
import com.cunhac.btcradar.engine.Venue
import com.cunhac.btcradar.engine.Wall
import com.cunhac.btcradar.engine.WallClass
import com.cunhac.btcradar.engine.WallSignal
import com.cunhac.btcradar.engine.Analytics
import com.cunhac.btcradar.engine.WallStatus

/**
 * Cor de cada corretora (índice = Venue.ordinal), com variante legível no tema claro.
 * Cores frias e neutras de propósito: amarelo/laranja ficam reservados para aviso e acento,
 * verde/vermelho para compra/venda — uma cor nunca faz dois papéis.
 */
val VENUE_COLORS: List<Color> get() = if (Look.dark)
    listOf(Color(0xFF5AA9FF), Color(0xFFB59CFF), Color(0xFF63D2DC), Color(0xFF93A4BD), Color(0xFFE08FCB), Color(0xFFA7AEBB))
else listOf(Color(0xFF1F63C6), Color(0xFF6A4BD6), Color(0xFF0F7A85), Color(0xFF4A5B75), Color(0xFFA23E86), Color(0xFF5D6573))

@Composable
fun Th(vararg cols: Pair<String, Int>) = Row(Modifier.fillMaxWidth().padding(vertical = if (Look.compact) 1.dp else 3.dp)) {
    cols.forEach { (t, w) -> T(headerCase(t), Q.Muted, 10, modifier = if (w > 0) Modifier.width(w.dp) else Modifier.weight(1f)) }
}

/** Siglas que continuam em maiúsculas nos cabeçalhos. */
private val ACRONYMS = setOf("BTC", "OI", "OKX", "USD", "US$", "CVD", "POC", "VAH", "VAL", "ETF", "ETFS", "RPI", "LONG", "SHORT", "BID", "ASK", "OHLC")

/** Cabeçalho de tabela em caixa normal ("MAIOR COMPRA" → "Maior compra"), mantendo siglas. Só o título do painel fica em caixa alta. */
fun headerCase(t: String): String {
    if (t.any { it.isLowerCase() }) return t
    return t.split(" ").mapIndexed { i, w ->
        val core = w.trim('(', ')', '.', ',', '%', '/')
        when {
            core.uppercase() in ACRONYMS || core.length <= 1 -> w
            i == 0 -> w.lowercase().replaceFirstChar { it.uppercase() }
            else -> w.lowercase()
        }
    }.joinToString(" ")
}

@Composable
fun Td(vararg cols: Triple<String, Int, Color>) = Row(Modifier.fillMaxWidth().padding(vertical = if (Look.compact) 0.dp else 2.dp)) {
    // Colunas de largura fixa cortam; a coluna flexível (largura 0) quebra linha para mostrar tudo, sem "…".
    cols.forEach { (t, w, c) -> T(t, c, 11, monoFont = true, modifier = if (w > 0) Modifier.width(w.dp) else Modifier.weight(1f), wrap = w == 0) }
}

// =====================================================================
// LIQUIDITY WALLS
// =====================================================================
@Composable
fun WallsTab(ctx: AppContext, s: TerminalState) {
    val th = ctx.hub.wallThresholds
    var venueIdx by remember { mutableIntStateOf(0) }
    var ver by remember { mutableIntStateOf(0) }
    val venue = if (venueIdx == 0) null else CORE_VENUES[venueIdx - 1]
    val bids = remember(s.tick, venueIdx, ver) { ctx.hub.walls.top(BookSide.BID, venue) }
    val asks = remember(s.tick, venueIdx, ver) { ctx.hub.walls.top(BookSide.ASK, venue) }
    val hist = remember(s.tick) { ctx.hub.walls.history(300) }
    val mid = s.price ?: 0.0
    Column(Modifier.fillMaxSize()) {
        Controls {
            Chips("Corretora", listOf("Todas") + CORE_VENUES.map { it.label }, venueIdx) { venueIdx = it }
            NumField("SMALL ≥", th.small, 60.dp) { it?.let { v -> th.small = v; SettingsStore.put("w.small", v); ver++ } }
            NumField("MEDIUM ≥", th.medium, 60.dp) { it?.let { v -> th.medium = v; SettingsStore.put("w.medium", v) } }
            NumField("LARGE ≥", th.large, 60.dp) { it?.let { v -> th.large = v; SettingsStore.put("w.large", v) } }
            NumField("EXTREME ≥", th.extreme, 60.dp) { it?.let { v -> th.extreme = v; SettingsStore.put("w.extreme", v) } }
            NumField("Faixa ±%", ctx.hub.walls.rangePct, 50.dp) { it?.let { v -> ctx.hub.walls.rangePct = v; SettingsStore.put("w.range", v) } }
            T("BTC · ativas: ${ctx.hub.walls.activeCount()}", Q.Muted, 11)
        }
        Spacer(Modifier.height(8.dp))
        Panes(grow(1f), Pane(1f, 440.dp) { m -> WallTable("Top 50 paredes de compra", bids, mid, Q.Buy, m) },
            Pane(1f, 440.dp) { m -> WallTable("Top 50 paredes de venda", asks, mid, Q.Sell, m) })
        Spacer(Modifier.height(8.dp))
        val sigs = remember(s.tick) { ctx.hub.walls.signals() }
        Panes(if (compact) Modifier else Modifier.fillMaxWidth().height(230.dp), Pane(1.7f, 300.dp) { m ->
        Section("Histórico de concentrações (eventos de saída do livro)", m, minWidth = 920.dp) {
            Column {
                Th("HORA" to 70, "CORRETORA" to 80, "LADO" to 44, "PREÇO" to 90, "STATUS" to 190, "PICO" to 90, "FINAL" to 90, "NEGOCIADO NO NÍVEL" to 130, "PERMANÊNCIA" to 0)
                if (hist.isEmpty()) Empty("Nenhuma concentração saiu do livro ainda", "Quando uma parede some, aqui aparece se houve execução corroborada por trades ou só retirada")
                LazyColumn {
                    items(hist) { w ->
                        val c = when (w.status) { WallStatus.EXECUTION_CORROBORATED -> Q.Accent; WallStatus.POSSIBLY_EXECUTED -> Q.Warn; else -> Q.Muted }
                        Td(Triple(hhmmss(w.statusMs), 70, Q.Muted), Triple(w.venue.label, 80, Q.Text), Triple(w.side.name, 44, if (w.side == BookSide.BID) Q.Buy else Q.Sell),
                            Triple(px(w.price), 90, Q.Text), Triple(w.status.pt(), 190, c), Triple(num(w.peakQty), 90, Q.Text), Triple(num(w.currentQty), 90, Q.Text),
                            Triple(btc(w.tradedNearBtc), 130, Q.Text), Triple(ago(w.permanenceMs), 0, Q.Muted))
                    }
                }
            }
        }
        }, Pane(1f, 260.dp) { m ->
        Section("Absorção e iceberg · ${sigs.size}", m, minWidth = 440.dp) {
            Column {
                Th("PREÇO" to 80, "CORR." to 58, "SINAL" to 76, "EXECUTADO" to 72, "REPOSTO" to 66, "RESTANTE" to 0)
                if (sigs.isEmpty()) Empty("Nenhuma parede absorvendo agora", "Aparece quando um nível recebe execução e não diminui (ou volta a crescer)")
                LazyColumn {
                    items(sigs) { w ->
                        val sc = if (w.side == BookSide.BID) Q.Buy else Q.Sell
                        Td(Triple(px(w.price), 80, sc), Triple(w.venue.label, 58, Q.Text), Triple(w.signal?.label ?: "", 76, if (w.signal == WallSignal.ICEBERG) Q.Accent else Q.Warn),
                            Triple(num(w.executedBtc, 2), 72, Q.Text), Triple(num(w.replenishedBtc, 2), 66, Q.Text), Triple(num(w.currentQty, 2), 0, Q.Muted))
                    }
                }
            }
        }
        })
        Footnote("Identidade = corretora + instrumento + lado + preço. O livro público é agregado por preço: não identificamos ordens individuais nem quem as colocou. " +
            "Sumir do livro não prova execução: \"Executada (confirmada)\" exige trades no nível ≥ 50% da quantidade. " +
            "EXECUTADO = BTC negociado exatamente no preço da parede desde que ela apareceu; % CONS. = executado ÷ (executado + restante). " +
            "ABSORÇÃO = recebeu execução ≥ 50% do tamanho inicial e manteve ≥ 80% dele. ICEBERG = executou mais do que mostrava e foi reposta 2+ vezes " +
            "(REPOSTO = execução que não reduziu o livro). Ambos são inferências.", Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun WallTable(title: String, walls: List<Wall>, mid: Double, color: Color, modifier: Modifier) {
    val maxQ = (walls.maxOfOrNull { it.currentQty } ?: 0.0).coerceAtLeast(1e-9)
    Section("$title · ${walls.size}", modifier.fillMaxHeight(), minWidth = 790.dp) {
        Column {
            Th("PREÇO" to 80, "CORR." to 54, "ATUAL (BTC)" to 0, "Δ BTC" to 60, "EXEC." to 66, "% CONS." to 52, "DIST." to 58, "TEMPO" to 46, "STATUS" to 104, "CLASSE" to 64, "SINAL" to 72)
            if (walls.isEmpty()) Empty("Nenhuma parede acima do limite SMALL nesta faixa", "Ajuste os limites ou a faixa ±% acima")
            LazyColumn {
                items(walls) { w ->
                    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(px(w.price), color, 11, monoFont = true, modifier = Modifier.width(80.dp))
                        T(w.venue.label, Q.Muted, 11, modifier = Modifier.width(54.dp))
                        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp, horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((w.currentQty / maxQ).toFloat().coerceIn(0.02f, 1f)).background(color.copy(alpha = 0.22f)))
                            T(num(w.currentQty), Q.Text, 11, bold = true, monoFont = true, modifier = Modifier.padding(start = 4.dp))
                        }
                        T(signed(w.changeBtc), if (w.changeBtc >= 0) Q.Buy else Q.Sell, 11, monoFont = true, modifier = Modifier.width(60.dp))
                        T(if (w.executedBtc > 0) num(w.executedBtc, 2) else "·", if (w.executedBtc > 0) Q.Accent else Q.Muted, 11, monoFont = true, modifier = Modifier.width(66.dp))
                        T(num(w.consumedPct, 0) + "%", if (w.consumedPct >= 50) Q.Accent else Q.Muted, 11, monoFont = true, modifier = Modifier.width(52.dp))
                        T(pct(if (mid > 0) w.distancePct(mid) else null), Q.Muted, 11, monoFont = true, modifier = Modifier.width(58.dp))
                        T(ago(w.permanenceMs), Q.Muted, 11, modifier = Modifier.width(46.dp))
                        Box(Modifier.width(104.dp)) { Badge(w.status.pt(), statusColor(w.status)) }
                        Box(Modifier.width(64.dp)) { Badge(w.cls.name, classColor(w.cls)) }
                        Box(Modifier.width(72.dp)) { w.signal?.let { Badge(it.label, if (it == WallSignal.ICEBERG) Q.Accent else Q.Warn) } }
                    }
                }
            }
        }
    }
}

fun statusColor(s: WallStatus): Color = when (s) {
    WallStatus.NEW -> Q.Accent
    WallStatus.INCREASED -> Q.Buy
    WallStatus.REDUCED -> Q.Sell
    WallStatus.EXECUTION_CORROBORATED -> Q.Accent
    WallStatus.POSSIBLY_EXECUTED -> Q.Warn
    else -> Q.Muted
}

fun classColor(c: WallClass): Color = when (c) {
    WallClass.EXTREME -> Q.Accent
    WallClass.LARGE -> Q.Warn
    WallClass.MEDIUM -> Q.Text
    WallClass.SMALL -> Q.Muted
}

// =====================================================================
// LONG & SHORT FLOW
// =====================================================================
@Composable
fun FlowTab(ctx: AppContext, s: TerminalState) {
    var winIdx by remember { mutableIntStateOf(SettingsStore.i("fl.win", 5)) }
    val w = FLOW_WINDOWS[winIdx]
    Panes(Modifier.fillMaxSize(), Pane(1.5f) { pm ->
        Column(pm, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Controls {
                Chips("Janela", FLOW_WINDOWS.map { it.first }, winIdx) { winIdx = it; SettingsStore.put("fl.win", it) }
                T("Eventos processados na chegada; a tela agrupa a cada 1,5 s.", Q.Muted, 11, wrap = compact)
            }
            Section("Fluxo agressor (${w.first})", Modifier.fillMaxWidth(), minWidth = 900.dp) {
                Column {
                    Th("Fonte" to 100, "Comprado" to 100, "Vendido" to 100, "Delta" to 100, "Trades" to 70, "Médio" to 80, "Maior compra" to 110, "Maior venda" to 110, "Trades/s" to 0)
                    fun row(name: String, f: FlowStats?) {}
                    val rows = listOf<Pair<String, FlowStats?>>("Consolidado" to s.flowAll.getOrNull(winIdx)) + CORE_VENUES.map { it.label to s.flowByVenue[it]?.getOrNull(winIdx) }
                    rows.forEach { (n, f) ->
                        if (f == null) Td(Triple(n, 100, Q.Text), Triple("sem dados", 0, Q.Muted))
                        else Td(Triple(n, 100, Q.Text), Triple(num(f.buyBtc, 3), 100, Q.Buy), Triple(num(f.sellBtc, 3), 100, Q.Sell),
                            Triple(String.format(BR, "%+.3f", f.deltaBtc), 100, if (f.deltaBtc >= 0) Q.Buy else Q.Sell), Triple(f.count.toString(), 70, Q.Text),
                            Triple(num(f.avgBtc, 4), 80, Q.Muted), Triple(f.maxBuy?.let { num(it.qtyBtc, 3) } ?: "—", 110, Q.Buy),
                            Triple(f.maxSell?.let { num(it.qtyBtc, 3) } ?: "—", 110, Q.Sell), Triple(num(f.tradesPerSec, 1), 0, Q.Muted))
                    }
                }
            }
            Section("CVD · últimos 15 min (BTC, desde o início da janela)", Modifier.fillMaxWidth().then(grow(1f, 340.dp))) {
                val series = remember(s.tick) { ctx.hub.flow.cvdSeries(900_000) }
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        T("— Total", Q.Text, 11); CORE_VENUES.forEach { T("— ${it.label}", VENUE_COLORS[it.ordinal], 11) }
                        series.lastOrNull()?.let {
                            val tot = it.second.sum()
                            T("Total: " + String.format(BR, "%+.2f BTC", tot), if (tot >= 0) Q.Buy else Q.Sell, 11)
                            Analytics.normalizedCvd(tot, ctx.hub.totalOiBtc())?.let { n ->
                                T("Normalizado: " + String.format(BR, "%+.3f%% do OI", n), if (n >= 0) Q.Buy else Q.Sell, 11)
                            }
                        }
                    }
                    CvdChart(series)
                }
            }
        }
    }, Pane(1f) { pm ->
        Column(pm.then(if (compact) Modifier else Modifier.verticalScroll(rememberScrollState())), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sig = s.signals.getOrNull(winIdx)
            if (sig != null) {
                val c = when (sig.cls.side) { FlowClass.LONG_SIDE -> Q.Buy; FlowClass.SHORT_SIDE -> Q.Sell; else -> Q.Muted }
                Section("Sinal de fluxo (${w.first})") {
                    Column {
                        // O veredito é o dado principal da aba: grande, na cor do lado.
                        T(sig.cls.label, c, 28, bold = true, wrap = true)
                        if (sig.cls != FlowClass.NEUTRAL) T("${sig.cls.side} · confiança ${sig.confidence.lowercase()}", Q.Text, 13, modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.height(10.dp)); T("Dados observados", Q.Muted, 11, bold = true)
                        sig.observed.forEach { T("· $it", Q.Text, 12, wrap = true) }
                        Spacer(Modifier.height(8.dp)); T("Interpretação", Q.Muted, 11, bold = true)
                        T(sig.interpretation, Q.Text, 13, wrap = true)
                        Spacer(Modifier.height(8.dp)); T("Limitações", Q.Muted, 11, bold = true)
                        T(sig.limitation, Q.Muted, 12, wrap = true)
                    }
                }
            }
            Section("Divergências preço × CVD") {
                Column {
                    val divs = s.divergences
                    if (divs.isEmpty()) Empty("Nenhuma divergência detectada", "Checa 5 min, 15 min e 1 h a cada ~15 s: preço faz novo fundo/topo e o CVD não confirma")
                    divs.take(8).forEach { d ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row {
                                T(hhmmss(d.detectedMs), Q.Muted, 11, monoFont = true)
                                Spacer(Modifier.width(8.dp))
                                T("${d.kind.label} · ${d.windowMs / 60_000} min", if (d.kind.bullish) Q.Buy else Q.Sell, 11, bold = true)
                            }
                            T(d.text, Q.Text, 11, wrap = true)
                        }
                    }
                    Footnote("Divergência = o preço faz um fundo mais baixo (ou topo mais alto) na 2ª metade da janela e o CVD não acompanha. Sinal de enfraquecimento, não de reversão garantida.")
                }
            }
            Section("Derivativos por corretora") {
                Column {
                    Th("FONTE" to 70, "MARK" to 90, "INDEX" to 90, "FUNDING" to 80, "OI (BTC)" to 0)
                    CORE_VENUES.forEach { v ->
                        val t = s.tickers[v]
                        Td(Triple(v.label, 70, Q.Text), Triple(px(t?.markPrice), 90, Q.Text), Triple(px(t?.indexPrice), 90, Q.Muted),
                            Triple(t?.fundingRate?.let { pct(it * 100, 4) } ?: "—", 80, Q.Text), Triple(t?.oiBtc?.let { num(it, 0) } ?: "—", 0, Q.Text))
                    }
                    Spacer(Modifier.height(4.dp))
                    KV("OI somado (3 corretoras)", ctx.hub.totalOiBtc()?.let { btc(it, 0) } ?: "aguardando as 3 fontes")
                    KV("Variação do OI (${w.first})", pct(ctx.hub.oiChangePct(w.second), 3))
                    KV("Variação do preço (${w.first})", pct(ctx.hub.priceChangePct(w.second), 3))
                    Footnote("OI Binance via REST a cada 15 s (não há WebSocket de OI); Bybit e OKX por WebSocket. " +
                        "CVD normalizado = CVD ÷ OI somado, em %: permite comparar dias com open interest diferente.")
                }
            }
            Section("Delta por janela · consolidado") {
                Column {
                    FLOW_WINDOWS.forEachIndexed { i, (lbl, _) ->
                        val f = s.flowAll.getOrNull(i)
                        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(lbl, if (i == winIdx) Q.Accent else Q.Muted, 11, modifier = Modifier.width(36.dp))
                            ImbalanceBar(f?.imbalance ?: 0.0, Modifier.weight(1f))
                            T(f?.let { String.format(BR, "%+.2f BTC", it.deltaBtc) } ?: "—", if ((f?.deltaBtc ?: 0.0) >= 0) Q.Buy else Q.Sell, 11,
                                monoFont = true, modifier = Modifier.width(104.dp).padding(start = 8.dp))
                            T(f?.let { String.format(BR, "%+.0f%%", it.imbalance * 100) } ?: "", Q.Muted, 11, monoFont = true, modifier = Modifier.width(48.dp))
                        }
                    }
                    Footnote("Barra = desequilíbrio entre compra e venda agressora (verde para a direita = compradores).", Modifier.padding(top = 4.dp))
                }
            }
        }
    })
}

@Composable
private fun ImbalanceBar(imb: Double, modifier: Modifier) {
    Canvas(modifier.height(12.dp)) {
        val mid = size.width / 2
        drawRect(Q.Panel2, Offset(0f, 2f), Size(size.width, size.height - 4f))
        val w = (imb.coerceIn(-1.0, 1.0) * mid).toFloat()
        if (w >= 0) drawRect(Q.Buy, Offset(mid, 2f), Size(w, size.height - 4f)) else drawRect(Q.Sell, Offset(mid + w, 2f), Size(-w, size.height - 4f))
        drawLine(Q.Muted, Offset(mid, 0f), Offset(mid, size.height), 1f)
    }
}

@Composable
private fun CvdChart(series: List<Pair<Long, DoubleArray>>) {
    if (series.size < 2) { Empty("Aguardando trades para desenhar o CVD"); return }
    val tm = rememberTextMeasurer()
    val st = TextStyle(color = Q.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    val line = Q.Line; val zero = Q.Muted; val text = Q.Text; val colors = VENUE_COLORS
    Canvas(Modifier.fillMaxSize().padding(top = 6.dp)) {
        val totals = series.map { it.second.sum() }
        val all = totals + series.flatMap { it.second.toList() }
        // Uma escala só (BTC); o zero sempre aparece.
        val lo = minOf(all.minOrNull()!!, 0.0); val hi = maxOf(all.maxOrNull()!!, 0.0)
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val gut = 72.dp.toPx(); val pad = 7.dp.toPx()
        val w = size.width - gut; val h = size.height - 2 * pad
        val t0 = series.first().first; val t1 = series.last().first
        val tw = (t1 - t0).coerceAtLeast(1).toFloat()
        fun x(t: Long) = (t - t0) / tw * w
        fun y(v: Double) = (pad + (hi - v) / span * h).toFloat()
        fun label(v: Double, c: androidx.compose.ui.graphics.Color = st.color) =
            drawText(tm, String.format(BR, "%+,.1f BTC", v).replace("+0,0", "0"), Offset(w + 6.dp.toPx(), y(v) - 7.dp.toPx()), st.copy(color = c))
        val minGap = 14.dp.toPx()
        drawLine(zero.copy(alpha = 0.7f), Offset(0f, y(0.0)), Offset(w, y(0.0)), 1f)
        label(0.0)
        if (y(0.0) - y(hi) > minGap) { drawLine(line, Offset(0f, y(hi)), Offset(w, y(hi)), 1f); label(hi) }
        if (y(lo) - y(0.0) > minGap) { drawLine(line, Offset(0f, y(lo)), Offset(w, y(lo)), 1f); label(lo) }
        for (vi in 0..3) {
            val color = if (vi == 3) text else colors[vi]
            var prev: Offset? = null
            series.forEachIndexed { i, (t, d) ->
                val p = Offset(x(t), y(if (vi == 3) totals[i] else d[vi]))
                prev?.let { drawLine(color, it, p, if (vi == 3) 2f else 1.2f) }
                prev = p
            }
        }
    }
}

// =====================================================================
// LARGE TRADES
// =====================================================================
private val LT_PERIODS = listOf("1m" to 60_000L, "5m" to 300_000L, "15m" to 900_000L, "1h" to 3_600_000L)

@Composable
fun LargeTradesTab(ctx: AppContext, s: TerminalState) {
    var pIdx by remember { mutableIntStateOf(1) }
    var venueIdx by remember { mutableIntStateOf(0) }
    var ver by remember { mutableIntStateOf(0) }
    val venue = if (venueIdx == 0) null else CORE_VENUES[venueIdx - 1]
    val period = LT_PERIODS[pIdx].second
    val buys = remember(s.tick, pIdx, venueIdx, ver) { ctx.hub.large.top(period, Aggressor.BUY, venue, 40) }
    val sells = remember(s.tick, pIdx, venueIdx, ver) { ctx.hub.large.top(period, Aggressor.SELL, venue, 40) }
    val recent = remember(s.tick, ver) { ctx.hub.large.recent(150) }
    val clusters = remember(s.tick, pIdx) { ctx.hub.large.clusters(period) }
    val minBtc = ctx.hub.large.minBtc
    val pl = LT_PERIODS[pIdx].first
    Column(Modifier.fillMaxSize()) {
        Controls {
            Chips("Período", LT_PERIODS.map { it.first }, pIdx) { pIdx = it }
            Chips("Corretora", listOf("Todas") + CORE_VENUES.map { it.label }, venueIdx) { venueIdx = it }
            NumField("Mínimo (BTC)", minBtc, 60.dp) { it?.let { v -> ctx.hub.large.minBtc = v; SettingsStore.put("lt.min", v); ver++ } }
            T("Compras $pl: ${buys.size} · ${btc(buys.sumOf { it.qtyBtc })}", Q.Buy, 12, bold = true)
            T("Vendas $pl: ${sells.size} · ${btc(sells.sumOf { it.qtyBtc })}", Q.Sell, 12, bold = true)
        }
        Spacer(Modifier.height(8.dp))
        Panes(grow(1f), Pane(1f) { pm ->
            Column(pm, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeTable("Maiores compras agressoras · $pl", buys, grow(1f, 320.dp), "Nenhuma compra ≥ ${num(minBtc, 1)} BTC em $pl")
                TradeTable("Maiores vendas agressoras · $pl", sells, grow(1f, 320.dp), "Nenhuma venda ≥ ${num(minBtc, 1)} BTC em $pl")
            }
        }, Pane(1.2f) { pm ->
            Column(pm, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeTable("Fita de grandes execuções (mais recentes primeiro)", recent, grow(1f, 380.dp), "Aguardando execuções ≥ ${num(minBtc, 1)} BTC")
                Section("Sequências · 3+ grandes execuções do mesmo lado em até 10 s", Modifier.fillMaxWidth().height(170.dp)) {
                    Column {
                        if (clusters.isEmpty()) Empty("Nenhuma sequência em $pl", "Indica agressão concentrada: várias ordens grandes seguidas do mesmo lado")
                        LazyColumn {
                            items(clusters) { c ->
                                val buy = c.first().aggressor == Aggressor.BUY
                                T("${hhmmss(c.first().recvMs)}–${hhmmss(c.last().recvMs)}  ${if (buy) "COMPRA" else "VENDA"}  ${c.size} execuções  ${btc(c.sumOf { it.qtyBtc })}  " +
                                    "${px(c.minOf { it.price })}–${px(c.maxOf { it.price })}  " + c.map { it.venue.label }.distinct().joinToString(), if (buy) Q.Buy else Q.Sell, 11, monoFont = true)
                            }
                        }
                    }
                }
            }
        })
    }
}

@Composable
private fun TradeTable(title: String, list: List<TradeEvent>, modifier: Modifier, empty: String) {
    val maxQ = (list.maxOfOrNull { it.qtyBtc } ?: 0.0).coerceAtLeast(1e-9)
    Section(title, modifier.fillMaxWidth()) {
        Column {
            Th("HORA" to 70, "CORRETORA" to 78, "LADO" to 62, "PREÇO" to 90, "BTC" to 0)
            if (list.isEmpty()) Empty(empty, "Os eventos aparecem aqui assim que acontecem")
            LazyColumn {
                items(list) { t ->
                    val c = if (t.aggressor == Aggressor.BUY) Q.Buy else Q.Sell
                    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(hhmmss(t.recvMs), Q.Muted, 11, monoFont = true, modifier = Modifier.width(70.dp))
                        T(t.venue.label, Q.Text, 11, modifier = Modifier.width(78.dp))
                        T(if (t.aggressor == Aggressor.BUY) "COMPRA" else "VENDA", c, 11, modifier = Modifier.width(62.dp))
                        T(px(t.price), Q.Text, 11, monoFont = true, modifier = Modifier.width(90.dp))
                        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp), contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((t.qtyBtc / maxQ).toFloat().coerceIn(0.03f, 1f)).background(c.copy(alpha = 0.22f)))
                            T(num(t.qtyBtc, 3), c, 11, bold = true, monoFont = true, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
