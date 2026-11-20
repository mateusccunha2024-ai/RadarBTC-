package com.cunhac.btcradar.ui.map

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cunhac.btcradar.data.model.PriceBin
import com.cunhac.btcradar.ui.components.Chips
import com.cunhac.btcradar.ui.components.Label
import com.cunhac.btcradar.ui.components.LoadingOrError
import com.cunhac.btcradar.ui.components.Stat
import com.cunhac.btcradar.ui.components.fmtPct
import com.cunhac.btcradar.ui.components.fmtPrice
import com.cunhac.btcradar.ui.components.fmtUsd
import com.cunhac.btcradar.ui.theme.C
import com.cunhac.btcradar.ui.theme.Mono

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val stats = s.stats
    if (stats == null) { LoadingOrError(s.loading, s.error) { vm.start() }; return }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Cabeçalho: preço e métricas do perpétuo
        Spacer(Modifier.height(12.dp))
        Label("BTC/USDT perpétuo")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtPrice(stats.price), color = C.Text, fontSize = 30.sp)
            Spacer(Modifier.width(8.dp))
            Text(fmtPct(stats.change24hPct), color = if (stats.change24hPct >= 0) C.Up else C.Down, fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 5.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat("Funding", fmtPct(stats.fundingRate * 100, 4), if (stats.fundingRate >= 0) C.Up else C.Down)
            Stat("OI Binance", fmtUsd(stats.openInterestBtc * stats.price))
        }

        // Ímãs: para onde a liquidez tende a puxar
        Spacer(Modifier.height(16.dp))
        Label("Zonas de atração")
        Spacer(Modifier.height(6.dp))
        s.magnets.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (m.above) "▲ ${fmtPrice(m.bin.mid)}" else "▼ ${fmtPrice(m.bin.mid)}",
                    color = if (m.above) C.Up else C.Down, style = Mono, fontSize = 14.sp)
                Text("${fmtPct(m.distancePct, 1)} · ${fmtUsd(m.bin.liqUsd)} em liquidações", color = C.Muted, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        Chips(listOf("±2%", "±5%", "±10%"), s.rangeIndex) { vm.setRange(it) }
        Spacer(Modifier.height(10.dp))
        Row {
            Text("Preço", color = C.Muted, fontSize = 11.sp, modifier = Modifier.width(64.dp))
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
    LaunchedEffect(current, bins.size) { if (current > 6) list.scrollToItem(current - 6) }

    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        items(bins, key = { it.low }) { b ->
            val isNow = b.contains(price)
            Row(
                Modifier.fillMaxWidth().height(18.dp)
                    .background(if (isNow) C.Line else C.Bg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmtPrice(b.low), style = Mono, fontSize = 11.sp,
                    color = if (isNow) C.Accent else C.Muted, modifier = Modifier.width(64.dp))
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
