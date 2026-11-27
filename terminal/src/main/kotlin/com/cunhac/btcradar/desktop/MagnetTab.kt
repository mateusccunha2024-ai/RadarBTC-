package com.cunhac.btcradar.desktop

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.data.model.PriceBin

/** Vermelho escuro (pouca liquidez estimada) até vermelho vivo (muita). */
fun heat(f: Float): Color = lerp(Q.HeatLow, Q.HeatHigh, f.coerceIn(0f, 1f))

private fun magnetReason(err: String?): String = when {
    err == null -> "Motivo desconhecido."
    err.contains("451") -> "A Binance recusou o acesso nesta região (HTTP 451). As outras abas não dependem disso."
    else -> err
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MagnetTab(m: MapModel) {
    val s by m.state.collectAsState()
    val stats = s.stats
    if (stats == null) {
        Section("Zonas ímã · mapa estimado de liquidações", if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(top = if (compact) 24.dp else 80.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (s.loading) {
                    T("Calculando o mapa a partir do histórico de posições…", Q.Text, 14, wrap = true, align = TextAlign.Center)
                    T("Leva alguns segundos: velas de 15 min + open interest dos últimos 5 dias.", Q.Muted, 12, modifier = Modifier.padding(top = 6.dp))
                } else {
                    T("Mapa indisponível no momento", Q.HeatHigh, 16, bold = true)
                    T(magnetReason(s.error), Q.Text, 12, wrap = true, align = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).widthIn(max = 620.dp))
                    T("O mapa estimado usa velas e histórico de open interest da Binance (REST).", Q.Muted, 11, wrap = true,
                        align = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).widthIn(max = 620.dp))
                    Box(Modifier.padding(top = 18.dp)) { OutlineButton("Tentar de novo", Q.HeatHigh) { m.start() } }
                }
            }
        }
        return
    }
    val price = stats.price
    Panes(Modifier.fillMaxSize(), Pane(0.4f, null, "Mercado e zonas", 380.dp) { pm ->
        Column(pm, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Mercado (Binance perp)", Modifier.fillMaxWidth()) {
                Column {
                    KV("Preço", px(price))
                    KV("Variação 24h", pct(stats.change24hPct), if (stats.change24hPct >= 0) Q.Buy else Q.Sell)
                    KV("Funding", pct(stats.fundingRate * 100, 4))
                    KV("Open interest", btc(stats.openInterestBtc, 0))
                }
            }
            Section("Zonas de atração", Modifier.fillMaxWidth()) {
                Column {
                    if (s.magnets.isEmpty()) Empty("Nenhum aglomerado relevante nesta faixa", "Amplie a faixa para ±10%")
                    s.magnets.forEach { mg ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(if (mg.above) "▲" else "▼", Q.HeatHigh, 14, modifier = Modifier.width(20.dp))
                            Column(Modifier.weight(1f)) {
                                T(px(mg.bin.mid), Q.Text, 16, bold = true, monoFont = true)
                                T((if (mg.above) "acima" else "abaixo") + " · " + pct(mg.distancePct, 2) + " do preço", Q.Muted, 11)
                            }
                            T(btc(mg.bin.liqUsd / price, 1), Q.HeatHigh, 13, bold = true, monoFont = true)
                        }
                    }
                }
            }
            Section("Como ler", Modifier.fillMaxWidth()) {
                Footnote("Estimativa, como no CoinGlass: quando o open interest sobe, simulamos posições 10x/25x/50x/100x e marcamos onde seriam liquidadas; " +
                    "níveis que o preço já atravessou saem do mapa. Quanto mais vivo o vermelho, mais BTC estimado no nível. Liquidações reais ficam no LIQUIDATION RADAR.")
            }
        }
    }, Pane(1f, 620.dp, "Mapa estimado", 480.dp) { m2 ->
        Section("Mapa de liquidações estimadas", m2) {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chips("Faixa", listOf("±2%", "±5%", "±10%"), s.rangeIndex) { m.setRange(it) }
                    T("menos", Q.Muted, 11)
                    Row { (0..5).forEach { Box(Modifier.width(18.dp).height(10.dp).background(heat(it / 5f))) } }
                    T("mais BTC estimado", Q.Muted, 11)
                    T("Book: " + s.booksOk.joinToString { it.label }, Q.Muted, 11)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                    T("Preço", Q.Muted, 10, modifier = Modifier.width(96.dp.f))
                    T("Liquidações estimadas", Q.Muted, 10, modifier = Modifier.weight(1.4f))
                    T("BTC", Q.Muted, 10, modifier = Modifier.width(78.dp.f))
                    T("Paredes do livro", Q.Muted, 10, modifier = Modifier.weight(1f))
                    T("BTC", Q.Muted, 10, modifier = Modifier.width(70.dp.f))
                }
                MagnetLadder(s.bins, price)
            }
        }
    })
}

@Composable
private fun MagnetLadder(bins: List<PriceBin>, price: Double) {
    val maxLiq = (bins.maxOfOrNull { it.liqUsd } ?: 0.0).coerceAtLeast(1.0)
    val maxBook = (bins.maxOfOrNull { it.bookUsd } ?: 0.0).coerceAtLeast(1.0)
    val current = bins.indexOfFirst { it.contains(price) }
    val list = rememberLazyListState()
    LaunchedEffect(current, bins.size) { if (current > 14) list.scrollToItem(current - 14) }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
        items(bins, key = { it.low }) { b ->
            val now = b.contains(price)
            val f = (b.liqUsd / maxLiq).toFloat()
            Row(Modifier.fillMaxWidth().height(20.dp).background(if (now) Q.Panel2 else Q.Panel), verticalAlignment = Alignment.CenterVertically) {
                T((if (now) "▶ " else "") + px(b.low), if (now) Q.Accent else Q.Muted, 11, monoFont = true, modifier = Modifier.width(96.dp.f))
                Box(Modifier.weight(1.4f).fillMaxHeight().padding(vertical = 2.dp)) {
                    if (f > 0.004f) Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(heat(0.15f + 0.85f * f)))
                }
                T(if (b.liqUsd > 0) num(b.liqUsd / price, 1) else "·", if (f > 0.5f) Q.HeatHigh else Q.Muted, 11, monoFont = true,
                    modifier = Modifier.width(78.dp.f).padding(start = 6.dp))
                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 5.dp)) {
                    val fb = (b.bookUsd / maxBook).toFloat()
                    if (fb > 0.004f) Box(Modifier.fillMaxHeight().fillMaxWidth(fb).background(Q.Sell.copy(alpha = 0.35f)))
                }
                T(if (b.bookUsd > 0) num(b.bookUsd / price, 1) else "·", Q.Muted, 11, monoFont = true, modifier = Modifier.width(70.dp.f).padding(start = 6.dp))
            }
        }
    }
}
