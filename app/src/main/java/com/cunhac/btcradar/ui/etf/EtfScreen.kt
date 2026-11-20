package com.cunhac.btcradar.ui.etf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cunhac.btcradar.data.model.EtfDay
import com.cunhac.btcradar.ui.components.Label
import com.cunhac.btcradar.ui.components.LoadingOrError
import com.cunhac.btcradar.ui.components.Stat
import com.cunhac.btcradar.ui.components.fmtMillions
import com.cunhac.btcradar.ui.theme.C
import com.cunhac.btcradar.ui.theme.Mono
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private fun flowColor(v: Double): Color = if (v >= 0) C.Up else C.Down

@Composable
fun EtfScreen(vm: EtfViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val latest = s.latest
    if (latest == null) {
        LoadingOrError(s.loading, s.error ?: if (!s.loading) "Sem dados" else null) { vm.load() }
        return
    }
    val funds = latest.flows.entries.sortedByDescending { abs(it.value) }.map { it.key to it.value }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Label("ETFs spot de BTC (EUA) · " + latest.date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
        Text(fmtMillions(latest.total), color = flowColor(latest.total), fontSize = 30.sp)
        Text(if (latest.total >= 0) "entrada líquida no dia" else "saída líquida no dia", color = C.Muted, fontSize = 12.sp)

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
                val ticker = pair.first
                val v = pair.second
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ticker, style = Mono, fontSize = 13.sp)
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
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
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
