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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.data.model.EtfDay
import com.cunhac.btcradar.data.model.PriceBin
import com.cunhac.btcradar.data.model.Side
import com.cunhac.btcradar.ui.components.Chips
import com.cunhac.btcradar.ui.components.Label
import com.cunhac.btcradar.ui.components.LoadingOrError
import com.cunhac.btcradar.ui.components.Stat
import com.cunhac.btcradar.ui.components.fmtMillions
import com.cunhac.btcradar.ui.components.fmtPct
import com.cunhac.btcradar.ui.components.fmtPrice
import com.cunhac.btcradar.ui.components.fmtUsd
import com.cunhac.btcradar.ui.theme.C
import com.cunhac.btcradar.ui.theme.Mono
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun PanelTitle(text: String) {
    Text(text, color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

// ---------- Mapa ----------

@Composable
fun MapPanel(m: MapModel) {
    val s by m.state.collectAsState()
    val stats = s.stats
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PanelTitle("Mapa de liquidez")
        if (stats == null) { LoadingOrError(s.loading, s.error) { m.start() }; return@Column }

        Label("BTC/USDT perpétuo")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtPrice(stats.price), color = C.Text, fontSize = 34.sp)
            Spacer(Modifier.width(10.dp))
            Text(fmtPct(stats.change24hPct), color = if (stats.change24hPct >= 0) C.Up else C.Down, fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat("Funding", fmtPct(stats.fundingRate * 100, 4), if (stats.fundingRate >= 0) C.Up else C.Down)
            Stat("OI Binance", fmtUsd(stats.openInterestBtc * stats.price))
        }

        Spacer(Modifier.height(16.dp))
        Label("Zonas de atração")
        Spacer(Modifier.height(6.dp))
        s.magnets.forEach { mg ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (mg.above) "▲ ${fmtPrice(mg.bin.mid)}" else "▼ ${fmtPrice(mg.bin.mid)}",
                    color = if (mg.above) C.Up else C.Down, style = Mono, fontSize = 14.sp)
                Text("${fmtPct(mg.distancePct, 1)} · ${fmtUsd(mg.bin.liqUsd)} em liquidações", color = C.Muted, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        Chips(listOf("±2%", "±5%", "±10%"), s.rangeIndex) { m.setRange(it) }
        Spacer(Modifier.height(10.dp))
        Row {
            Text("Preço", color = C.Muted, fontSize = 11.sp, modifier = Modifier.width(72.dp))
            Text("Liquidações est.", color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Book (${s.booksOk.joinToString { it.label }})", color = C.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        }
        Ladder(s.bins, stats.price)
    }
}

@Composable
private fun Ladder(bins: List<PriceBin>, price: Double) {
    val maxLiq = (bins.maxOfOrNull { it.liqUsd } ?: 0.0).coerceAtLeast(1.0)
    val maxBook = (bins.maxOfOrNull { it.bookUsd } ?: 0.0).coerceAtLeast(1.0)
    val current = bins.indexOfFirst { it.contains(price) }
    val list = rememberLazyListState()
    LaunchedEffect(current, bins.size) { if (current > 12) list.scrollToItem(current - 12) }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        items(bins, key = { it.low }) { b ->
            val isNow = b.contains(price)
            Row(
                Modifier.fillMaxWidth().height(18.dp).background(if (isNow) C.Line else C.Bg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmtPrice(b.low), style = Mono, fontSize = 11.sp,
                    color = if (isNow) C.Accent else C.Muted, modifier = Modifier.width(72.dp))
                Bar((b.liqUsd / maxLiq).toFloat(), if (b.low >= price) C.Up else C.Down, Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Bar((b.bookUsd / maxBook).toFloat(), if (b.askUsd >= b.bidUsd) C.Up.copy(alpha = .45f) else C.Down.copy(alpha = .45f), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier) {
    Box(modifier.fillMaxHeight().padding(vertical = 3.dp)) {
        if (fraction > 0.005f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

// ---------- Liquidações ----------

@Composable
fun LiqPanel(m: LiqModel) {
    val s by m.state.collectAsState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val total = s.longUsd1h + s.shortUsd1h

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PanelTitle("Liquidações ao vivo")
        Label("Última hora · desde que o app abriu")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat("Longs", fmtUsd(s.longUsd1h), C.Down)
            Stat("Shorts", fmtUsd(s.shortUsd1h), C.Up)
            s.largest1h?.let { Stat("Maior", fmtUsd(it.usd), if (it.side == Side.LONG) C.Down else C.Up) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(4.dp).background(C.Surface)) {
            if (total > 0) {
                val f = (s.longUsd1h / total).toFloat().coerceIn(0.001f, 0.999f)
                Box(Modifier.weight(f).height(4.dp).background(C.Down))
                Box(Modifier.weight(1 - f).height(4.dp).background(C.Up))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            s.byExchange1h.entries.sortedByDescending { it.value }.joinToString("   ") { "${it.key.label} ${fmtUsd(it.value)}" },
            color = C.Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))
        Chips(listOf("Todas", "> US$ 10K", "> US$ 100K"), s.minIndex) { m.setMin(it) }
        Spacer(Modifier.height(8.dp))
        if (s.recent.isEmpty()) {
            Text("Aguardando liquidações ao vivo…", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn {
            items(s.recent) { l ->
                val color = if (l.side == Side.LONG) C.Down else C.Up
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(timeFmt.format(Date(l.timeMs)), style = Mono, color = C.Muted, modifier = Modifier.width(72.dp))
                    Text(if (l.side == Side.LONG) "LONG" else "SHORT", style = Mono, color = color, modifier = Modifier.width(56.dp))
                    Text(l.exchange.label, color = C.Muted, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                    Text(fmtPrice(l.price), style = Mono, modifier = Modifier.weight(1f))
                    Text(fmtUsd(l.usd), style = Mono, color = color)
                }
                HorizontalDivider(color = C.Line, thickness = 0.5.dp)
            }
        }
    }
}

// ---------- ETFs ----------

private fun flowColor(v: Double): Color = if (v >= 0) C.Up else C.Down

@Composable
fun EtfPanel(m: EtfModel) {
    val s by m.state.collectAsState()
    val latest = s.latest
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PanelTitle("ETFs spot de BTC (EUA)")
        if (latest == null) {
            LoadingOrError(s.loading, s.error ?: if (!s.loading) "Sem dados" else null) { m.load() }
            return@Column
        }
        val funds = latest.flows.entries.sortedByDescending { abs(it.value) }.map { it.key to it.value }

        Label("Último pregão · " + latest.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
        Text(fmtMillions(latest.total), color = flowColor(latest.total), fontSize = 34.sp)
        Text(if (latest.total >= 0) "entrada líquida no dia" else "saída líquida no dia", color = C.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat("5 dias", fmtMillions(s.sum(5)), flowColor(s.sum(5)))
            Stat("20 dias", fmtMillions(s.sum(20)), flowColor(s.sum(20)))
        }
        Spacer(Modifier.height(16.dp))
        Label("Fluxo diário · 30 pregões")
        Spacer(Modifier.height(6.dp))
        FlowBars(s.days.take(30).reversed())
        Spacer(Modifier.height(16.dp))
        Label("Por fundo no dia")
        LazyColumn {
            items(funds, key = { it.first }) { pair ->
                val v = pair.second
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(pair.first, style = Mono, fontSize = 13.sp)
                    Text(if (v == 0.0) "—" else fmtMillions(v), style = Mono, fontSize = 13.sp,
                        color = if (v == 0.0) C.Muted else flowColor(v))
                }
                HorizontalDivider(color = C.Line, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun FlowBars(days: List<EtfDay>) {
    val max = (days.maxOfOrNull { abs(it.total) } ?: 0.0).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        if (days.isEmpty()) return@Canvas
        val mid = size.height / 2
        val slot = size.width / days.size
        val w = slot * 0.7f
        drawLine(C.Line, Offset(0f, mid), Offset(size.width, mid), 1f)
        days.forEachIndexed { i, d ->
            val h = (abs(d.total) / max * mid).toFloat()
            val top = if (d.total >= 0) mid - h else mid
            drawRect(flowColor(d.total), Offset(i * slot + (slot - w) / 2, top), Size(w, h))
        }
    }
}
