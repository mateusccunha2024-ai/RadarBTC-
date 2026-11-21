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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.engine.CORE_VENUES
import com.cunhac.btcradar.engine.FootCandle
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.VolumeProfile
import com.cunhac.btcradar.engine.buildProfile
import com.cunhac.btcradar.engine.ProfileRow
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max

private val FP_CANDLES = listOf("1m" to 60_000L, "5m" to 300_000L, "15m" to 900_000L, "1h" to 3_600_000L)
private val FP_STEPS = listOf(5.0, 10.0, 25.0, 50.0, 100.0)
private val FP_COUNTS = listOf(8, 12, 20, 30)
private val FP_PROFILE = listOf("Velas visíveis" to 0L, "4 h" to 14_400_000L, "24 h" to 86_400_000L)

/** Desequilíbrio diagonal clássico: compra no nível ≥ 3× a venda no nível de baixo (e vice-versa). */
private const val IMB_RATIO = 3.0

@Composable
fun FootprintTab(ctx: AppContext, s: TerminalState) {
    var cIdx by remember { mutableIntStateOf(SettingsStore.i("fp.c", 0)) }
    var stIdx by remember { mutableIntStateOf(SettingsStore.i("fp.s", 1)) }
    var nIdx by remember { mutableIntStateOf(SettingsStore.i("fp.n", 1)) }
    var vIdx by remember { mutableIntStateOf(SettingsStore.i("fp.v", 0)) }
    var pIdx by remember { mutableIntStateOf(SettingsStore.i("fp.p", 0)) }
    val venues = if (vIdx == 0) CORE_VENUES.toSet() else setOf(CORE_VENUES[vIdx - 1])
    val candleMs = FP_CANDLES[cIdx].second
    val step = FP_STEPS[stIdx]
    val candles = remember(s.tick, cIdx, stIdx, nIdx, vIdx) { ctx.hub.footprint.candles(candleMs, step, FP_COUNTS[nIdx], venues) }
    val profile = remember(s.tick, candles, pIdx) {
        val w = FP_PROFILE[pIdx].second
        if (w > 0) ctx.hub.footprint.profile(w, step, venues)
        else {
            val acc = java.util.TreeMap<Double, DoubleArray>()
            candles.forEach { c -> c.cells.forEach { (p, v) -> acc.getOrPut(p) { DoubleArray(2) }.let { it[0] += v[0]; it[1] += v[1] } } }
            buildProfile(acc.map { ProfileRow(it.key, it.value[0], it.value[1]) })
        }
    }
    val oldest = ctx.hub.footprint.oldestMs()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.background(Q.Panel).padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Chips("Vela", FP_CANDLES.map { it.first }, cIdx) { cIdx = it; SettingsStore.put("fp.c", it) }
            Chips("Faixa US$", FP_STEPS.map { num(it, 0) }, stIdx) { stIdx = it; SettingsStore.put("fp.s", it) }
            Chips("Velas", FP_COUNTS.map { it.toString() }, nIdx) { nIdx = it; SettingsStore.put("fp.n", it) }
            Chips("Corretoras", listOf("TODAS") + CORE_VENUES.map { it.label.uppercase() }, vIdx) { vIdx = it; SettingsStore.put("fp.v", it) }
            Spacer(Modifier.weight(1f))
            T(oldest?.let { "Dados desde ${hhmmss(it)}" } ?: "Aguardando trades", Q.Muted, 11)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Footprint · venda × compra agressora por faixa (BTC)", Modifier.weight(1f).fillMaxHeight()) {
                if (candles.isEmpty()) Empty("Montando o footprint", "Precisa de trades das corretoras principais; cada vela aparece assim que recebe execuções")
                else FootprintChart(candles, step, s.price, Modifier.fillMaxSize())
            }
            Column(Modifier.width(330.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Perfil de volume", Modifier.fillMaxWidth().weight(1f)) {
                    Column {
                        Chips(null, FP_PROFILE.map { it.first }, pIdx) { pIdx = it; SettingsStore.put("fp.p", it) }
                        Spacer(Modifier.height(6.dp))
                        if (profile.rows.isEmpty()) Empty("Sem volume no período")
                        else ProfileChart(profile, s.price, Modifier.fillMaxWidth().weight(1f))
                    }
                }
                Section("Resumo", Modifier.fillMaxWidth()) {
                    Column {
                        KV("POC (maior volume)", px(profile.poc), Q.Accent)
                        KV("VAH (topo da área de valor)", px(profile.vah))
                        KV("VAL (base da área de valor)", px(profile.val_))
                        KV("Volume no perfil", btc(profile.total, 1))
                        val buy = profile.rows.sumOf { it.buy }; val sell = profile.rows.sumOf { it.sell }
                        KV("Delta do perfil", String.format(BR, "%+.2f BTC", buy - sell), if (buy >= sell) Q.Buy else Q.Sell)
                        candles.lastOrNull()?.let { c -> KV("Delta da vela atual", String.format(BR, "%+.2f BTC", c.delta), if (c.delta >= 0) Q.Buy else Q.Sell) }
                    }
                }
            }
        }
        Footnote("Cada célula mostra venda agressora × compra agressora naquela faixa de preço. Fundo verde = compradores dominaram a faixa; vermelho = vendedores. " +
            "Número em destaque = desequilíbrio diagonal (um lado ≥ 3× o outro). Contorno laranja = POC da vela. " +
            "Área de valor = faixas com 70% do volume. Trades de Binance, Bybit e OKX, guardados por 24 h em faixas de US$ 5.")
    }
}

@Composable
private fun FootprintChart(candles: List<FootCandle>, step: Double, price: Double?, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val buyC = Q.Buy; val sellC = Q.Sell; val text = Q.Text; val muted = Q.Muted; val line = Q.Line; val accent = Q.Accent; val panel2 = Q.Panel2
    Canvas(modifier) {
        val axisW = 72f; val footH = 34f; val headH = 16f
        val w = size.width - axisW; val h = size.height - footH - headH
        val lo = candles.minOf { it.low }.let { kotlin.math.floor(it / step) * step }
        val hi = candles.maxOf { it.high }.let { kotlin.math.floor(it / step) * step + step }
        val rows = ((hi - lo) / step).toInt().coerceAtLeast(1)
        val rowH = h / rows
        val colW = w / candles.size
        val maxCell = candles.maxOf { c -> c.cells.values.maxOf { it[0] + it[1] } }.coerceAtLeast(1e-9)
        val fs = (rowH * 0.62f).coerceIn(8.sp.toPx(), 12.sp.toPx())
        // Números só quando cabem: "00,00 × 00,00" em fonte monoespaçada.
        val showText = rowH >= 11.sp.toPx() && colW >= fs * 0.62f * 11 + 24f
        val cellStyle = TextStyle(color = text, fontSize = fs.toSp(), fontFamily = FontFamily.Monospace)
        val axis = TextStyle(color = muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        fun y(p: Double) = headH + ((hi - p) / (hi - lo) * h).toFloat()
        fun f(v: Double) = if (v <= 0) "0" else if (v < 10) String.format(BR, "%.2f", v) else String.format(BR, "%.0f", v)
        val tf = SimpleDateFormat("HH:mm")

        candles.forEachIndexed { i, c ->
            val x0 = i * colW
            drawText(tm, tf.format(Date(c.startMs)), Offset(x0 + 4f, 1f), axis)
            val poc = c.poc
            for ((p, v) in c.cells) {
                val top = y(p + step); val vol = v[0] + v[1]
                val imb = if (vol > 0) ((v[0] - v[1]) / vol).toFloat() else 0f
                val alpha = (0.10f + 0.55f * (vol / maxCell).toFloat()) * abs(imb).coerceAtLeast(0.25f)
                drawRect((if (imb >= 0) buyC else sellC).copy(alpha = alpha.coerceIn(0.05f, 0.75f)), Offset(x0 + 8f, top), Size(colW - 10f, rowH - 1f))
                if (p == poc) drawRect(accent, Offset(x0 + 8f, top), Size(colW - 10f, rowH - 1f), style = Stroke(1.5f))
                if (showText) {
                    val below = c.cells[p - step]; val above = c.cells[p + step]
                    val buyImb = below != null && v[0] >= IMB_RATIO * max(below[1], 1e-9) && v[0] >= 0.5
                    val sellImb = above != null && v[1] >= IMB_RATIO * max(above[0], 1e-9) && v[1] >= 0.5
                    val ty = top + (rowH - fs * 1.25f) / 2
                    val half = (colW - 10f) / 2
                    drawText(tm, f(v[1]), Offset(x0 + 12f, ty), if (sellImb) cellStyle.copy(color = sellC, fontWeight = FontWeight.Bold) else cellStyle)
                    drawText(tm, "×", Offset(x0 + 8f + half - 3f, ty), cellStyle.copy(color = muted))
                    drawText(tm, f(v[0]), Offset(x0 + 8f + half + 7f, ty), if (buyImb) cellStyle.copy(color = buyC, fontWeight = FontWeight.Bold) else cellStyle)
                }
            }
            // pavio e corpo da vela à esquerda da coluna
            val up = c.close >= c.open
            drawLine(muted, Offset(x0 + 4f, y(c.high)), Offset(x0 + 4f, y(c.low)), 1f)
            drawRect(if (up) buyC else sellC, Offset(x0 + 2f, y(max(c.open, c.close))), Size(4f, abs(y(c.open) - y(c.close)).coerceAtLeast(1f)))
            // rodapé: delta e volume
            drawRect(panel2, Offset(x0 + 1f, headH + h + 2f), Size(colW - 2f, footH - 2f))
            drawText(tm, "Δ " + String.format(BR, "%+.1f", c.delta), Offset(x0 + 6f, headH + h + 3f), axis.copy(color = if (c.delta >= 0) buyC else sellC))
            drawText(tm, "V " + String.format(BR, "%.1f", c.volume), Offset(x0 + 6f, headH + h + 17f), axis)
            drawLine(line, Offset(x0 + colW, headH), Offset(x0 + colW, headH + h), 1f)
        }
        // eixo de preço
        drawRect(panel2, Offset(w, headH), Size(axisW, h))
        val every = max(1, (rows / (h / 18f)).toInt() + 1)
        for (r in 0..rows step every) {
            val p = lo + r * step
            drawText(tm, px(p), Offset(w + 4f, y(p) - 7f), axis)
        }
        price?.let { p -> if (p in lo..hi) { val yy = y(p); drawLine(accent, Offset(0f, yy), Offset(w, yy), 1f); drawText(tm, px(p), Offset(w + 4f, yy - 7f), axis.copy(color = accent)) } }
        if (!showText) drawText(tm, "Aumente a faixa US$ ou reduza o nº de velas para ver os números", Offset(10f, headH + h - 16f), axis)
    }
}

@Composable
private fun ProfileChart(p: VolumeProfile, price: Double?, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val buyC = Q.Buy; val sellC = Q.Sell; val muted = Q.Muted; val accent = Q.Accent; val line = Q.Line
    Canvas(modifier) {
        val rows = p.rows
        val lo = rows.first().price; val hi = rows.last().price
        val step = if (rows.size > 1) (hi - lo) / (rows.size - 1) else 1.0
        val axisW = 70f
        val w = size.width - axisW
        val rh = size.height / (rows.size).coerceAtLeast(1)
        val mx = rows.maxOf { it.total }.coerceAtLeast(1e-9)
        val axis = TextStyle(color = muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        fun y(price: Double) = ((hi - price) / (hi - lo + step) * size.height).toFloat()
        rows.forEach { r ->
            val top = y(r.price + step) + 0f
            val inVa = p.vah != null && p.val_ != null && r.price in p.val_!!..p.vah!!
            val bw = (r.total / mx * w).toFloat()
            val bb = (r.buy / mx * w).toFloat()
            val a = if (inVa) 0.85f else 0.4f
            drawRect(sellC.copy(alpha = a), Offset(0f, top), Size(bw, (rh - 1f).coerceAtLeast(1f)))
            drawRect(buyC.copy(alpha = a), Offset(0f, top), Size(bb, (rh - 1f).coerceAtLeast(1f)))
            if (r.price == p.poc) drawRect(accent, Offset(0f, top), Size(w, rh.coerceAtLeast(2f)), style = Stroke(1.5f))
        }
        fun mark(label: String, v: Double?, c: androidx.compose.ui.graphics.Color) {
            v ?: return
            val yy = y(v + step / 2)
            drawLine(c, Offset(0f, yy), Offset(w, yy), 1f)
            drawText(tm, "$label ${px(v)}", Offset(w + 2f, yy - 7f), axis.copy(color = c))
        }
        mark("VAH", p.vah, muted); mark("VAL", p.val_, muted); mark("POC", p.poc, accent)
        price?.let { if (it in lo..(hi + step)) { val yy = y(it); drawLine(line, Offset(0f, yy), Offset(w, yy), 2f) } }
    }
}
