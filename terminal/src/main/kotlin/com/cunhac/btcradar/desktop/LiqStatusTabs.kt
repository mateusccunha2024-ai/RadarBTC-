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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.cunhac.btcradar.engine.ConnState
import com.cunhac.btcradar.engine.FeedAlert
import com.cunhac.btcradar.engine.LIQ_PERIODS
import com.cunhac.btcradar.engine.LIQ_VENUES
import com.cunhac.btcradar.engine.LiqClass
import com.cunhac.btcradar.engine.LiqEvent
import com.cunhac.btcradar.engine.LiqSide
import com.cunhac.btcradar.engine.LogLevel
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.Venue

private val ZOOMS = listOf("±1%" to 0.01, "±2%" to 0.02, "±5%" to 0.05, "±10%" to 0.10)

@Composable
fun LiqTab(ctx: AppContext, s: TerminalState) {
    val r = ctx.hub.liqs
    var pIdx by remember { mutableIntStateOf(SettingsStore.i("lq.period", 3)) }
    var ver by remember { mutableIntStateOf(0) }
    val period = LIQ_PERIODS[pIdx].second
    val pl = LIQ_PERIODS[pIdx].first
    val tot = remember(s.tick, pIdx) { r.totals(period) }
    val topL = remember(s.tick, pIdx, ver) { r.top(period, LiqSide.LONG, 30) }
    val topS = remember(s.tick, pIdx, ver) { r.top(period, LiqSide.SHORT, 30) }
    val topAll = remember(s.tick, pIdx, ver) { r.top(period, null, 30) }
    // "Ampliar gráfico" (só no PC): esconde as tabelas de baixo para o mapa ocupar a aba inteira.
    var big by remember { mutableStateOf(SettingsStore.b("lq.big", false)) }
    val expanded = big && !compact
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LiqHeatmapPanel(ctx, s, Modifier.fillMaxWidth().then(grow(if (expanded) 1f else 2f)), expanded,
            if (compact) null else ({ big = !big; SettingsStore.put("lq.big", big) }))
        if (!expanded) {
        Controls {
            T("Liquidações reais", Q.Text, 14, bold = true)
            Chips("Período", LIQ_PERIODS.map { it.first }, pIdx) { pIdx = it; SettingsStore.put("lq.period", it) }
            NumField("LARGE ≥", r.thresholds.large, 56.dp) { it?.let { v -> r.thresholds.large = v; SettingsStore.put("lq.large", v); ver++ } }
            NumField("EXTREME ≥", r.thresholds.extreme, 56.dp) { it?.let { v -> r.thresholds.extreme = v; SettingsStore.put("lq.extreme", v); ver++ } }
            T("LONG ${btc(tot.longBtc, 3)} (${tot.longCount})", Q.LiqLong, 12, bold = true)
            T("SHORT ${btc(tot.shortBtc, 3)} (${tot.shortCount})", Q.LiqShort, 12, bold = true)
            LIQ_VENUES.forEach { v -> T("${v.label} ${num(tot.byVenue[v] ?: 0.0, 3)}", Q.Muted, 11) }
        }
        Panes(grow(1f),
            Pane(1f, 360.dp) { m -> LiqTable("Top LONG liquidadas · $pl", topL, r.thresholds::classify, m, "Nenhuma liquidação LONG de BTC em $pl · ${r.received} eventos desde a abertura") },
            Pane(1f, 360.dp) { m -> LiqTable("Top SHORT liquidadas · $pl", topS, r.thresholds::classify, m, "Nenhuma liquidação SHORT de BTC em $pl · ${r.received} eventos desde a abertura") },
            Pane(1f, 360.dp) { m -> LiqTable("Top geral · $pl", topAll, r.thresholds::classify, m, "Nenhuma liquidação de BTC em $pl") },
            Pane(1.1f, 480.dp) { m ->
            AlertFeed(s, m)
            })
        Footnote("Liquidações reais = Binance, Bybit, OKX, Gate, Bitget e Deribit (feeds públicos, não garantem todos os eventos; Binance envia no máximo 1/s e a Bitget agrupa por segundo). " +
            "Conversões para BTC: OKX ${s.okxCtVal}; Gate 1 contrato = 0,0001 BTC; Bitget USDT ÷ preço; Deribit USD ÷ preço. " +
            "Heatmap = estimativa a partir do open interest histórico de Binance, Bybit, OKX, Gate, HTX e Kraken.")
        }
    }
}

@Composable
private fun LiqTable(title: String, list: List<LiqEvent>, cls: (Double) -> LiqClass, modifier: Modifier, empty: String) {
    // Compacta para caber no painel do PC sem rolar para o lado; a coluna ORIGINAL quebra linha se precisar.
    Section(title, modifier.fillMaxHeight(), minWidth = 340.dp) {
        Column {
            Th("HORA" to 60, "CORR." to 58, "LADO" to 48, "PREÇO" to 72, "BTC" to 58, "ORIGINAL" to 0)
            if (list.isEmpty()) Empty(empty, "Aparecem aqui assim que alguma das 6 corretoras reportar")
            LazyColumn {
                items(list) { e ->
                    val c = if (e.side == LiqSide.LONG) Q.LiqLong else Q.LiqShort
                    val k = cls(e.qtyBtc)
                    Td(Triple(hhmmss(e.timeMs), 60, Q.Muted), Triple(e.venue.label, 58, Q.Text), Triple(e.side.name, 48, c), Triple(px(e.price), 72, Q.Text),
                        Triple(num(e.qtyBtc, 3) + if (k == LiqClass.EXTREME) " !!" else if (k == LiqClass.LARGE) " !" else "", 58, c),
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
    val stats = remember(s.tick / 2) { processStats() }
    Panes(Modifier.fillMaxSize(), Pane(1.4f) { pm ->
        Column(pm, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Conexões (Ao vivo = conexão + inscrição + dados recentes)", Modifier.fillMaxWidth(), minWidth = 900.dp) {
                Column {
                    Th("CORRETORA" to 70, "CANAL" to 0, "ESTADO" to 110, "ÚLTIMA MSG" to 80, "MENSAGENS" to 90, "RECONEX." to 64, "ÚLTIMO ERRO" to 260)
                    s.statuses.forEach { r ->
                        val c = when (r.state) { ConnState.LIVE -> Q.Buy; ConnState.DOWN -> Q.Sell; else -> Q.Warn }
                        Td(Triple(r.venue.label, 70, Q.Text), Triple(r.channel, 0, Q.Muted), Triple(r.state.pt(), 110, c), Triple(ago(r.lastMsgAgoMs), 80, Q.Text),
                            Triple(String.format(BR, "%,d", r.messages), 90, Q.Text), Triple(r.reconnects.toString(), 64, Q.Text), Triple(r.lastError ?: "—", 260, if (r.lastError != null) Q.Sell else Q.Muted))
                    }
                }
            }
            Section("Livros", Modifier.fillMaxWidth(), minWidth = 640.dp) {
                Column {
                    Th("LIVRO" to 90, "VÁLIDO" to 70, "NÍVEIS BID/ASK" to 120, "LATÊNCIA" to 80, "RECONSTRUÇÕES" to 110, "NOTA" to 0)
                    (s.books.values + listOfNotNull(s.rpi)).forEachIndexed { i, b ->
                        Td(Triple(if (i == 3) "Bybit RPI" else b.venue.label, 90, Q.Text), Triple(if (b.valid) "SIM" else "NÃO", 70, if (b.valid) Q.Buy else Q.Sell),
                            Triple("${b.bidLevels} / ${b.askLevels}", 120, Q.Text), Triple("${b.latencyMs} ms", 80, Q.Text), Triple(b.resyncs.toString(), 110, Q.Text), Triple(b.note, 0, Q.Muted))
                    }
                }
            }
            Section("Log ao vivo (também gravado em ${AppDirs.logs.path})", Modifier.fillMaxWidth().then(grow(1f, 360.dp)), minWidth = if (compact) 760.dp else null) {
                LazyColumn {
                    items(logs) { l ->
                        val c = when (l.level) { LogLevel.ERROR -> Q.Sell; LogLevel.WARN -> Q.Warn; else -> Q.Muted }
                        T("${hhmmss(l.timeMs)} ${l.level.name.padEnd(5)} ${(l.venue?.label ?: "app").padEnd(8)} ${l.text}", c, 11, monoFont = true)
                    }
                }
            }
        }
    }, Pane(1f) { pm ->
        Column(pm, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Desempenho", Modifier.fillMaxWidth()) {
                Column {
                    KV("Mensagens/s (todas as fontes)", num(s.msgsPerSec, 0))
                    KV("Trades recebidos", String.format(BR, "%,d", s.tradesTotal))
                    KV("Liquidações recebidas", String.format(BR, "%,d", s.liqTotal))
                    KV("Paredes ativas", ctx.hub.walls.activeCount().toString())
                    stats.forEach { (k, v) -> KV(k, v) }
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
                    T("Binance USD-M: /public depth@100ms + snapshot REST 1000; /market aggTrade, markPrice@1s, forceOrder; OI REST 15 s.", Q.Muted, 10, wrap = true)
                    T("Bybit linear: orderbook.1000 (200 ms), orderbook.rpi (50 níveis), publicTrade, tickers, allLiquidation.", Q.Muted, 10, wrap = true)
                    T("OKX: books (400 níveis, seqId/prevSeqId), trades, open-interest, funding-rate, mark-price, liquidation-orders.", Q.Muted, 10, wrap = true)
                    T("OKX: ${s.okxCtVal}. Binance e Bybit já publicam em BTC.", Q.Muted, 10, wrap = true)
                    T("Liquidações extras: Gate (futures.public_liquidates), Bitget (UTA v3 liquidation), Deribit (trades com marca de liquidação).", Q.Muted, 10, wrap = true)
                }
            }
        }
    })
}

private val FEED_FILTERS = listOf("Todos", "Paredes", "Liquidações", "Execuções", "Divergências")

/**
 * Painel de alertas: uma linha de cabeçalho (hora, tipo, lado) e uma de dados (preço · corretora · números),
 * com faixa lateral na cor do lado e filtro por tipo. Antes era uma frase única cortada no meio.
 */
@Composable
private fun AlertFeed(s: TerminalState, modifier: Modifier) {
    var filter by remember { mutableIntStateOf(0) }
    val list = if (filter == 0) s.alerts else s.alerts.filter { it.kind.group == FEED_FILTERS[filter] }
    Section("Alertas · ${s.alerts.size}", modifier) {
        Column {
            Chips(null, FEED_FILTERS, filter) { filter = it }
            Spacer(Modifier.height(6.dp))
            if (list.isEmpty()) Empty(if (filter == 0) "Sem alertas por enquanto" else "Nenhum alerta deste tipo",
                "Absorção e iceberg em paredes, liquidações ≥ LARGE, execuções muito grandes e divergências preço × CVD aparecem aqui")
            else LazyColumn(if (compact) Modifier.heightIn(max = 520.dp) else Modifier.fillMaxSize()) {
                items(list) { AlertRow(it) }
            }
        }
    }
}

@Composable
private fun AlertRow(a: FeedAlert) {
    val side = when (a.buy) { true -> Q.Buy; false -> Q.Sell; null -> Q.Muted }
    Column(Modifier.fillMaxWidth()
        .drawBehind {
            drawRect(side, Offset(0f, 4.dp.toPx()), Size(2.dp.toPx(), size.height - 8.dp.toPx()))
            drawRect(Q.Line, Offset(0f, size.height - 1f), Size(size.width, 1f))
        }
        .padding(start = 10.dp, top = 5.dp, bottom = 6.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T(hhmmss(a.timeMs), Q.Muted, 11, monoFont = true)
            T(a.kind.label, if (a.strong) Q.Accent else Q.Text, 12, bold = true)
            T(a.sideLabel, side, 12, bold = true)
            if (a.count > 1) Badge("×${a.count}", Q.Muted)
        }
        val head = listOfNotNull(a.price?.let { px(it) }, a.where).joinToString("  ·  ")
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (head.isNotEmpty()) T(head, Q.Text, 12, monoFont = true, modifier = Modifier.padding(end = 8.dp))
        }
        T(a.detail, Q.Muted, 11, wrap = true)
    }
}
