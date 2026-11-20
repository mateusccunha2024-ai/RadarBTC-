package com.cunhac.btcradar.ui.liquidations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cunhac.btcradar.data.model.Side
import com.cunhac.btcradar.ui.components.Chips
import com.cunhac.btcradar.ui.components.Label
import com.cunhac.btcradar.ui.components.Stat
import com.cunhac.btcradar.ui.components.fmtPrice
import com.cunhac.btcradar.ui.components.fmtUsd
import com.cunhac.btcradar.ui.theme.C
import com.cunhac.btcradar.ui.theme.Mono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiquidationsScreen(vm: LiquidationsViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val total = s.longUsd1h + s.shortUsd1h

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Label("Liquidações · última hora (desde que o app abriu)")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat("Longs", fmtUsd(s.longUsd1h), C.Down)
            Stat("Shorts", fmtUsd(s.shortUsd1h), C.Up)
            s.largest1h?.let { Stat("Maior", fmtUsd(it.usd), if (it.side == Side.LONG) C.Down else C.Up) }
        }

        // barra de proporção long × short
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
        Chips(listOf("Todas", "> US$ 10K", "> US$ 100K"), s.minIndex) { vm.setMin(it) }
        Spacer(Modifier.height(8.dp))

        if (s.recent.isEmpty()) {
            Text("Aguardando liquidações ao vivo…", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn {
            items(s.recent) { l ->
                val color = if (l.side == Side.LONG) C.Down else C.Up
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Text(timeFmt.format(Date(l.timeMs)), style = Mono, color = C.Muted, modifier = Modifier.width(70.dp))
                    Text(if (l.side == Side.LONG) "LONG" else "SHORT", style = Mono, color = color, modifier = Modifier.width(52.dp))
                    Text(l.exchange.label, color = C.Muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                    Text(fmtPrice(l.price), style = Mono, modifier = Modifier.weight(1f))
                    Text(fmtUsd(l.usd), style = Mono, color = color)
                }
                HorizontalDivider(color = C.Line, thickness = 0.5.dp)
            }
        }
    }
}
