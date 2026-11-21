package com.cunhac.btcradar.desktop

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cunhac.btcradar.engine.BookSide
import com.cunhac.btcradar.engine.BookView
import com.cunhac.btcradar.engine.ConnState
import com.cunhac.btcradar.engine.ConsRow
import com.cunhac.btcradar.engine.Consolidate
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.CORE_VENUES
import com.cunhac.btcradar.engine.Venue
import kotlin.math.floor

private val GRANS = listOf(0.0, 1.0, 5.0, 10.0, 50.0, 100.0)
private val LEVS = listOf(25, 50, 100, 200, 500)
private val MINS = listOf(0.0, 0.1, 0.5, 1.0, 5.0)
private val INTS = listOf(0.5f, 1f, 2f, 4f)

@Composable
fun OrderflowTab(ctx: AppContext, s: TerminalState) {
    var venueIdx by remember { mutableIntStateOf(SettingsStore.i("of.venue", 0)) }
    var granIdx by remember { mutableIntStateOf(SettingsStore.i("of.gran", 0)) }
    var levIdx by remember { mutableIntStateOf(SettingsStore.i("of.lev", 1)) }
    var minIdx by remember { mutableIntStateOf(SettingsStore.i("of.min", 0)) }
    var intIdx by remember { mutableIntStateOf(SettingsStore.i("of.int", 1)) }
    var exIdx by remember { mutableIntStateOf(SettingsStore.i("of.ex", 0)) }
    var rpi by remember { mutableStateOf(SettingsStore.b("of.rpi", false)) }
    var manual by remember { mutableStateOf(SettingsStore.b("of.manual", false)) }
    var rMin by remember { mutableStateOf(SettingsStore.d("of.rmin", 0.0).takeIf { it > 0 }) }
    var rMax by remember { mutableStateOf(SettingsStore.d("of.rmax", 0.0).takeIf { it > 0 }) }

    val venues = if (venueIdx == 0) CORE_VENUES.toSet() else setOf(CORE_VENUES[venueIdx - 1])
    val levels = LEVS[levIdx]
    val book = remember(s.tick, venueIdx, granIdx, levIdx, minIdx, rpi, manual, rMin, rMax) {
        val ranged = manual && (rMin != null || rMax != null)
        val raw = Consolidate.build(s.books.values, s.rpi, GRANS[granIdx], if (ranged) 100_000 else levels, MINS[minIdx], venues, rpi)
        if (!ranged) raw else {
            fun ok(r: ConsRow) = (rMin == null || r.price >= rMin!!) && (rMax == null || r.price <= rMax!!)
            raw.copy(asks = raw.asks.filter(::ok).take(levels), bids = raw.bids.filter(::ok).take(levels))
        }
    }

    val g = GRANS[granIdx]
    val exWin = listOf(300_000L, 900_000L, 3_600_000L)[exIdx]
    val exec = remember(s.tick, exIdx, granIdx, venueIdx) {
        ctx.hub.flow.volumeByPrice(exWin, venues, { p -> if (g <= 0) Math.round(p * 100) / 100.0 else floor(p / g) * g })
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1.7f).fillMaxHeight().background(Q.Panel).padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Chips("Livro", listOf("CONSOLIDADO") + CORE_VENUES.map { it.label.uppercase() }, venueIdx) { venueIdx = it; SettingsStore.put("of.venue", it) }
                Chips("Agrupar", listOf("Exato", "1", "5", "10", "50", "100"), granIdx) { granIdx = it; SettingsStore.put("of.gran", it) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Chips("Níveis", LEVS.map { it.toString() }, levIdx) { levIdx = it; SettingsStore.put("of.lev", it) }
                Chips("Mín.", MINS.map { num(it, 1) }, minIdx) { minIdx = it; SettingsStore.put("of.min", it) }
                Chips("Intensidade", listOf("0,5x", "1x", "2x", "4x"), intIdx) { intIdx = it; SettingsStore.put("of.int", it) }
                Toggle("Bybit RPI", rpi) { rpi = it; SettingsStore.put("of.rpi", it) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Chips("Executado", listOf("5m", "15m", "1h"), exIdx) { exIdx = it; SettingsStore.put("of.ex", it) }
                Chips("Centro", listOf("AUTO (mid ${px(s.price)})", "MANUAL"), if (manual) 1 else 0) { manual = it == 1; SettingsStore.put("of.manual", manual) }
                if (manual) {
                    NumField("Faixa mín", rMin) { rMin = it; SettingsStore.put("of.rmin", it ?: 0.0) }
                    NumField("Faixa máx", rMax) { rMax = it; SettingsStore.put("of.rmax", it ?: 0.0) }
                }
                if (book.missing.isNotEmpty()) T("Fora da consolidação: " + book.missing.joinToString { it.label } + " (livro inválido, ver STATUS)", Q.Sell, 11)
            }
            Spacer(Modifier.height(8.dp))
            Ladder(book.asks, book.bids, rpi, INTS[intIdx], s.tick, exec, g, listOf("5m", "15m", "1h")[exIdx])
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Resumo do livro exibido") {
                Column {
                    KV("Maior BID", book.biggestBid?.let { "${px(it.price)} · ${btc(it.total)}" } ?: "—", Q.Buy)
                    KV("Maior ASK", book.biggestAsk?.let { "${px(it.price)} · ${btc(it.total)}" } ?: "—", Q.Sell)
                    KV("Profundidade BID", btc(book.bidSum))
                    KV("Profundidade ASK", btc(book.askSum))
                    KV("Desequilíbrio", pct(book.imbalance * 100, 1), if (book.imbalance >= 0) Q.Buy else Q.Sell)
                    val bb = book.bids.firstOrNull()?.price; val ba = book.asks.firstOrNull()?.price
                    KV("Spread consolidado", when { bb == null || ba == null -> "—"; ba < bb -> "cruzado entre corretoras"; else -> num(ba - bb, 1) + " USDT" })
                }
            }
            CORE_VENUES.forEach { v -> s.books[v]?.let { BookCard(it, s) } }
            s.rpi?.let { BookCard(it, s, "BYBIT RPI (só parte RPI, 50 níveis)") }
        }
    }
}

@Composable
private fun BookCard(b: BookView, s: TerminalState, title: String = "${b.venue.label.uppercase()} ORDER BOOK") {
    val conn = s.statuses.filter { it.venue == b.venue }
    val connOk = conn.isNotEmpty() && conn.all { it.state == ConnState.LIVE }
    Section(title) {
        Column {
            KV("Conexão", if (connOk) "LIVE" else conn.joinToString { it.state.name }.ifEmpty { "—" }, if (connOk) Q.Buy else Q.Sell)
            KV("Sequência", if (b.valid) "OK" else shortReason(b.note), if (b.valid) Q.Buy else Q.Sell)
            KV("Spread próprio", if (b.valid && b.bestBid != null && b.bestAsk != null) num(b.bestAsk!! - b.bestBid!!, 1) + " USDT" else "—")
            KV("Níveis válidos (bid/ask)", if (b.valid) "${b.bidLevels} / ${b.askLevels}" else "—")
            KV("Última atualização", if (b.lastUpdateMs > 0) ago(s.now - b.lastUpdateMs) + " atrás" else "—")
            KV("Latência (evento → recebido)", "${b.latencyMs} ms")
            KV("Último update id", b.lastSeq.toString())
            KV("Reconstruções", b.resyncs.toString())
        }
    }
}

@Composable
private fun Ladder(asks: List<ConsRow>, bids: List<ConsRow>, showRpi: Boolean, intensity: Float, tick: Long,
                   exec: Map<Double, DoubleArray>, g: Double, exLabel: String) {
    val max = ((asks + bids).maxOfOrNull { it.total } ?: 0.0).coerceAtLeast(1e-9)
    fun ex(r: ConsRow): DoubleArray? = exec[if (g > 0 && r.side == BookSide.ASK) r.price - g else r.price]
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        T("PREÇO", Q.Muted, 10, modifier = Modifier.width(88.dp))
        T("QUANTIDADE (BTC)", Q.Muted, 10, modifier = Modifier.weight(1f))
        listOf("BIN", "BYB", "OKX").forEach { T(it, Q.Muted, 10, modifier = Modifier.width(60.dp)) }
        if (showRpi) T("RPI", Q.Muted, 10, modifier = Modifier.width(52.dp))
        T("ORDENS", Q.Muted, 10, modifier = Modifier.width(52.dp))
        T("TOTAL", Q.Muted, 10, modifier = Modifier.width(70.dp).padding(start = 4.dp))
        T("ACUMULADO", Q.Muted, 10, modifier = Modifier.width(78.dp))
        T("EX.COMPRA", Q.Muted, 10, modifier = Modifier.width(74.dp))
        T("EX.VENDA", Q.Muted, 10, modifier = Modifier.width(70.dp))
    }
    T("ACUMULADO = BTC somado do preço atual até a linha · ORDENS = nº de ordens no nível (só a OKX publica) · EX. = BTC executado no nível nos últimos $exLabel",
        Q.Muted, 10, modifier = Modifier.padding(bottom = 4.dp))
    val list = rememberLazyListState()
    var centered by remember { mutableStateOf(false) }
    LaunchedEffect(asks.size, bids.size) {
        if (!centered && asks.isNotEmpty()) { list.scrollToItem((asks.size - 12).coerceAtLeast(0)); centered = true }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        items(asks.reversed()) { LadderRow(it, max, showRpi, intensity, ex(it)) }
        item {
            val bb = bids.firstOrNull()?.price; val ba = asks.firstOrNull()?.price
            Row(Modifier.fillMaxWidth().background(Q.Panel2).padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center) {
                T(when {
                    bb == null || ba == null -> "sem livro válido"
                    ba < bb -> "livros cruzados entre corretoras: melhor compra ${px(bb)} acima da melhor venda ${px(ba)} (cada corretora tem preço próprio)"
                    else -> "spread ${num(ba - bb, 1)}  ·  mid ${px((ba + bb) / 2)}"
                }, Q.Accent, 11)
            }
        }
        items(bids) { LadderRow(it, max, showRpi, intensity, ex(it)) }
    }
}

@Composable
private fun LadderRow(r: ConsRow, max: Double, showRpi: Boolean, intensity: Float, ex: DoubleArray?) {
    val buy = r.side == BookSide.BID
    val base = if (buy) Q.Buy else Q.Sell
    val frac = ((r.total / max).toFloat() * intensity).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        T(px(r.price), base, 11, monoFont = true, modifier = Modifier.width(88.dp))
        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp)) {
            if (frac > 0.003f) Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(base.copy(alpha = 0.25f + 0.6f * frac)))
        }
        for (i in 0..2) T(if (r.byVenue[i] > 0) qty(r.byVenue[i]) else "·", if (r.byVenue[i] > 0) Q.Text else Q.Line, 11, monoFont = true, modifier = Modifier.width(60.dp))
        if (showRpi) T(if (r.byVenue[3] > 0) qty(r.byVenue[3]) else "·", Q.Muted, 11, monoFont = true, modifier = Modifier.width(52.dp))
        T(if (r.okxOrders > 0) r.okxOrders.toString() else "·", if (r.okxOrders > 0) Q.Muted else Q.Line, 11, monoFont = true, modifier = Modifier.width(52.dp))
        T(qty(r.total), Q.Text, 11, bold = frac > 0.5f, monoFont = true, modifier = Modifier.width(70.dp).padding(start = 4.dp))
        T(num(r.cumulative, 2), Q.Muted, 11, monoFont = true, modifier = Modifier.width(78.dp))
        val eb = ex?.get(0) ?: 0.0; val es = ex?.get(1) ?: 0.0
        T(if (eb > 0) qty(eb) else "·", if (eb > 0) Q.Buy else Q.Line, 11, monoFont = true, modifier = Modifier.width(74.dp))
        T(if (es > 0) qty(es) else "·", if (es > 0) Q.Sell else Q.Line, 11, monoFont = true, modifier = Modifier.width(70.dp))
    }
}
