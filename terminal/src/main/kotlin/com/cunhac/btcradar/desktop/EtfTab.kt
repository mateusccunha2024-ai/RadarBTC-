package com.cunhac.btcradar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.data.model.EtfDay
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Fluxo em milhões de dólares (fonte publica em US$ mi). */
private fun mi(v: Double) = String.format(BR, "US$ %+,.1f mi", v)
private val DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val DM = DateTimeFormatter.ofPattern("dd/MM")

/** ETFs spot de BTC nos EUA: entradas e saídas diárias por fundo (Farside). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EtfTab(ctx: AppContext) {
    val m = ctx.etf
    val s by m.state.collectAsState()
    val latest = s.latest
    if (latest == null) {
        Section("ETFs spot de BTC (EUA)", if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp)) {
                if (s.loading) Empty("Carregando os fluxos dos ETFs…", "Fonte: tabela pública diária da Farside Investors")
                else {
                    Empty("Fluxos indisponíveis no momento", s.error ?: "Sem dados")
                    Box(Modifier.padding(start = 12.dp, top = 8.dp)) { OutlineButton("Tentar de novo") { m.load() } }
                }
            }
        }
        return
    }
    val funds = latest.flows.entries.sortedByDescending { abs(it.value) }.map { it.key to it.value }
    Panes(Modifier.fillMaxSize(), Pane(1.3f, null, "Fluxo diário", 480.dp) { pm ->
        Column(pm, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Último pregão · " + latest.date.format(DMY), Modifier.fillMaxWidth()) {
                Column {
                    T(mi(latest.total), if (latest.total >= 0) Q.Buy else Q.Sell, 30, bold = true, monoFont = true)
                    T(if (latest.total >= 0) "entrada líquida no dia" else "saída líquida no dia", Q.Muted, 12)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 20, 60).forEach { n ->
                            Column {
                                T("$n pregões", Q.Muted, 11)
                                val v = s.sum(n)
                                T(mi(v), if (v >= 0) Q.Buy else Q.Sell, 15, bold = true, monoFont = true)
                            }
                        }
                    }
                }
            }
            Section("Fluxo diário · últimos 60 pregões", Modifier.fillMaxWidth().then(grow(1f, 300.dp))) {
                FlowBars(s.days.take(60).reversed(), Modifier.fillMaxSize())
            }
            Footnote("Valores em milhões de dólares, como publicados pela Farside Investors (atualiza depois do fechamento do mercado americano). " +
                "Barras verdes = entrada líquida no dia; vermelhas = saída.")
        }
    }, Pane(1f, 520.dp, "Por fundo", 320.dp) { m2 ->
        Section("Por fundo no último pregão", m2) {
            LazyColumn {
                items(funds, key = { it.first }) { (name, v) ->
                    Row(Modifier.fillMaxWidth().height(26.dp)) {
                        T(name, Q.Text, 13, monoFont = true, modifier = Modifier.width(90.dp.f))
                        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            val mx = (funds.maxOfOrNull { abs(it.second) } ?: 0.0).coerceAtLeast(1e-9)
                            if (v != 0.0) Box(Modifier.fillMaxWidth((abs(v) / mx).toFloat().coerceIn(0.02f, 1f)).height(10.dp)
                                .background((if (v >= 0) Q.Buy else Q.Sell).copy(alpha = 0.6f)))
                        }
                        T(if (v == 0.0) "—" else mi(v), if (v == 0.0) Q.Muted else if (v > 0) Q.Buy else Q.Sell, 12, monoFont = true,
                            modifier = Modifier.width(120.dp.f).padding(start = 8.dp))
                    }
                }
            }
        }
    })
}

@Composable
private fun FlowBars(days: List<EtfDay>, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val buy = Q.Buy; val sell = Q.Sell; val line = Q.Line
    val st = TextStyle(color = Q.Muted, fontSize = 10.sp)
    Canvas(modifier) {
        if (days.isEmpty()) return@Canvas
        val axisH = 18.dp.toPx() * fontScale
        val h = size.height - axisH
        val max = (days.maxOfOrNull { abs(it.total) } ?: 0.0).coerceAtLeast(1.0)
        val mid = h / 2
        val slot = size.width / days.size
        val w = slot * 0.7f
        drawLine(line, Offset(0f, mid), Offset(size.width, mid), 1f)
        days.forEachIndexed { i, d ->
            val bh = (abs(d.total) / max * (mid - 2)).toFloat()
            drawRect(if (d.total >= 0) buy else sell, Offset(i * slot + (slot - w) / 2, if (d.total >= 0) mid - bh else mid), Size(w, bh))
        }
        val every = (days.size / 6).coerceAtLeast(1)
        for (i in days.indices step every) drawText(tm, days[i].date.format(DM), Offset(i * slot, h + 2.dp.toPx()), st)
        drawText(tm, mi(max), Offset(2f, 2f), st)
    }
}
