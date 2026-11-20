package com.cunhac.btcradar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.cunhac.btcradar.engine.ConnState
import com.cunhac.btcradar.engine.LIQ_PERIODS
import com.cunhac.btcradar.engine.LiqClass
import com.cunhac.btcradar.engine.LiqEvent
import com.cunhac.btcradar.engine.LiqSide
import com.cunhac.btcradar.engine.LogLevel
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.Venue
import java.lang.management.ManagementFactory

private val ZOOMS = listOf("±1%" to 0.01, "±2%" to 0.02, "±5%" to 0.05, "±10%" to 0.10)

@Composable
fun LiqTab(ctx: AppContext, s: TerminalState) {
    val r = ctx.hub.liqs
    var pIdx by remember { mutableIntStateOf(SettingsStore.i("lq.period", 3)) }
    var zIdx by remember { mutableIntStateOf(SettingsStore.i("lq.zoom", 1)) }
    var ver by remember { mutableIntStateOf(0) }
    val period = LIQ_PERIODS[pIdx].second
    val tot = remember(s.tick, pIdx) { r.totals(period) }
    val topL = remember(s.tick, pIdx, ver) { r.top(period, LiqSide.LONG, 30) }
    val topS = remember(s.tick, pIdx, ver) { r.top(period, LiqSide.SHORT, 30) }
    val topAll = remember(s.tick, pIdx, ver) { r.top(period, null, 30) }
    val price = s.price
    val grid = remember(s.tick, pIdx, zIdx) {
        if (price == null) null else r.heatmap(period, 60, 60, price * (1 - ZOOMS[zIdx].second), price * (1 + ZOOMS[zIdx].second))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.background(Q.Panel).padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Chips("Período", LIQ_PERIODS.map { it.first }, pIdx) { pIdx = it; SettingsStore.put("lq.period", it) }
            NumField("LARGE ≥", r.thresholds.large, 56.dp) { it?.let { v -> r.thresholds.large = v; SettingsStore.put("lq.large", v); ver++ } }
            NumField("EXTREME ≥", r.thresholds.extreme, 56.dp) { it?.let { v -> r.thresholds.extreme = v; SettingsStore.put("lq.extreme", v); ver++ } }
            T("BTC", Q.Muted, 11)
            Spacer(Modifier.width(10.dp))
            T("LONG ${btc(tot.longBtc, 3)} (${tot.longCount})", Q.LiqLong, 12, bold = true)
            T("SHORT ${btc(tot.shortBtc, 3)} (${tot.shortCount})", Q.LiqShort, 12, bold = true)
            T(tot.byVenue.entries.joinToString("  ") { "${it.key.label} ${num(it.value, 3)}" }, Q.Muted, 11)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiqTable("TOP LONG LIQUIDATIONS", topL, r.thresholds::classify, Modifier.weight(1f))
            LiqTable("TOP SHORT LIQUIDATIONS", topS, r.thresholds::classify, Modifier.weight(1f))
            LiqTable("TOP LIQUIDATIONS OVERALL", topAll, r.thresholds::classify, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.height(300.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Liquidation heatmap · somente eventos REPORTADOS (estimativas ficam em MAGNET ZONES)", Modifier.weight(2f).fillMaxHeight()) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chips("Zoom de preço", ZOOMS.map { it.first }, zIdx) { zIdx = it; SettingsStore.put("lq.zoom", it) }
                        T("■ LONG", Q.LiqLong, 11); T("■ SHORT", Q.LiqShort, 11)
                        grid?.let { T("${px(it.pMin)} → ${px(it.pMin + it.pStep * it.rows)} · máx/célula ${num(it.max, 3)} BTC", Q.Muted, 10) }
                    }
                    Canvas(Modifier.fillMaxSize().padding(top = 6.dp).background(Q.Bg)) {
                        val g = grid ?: return@Canvas
                        if (g.max <= 0) return@Canvas
                        val cw = size.width / g.cols; val rh = size.height / g.rows
                        for (row in 0 until g.rows) for (col in 0 until g.cols) {
                            val y = size.height - (row + 1) * rh
                            val l = g.longBtc[row][col]; val sh = g.shortBtc[row][col]
                            if (l > 0) drawRect(Q.LiqLong.copy(alpha = (0.25 + 0.75 * l / g.max).toFloat().coerceAtMost(1f)), Offset(col * cw, y), Size(cw, rh))
                            if (sh > 0) drawRect(Q.LiqShort.copy(alpha = (0.25 + 0.75 * sh / g.max).toFloat().coerceAtMost(1f)), Offset(col * cw, y), Size(cw, rh))
                        }
                        price?.let { p ->
                            val y = size.height - ((p - g.pMin) / (g.pStep * g.rows) * size.height).toFloat()
                            drawLine(Q.Accent, Offset(0f, y), Offset(size.width, y), 1f)
                        }
                    }
                }
            }
            Section("Alertas", Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn { items(s.alerts) { T(it, if (it.contains("EXTREME")) Q.Accent else Q.Text, 11, monoFont = true) } }
            }
        }
        T("Cobertura: os feeds públicos não garantem todos os eventos. Binance envia no máximo 1 liquidação/s por símbolo; OKX pode entregar eventos com atraso. " +
            "Rankings ordenados por BTC equivalente; a OKX é convertida de contratos via metadados (${s.okxCtVal}).", Q.Muted, 10, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LiqTable(title: String, list: List<LiqEvent>, cls: (Double) -> LiqClass, modifier: Modifier) {
    Section(title, modifier.fillMaxHeight()) {
        Column {
            Th("HORA" to 64, "CORR." to 58, "POSIÇÃO" to 58, "PREÇO" to 84, "BTC" to 70, "ORIGINAL" to 0)
            LazyColumn {
                items(list) { e ->
                    val c = if (e.side == LiqSide.LONG) Q.LiqLong else Q.LiqShort
                    val k = cls(e.qtyBtc)
                    Td(Triple(hhmmss(e.timeMs), 64, Q.Muted), Triple(e.venue.label, 58, Q.Text), Triple(e.side.name, 58, c), Triple(px(e.price), 84, Q.Text),
                        Triple(num(e.qtyBtc, 3) + if (k == LiqClass.EXTREME) " !!" else if (k == LiqClass.LARGE) " !" else "", 70, c),
                        Triple("${num(e.origQty, 3)} ${e.origUnit}", 0, Q.Muted))
                }
            }
        }
    }
}

// =====================================================================
// STATUS / DIAGNÓSTICO
// =====================================================================
@Composable
fun StatusTab(ctx: AppContext, s: TerminalState) {
    val logs = remember(s.tick) { ctx.hub.logs(250) }
    val counts = remember(s.tick / 7) { ctx.db.counts() }
    val rt = Runtime.getRuntime()
    val os = ManagementFactory.getOperatingSystemMXBean()
    val cpu = (os as? com.sun.management.OperatingSystemMXBean)?.processCpuLoad?.let { it * 100 }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Conexões (LIVE = handshake + inscrição + dados recentes)", Modifier.fillMaxWidth()) {
                Column {
                    Th("CORRETORA" to 70, "CANAL" to 0, "ESTADO" to 80, "ÚLTIMA MSG" to 80, "MENSAGENS" to 90, "RECONEX." to 64, "ÚLTIMO ERRO" to 260)
                    s.statuses.forEach { r ->
                        val c = when (r.state) { ConnState.LIVE -> Q.Buy; ConnState.DOWN -> Q.Sell; else -> Q.Warn }
                        Td(Triple(r.venue.label, 70, Q.Text), Triple(r.channel, 0, Q.Muted), Triple(r.state.name, 80, c), Triple(ago(r.lastMsgAgoMs), 80, Q.Text),
                            Triple(String.format(BR, "%,d", r.messages), 90, Q.Text), Triple(r.reconnects.toString(), 64, Q.Text), Triple(r.lastError ?: "—", 260, if (r.lastError != null) Q.Sell else Q.Muted))
                    }
                }
            }
            Section("Livros", Modifier.fillMaxWidth()) {
                Column {
                    Th("LIVRO" to 90, "VÁLIDO" to 70, "NÍVEIS BID/ASK" to 120, "LATÊNCIA" to 80, "RECONSTRUÇÕES" to 110, "NOTA" to 0)
                    (s.books.values + listOfNotNull(s.rpi)).forEachIndexed { i, b ->
                        Td(Triple(if (i == 3) "Bybit RPI" else b.venue.label, 90, Q.Text), Triple(if (b.valid) "SIM" else "NÃO", 70, if (b.valid) Q.Buy else Q.Sell),
                            Triple("${b.bidLevels} / ${b.askLevels}", 120, Q.Text), Triple("${b.latencyMs} ms", 80, Q.Text), Triple(b.resyncs.toString(), 110, Q.Text), Triple(b.note, 0, Q.Muted))
                    }
                }
            }
            Section("Log ao vivo (também gravado em ${AppDirs.logs.path})", Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn {
                    items(logs) { l ->
                        val c = when (l.level) { LogLevel.ERROR -> Q.Sell; LogLevel.WARN -> Q.Warn; else -> Q.Muted }
                        T("${hhmmss(l.timeMs)} ${l.level.name.padEnd(5)} ${(l.venue?.label ?: "app").padEnd(8)} ${l.text}", c, 11, monoFont = true)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Desempenho", Modifier.fillMaxWidth()) {
                Column {
                    KV("Mensagens/s (todas as fontes)", num(s.msgsPerSec, 0))
                    KV("Trades recebidos", String.format(BR, "%,d", s.tradesTotal))
                    KV("Liquidações recebidas", String.format(BR, "%,d", s.liqTotal))
                    KV("Paredes ativas", ctx.hub.walls.activeCount().toString())
                    KV("CPU do processo", cpu?.let { num(it, 1) + " %" } ?: "—")
                    KV("Memória (usada / máx.)", "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} / ${rt.maxMemory() / 1_048_576} MB")
                    KV("Threads", Thread.activeCount().toString())
                    KV("Tempo de execução", ago(System.currentTimeMillis() - ctx.hub.startedAt))
                    KV("Atualização da tela", "${ctx.hub.refreshMs} ms")
                }
            }
            Section("Histórico (SQLite)", Modifier.fillMaxWidth()) {
                Column {
                    KV("Arquivo", AppDirs.db.path)
                    KV("Estado", ctx.db.error ?: "OK", if (ctx.db.error == null) Q.Buy else Q.Sell)
                    KV("Gravados nesta sessão", String.format(BR, "%,d", ctx.db.written))
                    KV("Na fila", ctx.db.pending.toString())
                    counts.forEach { (k, v) -> KV("Tabela $k", String.format(BR, "%,d linhas", v)) }
                    T("Nada é apagado automaticamente.", Q.Muted, 10)
                }
            }
            Section("Fontes e conversões", Modifier.fillMaxWidth()) {
                Column {
                    T("Binance USD-M: /public depth@100ms + snapshot REST 1000; /market aggTrade, markPrice@1s, forceOrder; OI REST 15 s.", Q.Muted, 10)
                    T("Bybit linear: orderbook.1000 (200 ms), orderbook.rpi (50 níveis), publicTrade, tickers, allLiquidation.", Q.Muted, 10)
                    T("OKX: books (400 níveis, seqId/prevSeqId), trades, open-interest, funding-rate, mark-price, liquidation-orders.", Q.Muted, 10)
                    T("OKX: ${s.okxCtVal}. Binance e Bybit já publicam em BTC.", Q.Muted, 10)
                }
            }
        }
    }
}
