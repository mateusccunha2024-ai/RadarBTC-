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
import com.cunhac.btcradar.engine.Aggressor
import com.cunhac.btcradar.engine.BookSide
import com.cunhac.btcradar.engine.FLOW_WINDOWS
import com.cunhac.btcradar.engine.FlowClass
import com.cunhac.btcradar.engine.FlowStats
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.TradeEvent
import com.cunhac.btcradar.engine.Venue
import com.cunhac.btcradar.engine.Wall
import com.cunhac.btcradar.engine.WallClass
import com.cunhac.btcradar.engine.WallStatus

val VENUE_COLORS = listOf(Color(0xFFF3BA2F), Color(0xFF9B7BFF), Color(0xFF5BC0EB))

@Composable
fun Th(vararg cols: Pair<String, Int>) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
    cols.forEach { (t, w) -> T(t, Q.Muted, 10, modifier = if (w > 0) Modifier.width(w.dp) else Modifier.weight(1f)) }
}

@Composable
fun Td(vararg cols: Triple<String, Int, Color>) = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    cols.forEach { (t, w, c) -> T(t, c, 11, monoFont = true, modifier = if (w > 0) Modifier.width(w.dp) else Modifier.weight(1f)) }
}

// =====================================================================
// LIQUIDITY WALLS
// =====================================================================
@Composable
fun WallsTab(ctx: AppContext, s: TerminalState) {
    val th = ctx.hub.wallThresholds
    var venueIdx by remember { mutableIntStateOf(0) }
    var ver by remember { mutableIntStateOf(0) }
    val venue = if (venueIdx == 0) null else Venue.entries[venueIdx - 1]
    val bids = remember(s.tick, venueIdx, ver) { ctx.hub.walls.top(BookSide.BID, venue) }
    val asks = remember(s.tick, venueIdx, ver) { ctx.hub.walls.top(BookSide.ASK, venue) }
    val hist = remember(s.tick) { ctx.hub.walls.history(300) }
    val mid = s.price ?: 0.0
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.background(Q.Panel).padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Chips("Corretora", listOf("TODAS") + Venue.entries.map { it.label.uppercase() }, venueIdx) { venueIdx = it }
            NumField("SMALL ≥", th.small, 60.dp) { it?.let { v -> th.small = v; SettingsStore.put("w.small", v); ver++ } }
            NumField("MEDIUM ≥", th.medium, 60.dp) { it?.let { v -> th.medium = v; SettingsStore.put("w.medium", v) } }
            NumField("LARGE ≥", th.large, 60.dp) { it?.let { v -> th.large = v; SettingsStore.put("w.large", v) } }
            NumField("EXTREME ≥", th.extreme, 60.dp) { it?.let { v -> th.extreme = v; SettingsStore.put("w.extreme", v) } }
            NumField("Faixa ±%", ctx.hub.walls.rangePct, 50.dp) { it?.let { v -> ctx.hub.walls.rangePct = v; SettingsStore.put("w.range", v) } }
            T("BTC · ativas: ${ctx.hub.walls.activeCount()}", Q.Muted, 11)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WallTable("TOP 50 BID WALLS", bids, mid, Q.Buy, Modifier.weight(1f))
            WallTable("TOP 50 ASK WALLS", asks, mid, Q.Sell, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Section("Histórico de concentrações (eventos de saída do livro)", Modifier.fillMaxWidth().height(230.dp)) {
            Column {
                Th("HORA" to 70, "CORRETORA" to 80, "LADO" to 44, "PREÇO" to 90, "STATUS" to 190, "PICO" to 90, "FINAL" to 90, "NEGOCIADO NO NÍVEL" to 130, "PERMANÊNCIA" to 0)
                if (hist.isEmpty()) Empty("Nenhuma concentração saiu do livro ainda", "Quando uma parede some, aqui aparece se houve execução corroborada por trades ou só retirada")
                LazyColumn {
                    items(hist) { w ->
                        val c = when (w.status) { WallStatus.EXECUTION_CORROBORATED -> Q.Accent; WallStatus.POSSIBLY_EXECUTED -> Q.Warn; else -> Q.Muted }
                        Td(Triple(hhmmss(w.statusMs), 70, Q.Muted), Triple(w.venue.label, 80, Q.Text), Triple(w.side.name, 44, if (w.side == BookSide.BID) Q.Buy else Q.Sell),
                            Triple(px(w.price), 90, Q.Text), Triple(w.status.name, 190, c), Triple(num(w.peakQty), 90, Q.Text), Triple(num(w.currentQty), 90, Q.Text),
                            Triple(btc(w.tradedNearBtc), 130, Q.Text), Triple(ago(w.permanenceMs), 0, Q.Muted))
                    }
                }
            }
        }
        T("Identidade = corretora + instrumento + lado + preço. O livro público é agregado por preço: não identificamos ordens individuais nem quem as colocou. " +
            "Sumir do livro não prova execução: EXECUTION_CORROBORATED exige trades no nível ≥ 50% da quantidade.", Q.Muted, 11, wrap = true, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun WallTable(title: String, walls: List<Wall>, mid: Double, color: Color, modifier: Modifier) {
    val maxQ = (walls.maxOfOrNull { it.currentQty } ?: 0.0).coerceAtLeast(1e-9)
    Section("$title · ${walls.size}", modifier.fillMaxHeight()) {
        Column {
            Th("PREÇO" to 84, "CORR." to 58, "ATUAL (BTC)" to 0, "Δ BTC" to 64, "DIST." to 62, "TEMPO" to 50, "STATUS" to 92, "CLASSE" to 68)
            if (walls.isEmpty()) Empty("Nenhuma parede acima do limite SMALL nesta faixa", "Ajuste os limites ou a faixa ±% acima")
            LazyColumn {
                items(walls) { w ->
                    Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(px(w.price), color, 11, monoFont = true, modifier = Modifier.width(84.dp))
                        T(w.venue.label, Q.Muted, 11, modifier = Modifier.width(58.dp))
                        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp, horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth((w.currentQty / maxQ).toFloat().coerceIn(0.02f, 1f)).background(color.copy(alpha = 0.22f)))
                            T(num(w.currentQty), Q.Text, 11, bold = true, monoFont = true, modifier = Modifier.padding(start = 4.dp))
                        }
                        T(signed(w.changeBtc), if (w.changeBtc >= 0) Q.Buy else Q.Sell, 11, monoFont = true, modifier = Modifier.width(64.dp))
                        T(pct(if (mid > 0) w.distancePct(mid) else null), Q.Muted, 11, monoFont = true, modifier = Modifier.width(62.dp))
                        T(ago(w.permanenceMs), Q.Muted, 11, modifier = Modifier.width(50.dp))
                        Box(Modifier.width(92.dp)) { Badge(w.status.name, statusColor(w.status)) }
                        Box(Modifier.width(68.dp)) { Badge(w.cls.name, classColor(w.cls)) }
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
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1.5f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.background(Q.Panel).padding(8.dp).fillMaxWidth()) {
                Chips("Janela", FLOW_WINDOWS.map { it.first }, winIdx) { winIdx = it; SettingsStore.put("fl.win", it) }
                Spacer(Modifier.width(12.dp))
                T("Eventos processados na chegada; a tela agrupa a cada 1,5 s.", Q.Muted, 11)
            }
            Section("Fluxo agressor (${w.first})", Modifier.fillMaxWidth()) {
                Column {
                    Th("FONTE" to 90, "COMPRADO" to 100, "VENDIDO" to 100, "DELTA" to 100, "TRADES" to 70, "MÉDIO" to 80, "MAIOR COMPRA" to 110, "MAIOR VENDA" to 110, "TRADES/S" to 0)
                    fun row(name: String, f: FlowStats?) {}
                    val rows = listOf<Pair<String, FlowStats?>>("CONSOLIDADO" to s.flowAll.getOrNull(winIdx)) + Venue.entries.map { it.label to s.flowByVenue[it]?.getOrNull(winIdx) }
                    rows.forEach { (n, f) ->
                        if (f == null) Td(Triple(n, 90, Q.Text), Triple("sem dados", 0, Q.Muted))
                        else Td(Triple(n, 90, Q.Text), Triple(num(f.buyBtc, 3), 100, Q.Buy), Triple(num(f.sellBtc, 3), 100, Q.Sell),
                            Triple(String.format(BR, "%+.3f", f.deltaBtc), 100, if (f.deltaBtc >= 0) Q.Buy else Q.Sell), Triple(f.count.toString(), 70, Q.Text),
                            Triple(num(f.avgBtc, 4), 80, Q.Muted), Triple(f.maxBuy?.let { num(it.qtyBtc, 3) } ?: "—", 110, Q.Buy),
                            Triple(f.maxSell?.let { num(it.qtyBtc, 3) } ?: "—", 110, Q.Sell), Triple(num(f.tradesPerSec, 1), 0, Q.Muted))
                    }
                }
            }
            Section("CVD · últimos 15 min (BTC, desde o início da janela)", Modifier.fillMaxWidth().weight(1f)) {
                val series = remember(s.tick) { ctx.hub.flow.cvdSeries(900_000) }
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        T("— Total", Q.Text, 11); Venue.entries.forEach { T("— ${it.label}", VENUE_COLORS[it.ordinal], 11) }
                        series.lastOrNull()?.let { T("Total: " + String.format(BR, "%+.2f BTC", it.second.sum()), if (it.second.sum() >= 0) Q.Buy else Q.Sell, 11) }
                    }
                    CvdChart(series)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sig = s.signals.getOrNull(winIdx)
            if (sig != null) {
                val c = when (sig.cls.side) { "LONG FLOW" -> Q.Buy; "SHORT FLOW" -> Q.Sell; else -> Q.Muted }
                Section(if (sig.cls.side == "—") "FLOW SIGNAL" else "${sig.cls.side} SIGNAL (${w.first})") {
                    Column {
                        T(sig.cls.label, c, 18, bold = true)
                        if (sig.cls != FlowClass.NEUTRAL) T("Confiança: ${sig.confidence}", Q.Text, 12)
                        Spacer(Modifier.height(6.dp)); T("DADOS OBSERVADOS", Q.Muted, 10)
                        sig.observed.forEach { T("· $it", Q.Text, 11) }
                        Spacer(Modifier.height(6.dp)); T("INTERPRETAÇÃO", Q.Muted, 10)
                        androidx.compose.material3.Text(sig.interpretation, color = Q.Text, fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp))
                        Spacer(Modifier.height(6.dp)); T("LIMITAÇÕES", Q.Muted, 10)
                        androidx.compose.material3.Text(sig.limitation, color = Q.Muted, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))
                    }
                }
            }
            Section("Derivativos por corretora") {
                Column {
                    Th("FONTE" to 70, "MARK" to 90, "INDEX" to 90, "FUNDING" to 80, "OI (BTC)" to 0)
                    Venue.entries.forEach { v ->
                        val t = s.tickers[v]
                        Td(Triple(v.label, 70, Q.Text), Triple(px(t?.markPrice), 90, Q.Text), Triple(px(t?.indexPrice), 90, Q.Muted),
                            Triple(t?.fundingRate?.let { pct(it * 100, 4) } ?: "—", 80, Q.Text), Triple(t?.oiBtc?.let { num(it, 0) } ?: "—", 0, Q.Text))
                    }
                    Spacer(Modifier.height(4.dp))
                    KV("OI somado (3 corretoras)", ctx.hub.totalOiBtc()?.let { btc(it, 0) } ?: "aguardando as 3 fontes")
                    KV("Variação do OI (${w.first})", pct(ctx.hub.oiChangePct(w.second), 3))
                    KV("Variação do preço (${w.first})", pct(ctx.hub.priceChangePct(w.second), 3))
                    Footnote("OI Binance via REST a cada 15 s (não há WebSocket de OI); Bybit e OKX por WebSocket.")
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
    }
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
    Canvas(Modifier.fillMaxSize().padding(top = 6.dp)) {
        if (series.size < 2) return@Canvas
        val totals = series.map { it.second.sum() }
        val all = totals + series.flatMap { it.second.toList() }
        val lo = all.minOrNull()!!; val hi = all.maxOrNull()!!
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val t0 = series.first().first; val t1 = series.last().first
        val tw = (t1 - t0).coerceAtLeast(1).toFloat()
        fun x(t: Long) = (t - t0) / tw * size.width
        fun y(v: Double) = (size.height - ((v - lo) / span * size.height)).toFloat()
        drawLine(Q.Line, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1f)
        for (vi in 0..3) {
            val color = if (vi == 3) Q.Text else VENUE_COLORS[vi]
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
    val venue = if (venueIdx == 0) null else Venue.entries[venueIdx - 1]
    val period = LT_PERIODS[pIdx].second
    val buys = remember(s.tick, pIdx, venueIdx, ver) { ctx.hub.large.top(period, Aggressor.BUY, venue, 40) }
    val sells = remember(s.tick, pIdx, venueIdx, ver) { ctx.hub.large.top(period, Aggressor.SELL, venue, 40) }
    val recent = remember(s.tick, ver) { ctx.hub.large.recent(150) }
    val clusters = remember(s.tick, pIdx) { ctx.hub.large.clusters(period) }
    val minBtc = ctx.hub.large.minBtc
    val pl = LT_PERIODS[pIdx].first
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.background(Q.Panel).padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Chips("Período", LT_PERIODS.map { it.first }, pIdx) { pIdx = it }
            Chips("Corretora", listOf("TODAS") + Venue.entries.map { it.label.uppercase() }, venueIdx) { venueIdx = it }
            NumField("Mínimo (BTC)", minBtc, 60.dp) { it?.let { v -> ctx.hub.large.minBtc = v; SettingsStore.put("lt.min", v); ver++ } }
            Spacer(Modifier.weight(1f))
            T("Compras $pl: ${buys.size} · ${btc(buys.sumOf { it.qtyBtc })}", Q.Buy, 12, bold = true)
            T("Vendas $pl: ${sells.size} · ${btc(sells.sumOf { it.qtyBtc })}", Q.Sell, 12, bold = true)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeTable("Maiores compras agressoras · $pl", buys, Modifier.weight(1f), "Nenhuma compra ≥ ${num(minBtc, 1)} BTC em $pl")
                TradeTable("Maiores vendas agressoras · $pl", sells, Modifier.weight(1f), "Nenhuma venda ≥ ${num(minBtc, 1)} BTC em $pl")
            }
            Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeTable("Fita de grandes execuções (mais recentes primeiro)", recent, Modifier.weight(1f), "Aguardando execuções ≥ ${num(minBtc, 1)} BTC")
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
        }
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
