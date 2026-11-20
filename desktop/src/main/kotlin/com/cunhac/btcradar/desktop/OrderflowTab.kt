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
import com.cunhac.btcradar.engine.Venue

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
    var rpi by remember { mutableStateOf(SettingsStore.b("of.rpi", false)) }
    var manual by remember { mutableStateOf(SettingsStore.b("of.manual", false)) }
    var rMin by remember { mutableStateOf(SettingsStore.d("of.rmin", 0.0).takeIf { it > 0 }) }
    var rMax by remember { mutableStateOf(SettingsStore.d("of.rmax", 0.0).takeIf { it > 0 }) }

    val venues = if (venueIdx == 0) Venue.entries.toSet() else setOf(Venue.entries[venueIdx - 1])
    val levels = LEVS[levIdx]
    val book = remember(s.tick, venueIdx, granIdx, levIdx, minIdx, rpi, manual, rMin, rMax) {
        val ranged = manual && (rMin != null || rMax != null)
        val raw = Consolidate.build(s.books.values, s.rpi, GRANS[granIdx], if (ranged) 100_000 else levels, MINS[minIdx], venues, rpi)
        if (!ranged) raw else {
            fun ok(r: ConsRow) = (rMin == null || r.price >= rMin!!) && (rMax == null || r.price <= rMax!!)
            raw.copy(asks = raw.asks.filter(::ok).take(levels), bids = raw.bids.filter(::ok).take(levels))
        }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1.7f).fillMaxHeight().background(Q.Panel).padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Chips("Livro", listOf("CONSOLIDADO") + Venue.entries.map { it.label.uppercase() }, venueIdx) { venueIdx = it; SettingsStore.put("of.venue", it) }
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
                Chips("Centro", listOf("AUTO (mid ${px(s.price)})", "MANUAL"), if (manual) 1 else 0) { manual = it == 1; SettingsStore.put("of.manual", manual) }
                if (manual) {
                    NumField("Faixa mín", rMin) { rMin = it; SettingsStore.put("of.rmin", it ?: 0.0) }
                    NumField("Faixa máx", rMax) { rMax = it; SettingsStore.put("of.rmax", it ?: 0.0) }
                }
                if (book.missing.isNotEmpty()) T("Fora da consolidação (livro inválido): " + book.missing.joinToString { it.label }, Q.Sell, 11)
            }
            Spacer(Modifier.height(8.dp))
            Ladder(book.asks, book.bids, rpi, INTS[intIdx], s.tick)
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
                    KV("Spread", if (bb != null && ba != null) num(ba - bb, 1) + " USDT" else "—")
                }
            }
            Venue.entries.forEach { v -> s.books[v]?.let { BookCard(it, s) } }
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
            KV("Sequência", if (b.valid) "OK" else "INVÁLIDO: ${b.note}", if (b.valid) Q.Buy else Q.Sell)
            KV("Níveis válidos (bid/ask)", if (b.valid) "${b.bidLevels} / ${b.askLevels}" else "—")
            KV("Última atualização", if (b.lastUpdateMs > 0) ago(s.now - b.lastUpdateMs) + " atrás" else "—")
            KV("Latência (evento → recebido)", "${b.latencyMs} ms")
            KV("Último update id", b.lastSeq.toString())
            KV("Reconstruções", b.resyncs.toString())
        }
    }
}

@Composable
private fun Ladder(asks: List<ConsRow>, bids: List<ConsRow>, showRpi: Boolean, intensity: Float, tick: Long) {
    val max = ((asks + bids).maxOfOrNull { it.total } ?: 0.0).coerceAtLeast(1e-9)
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        T("PREÇO", Q.Muted, 10, modifier = Modifier.width(92.dp))
        T("QUANTIDADE (BTC)", Q.Muted, 10, modifier = Modifier.weight(1f))
        listOf("BIN", "BYB", "OKX").forEach { T(it, Q.Muted, 10, modifier = Modifier.width(62.dp)) }
        if (showRpi) T("RPI", Q.Muted, 10, modifier = Modifier.width(56.dp))
        T("TOTAL", Q.Muted, 10, modifier = Modifier.width(76.dp))
        T("ACUMULADO", Q.Muted, 10, modifier = Modifier.width(84.dp))
    }
    val list = rememberLazyListState()
    var centered by remember { mutableStateOf(false) }
    LaunchedEffect(asks.size, bids.size) {
        if (!centered && asks.isNotEmpty()) { list.scrollToItem((asks.size - 12).coerceAtLeast(0)); centered = true }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        items(asks.reversed()) { LadderRow(it, max, showRpi, intensity) }
        item {
            val bb = bids.firstOrNull()?.price; val ba = asks.firstOrNull()?.price
            Row(Modifier.fillMaxWidth().background(Q.Panel2).padding(vertical = 3.dp), horizontalArrangement = Arrangement.Center) {
                T(if (bb != null && ba != null) "spread ${num(ba - bb, 1)}  ·  mid ${px((ba + bb) / 2)}" else "sem livro válido", Q.Accent, 11)
            }
        }
        items(bids) { LadderRow(it, max, showRpi, intensity) }
    }
}

@Composable
private fun LadderRow(r: ConsRow, max: Double, showRpi: Boolean, intensity: Float) {
    val buy = r.side == BookSide.BID
    val base = if (buy) Q.Buy else Q.Sell
    val frac = ((r.total / max).toFloat() * intensity).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().height(17.dp), verticalAlignment = Alignment.CenterVertically) {
        T(px(r.price), base, 11, monoFont = true, modifier = Modifier.width(92.dp))
        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp)) {
            if (frac > 0.003f) Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(base.copy(alpha = 0.25f + 0.6f * frac)))
        }
        for (i in 0..2) T(if (r.byVenue[i] > 0) num(r.byVenue[i], 3) else "·", if (r.byVenue[i] > 0) Q.Text else Q.Line, 11, monoFont = true, modifier = Modifier.width(62.dp))
        if (showRpi) T(if (r.byVenue[3] > 0) num(r.byVenue[3], 3) else "·", Q.Muted, 11, monoFont = true, modifier = Modifier.width(56.dp))
        T(num(r.total, 3), Q.Text, 11, bold = frac > 0.5f, monoFont = true, modifier = Modifier.width(76.dp))
        T(num(r.cumulative, 2), Q.Muted, 11, monoFont = true, modifier = Modifier.width(84.dp))
    }
}
