package com.cunhac.btcradar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.engine.HeatData
import com.cunhac.btcradar.engine.HeatRange
import com.cunhac.btcradar.engine.HeatmapModel
import com.cunhac.btcradar.engine.KeyLevel
import com.cunhac.btcradar.engine.LiqEvent
import com.cunhac.btcradar.engine.LiqSide
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.Venue
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val VIRIDIS = listOf(Color(0xFF2B0A3D), Color(0xFF3B528B), Color(0xFF21918C), Color(0xFF5EC962), Color(0xFFFDE725))

/** Escala Viridis (a mesma família de cores do CoinGlass): roxo = pouco, amarelo = muito. */
fun viridis(t: Float): Color {
    val x = t.coerceIn(0f, 1f) * (VIRIDIS.size - 1)
    val i = x.toInt().coerceAtMost(VIRIDIS.size - 2)
    return lerp(VIRIDIS[i], VIRIDIS[i + 1], x - i)
}

/** Converte a grade numa imagem (1 pixel por célula) para desenhar rápido mesmo com 730 colunas. */
private fun heatImage(l: Array<FloatArray>, s: Array<FloatArray>, ref: Float): ImageBitmap {
    val rows = l.size; val cols = l[0].size
    val bytes = ByteArray(rows * cols * 4)
    for (r in 0 until rows) for (c in 0 until cols) {
        val v = l[r][c] + s[r][c]
        val col = viridis(if (ref > 0) v / ref else 0f)
        val i = ((rows - 1 - r) * cols + c) * 4
        bytes[i] = (col.blue * 255).roundToInt().toByte(); bytes[i + 1] = (col.green * 255).roundToInt().toByte()
        bytes[i + 2] = (col.red * 255).roundToInt().toByte(); bytes[i + 3] = 255.toByte()
    }
    return Image.makeRaster(ImageInfo.makeN32(cols, rows, ColorAlphaType.PREMUL), bytes, cols * 4).toComposeImageBitmap()
}

private fun q99(l: Array<FloatArray>, s: Array<FloatArray>): Float {
    val vals = ArrayList<Float>()
    for (r in l.indices) for (c in l[r].indices) { val v = l[r][c] + s[r][c]; if (v > 0) vals += v }
    if (vals.isEmpty()) return 0f
    vals.sort(); return vals[(vals.size * 0.99).toInt().coerceAtMost(vals.size - 1)]
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiqHeatmapPanel(ctx: AppContext, s: TerminalState, modifier: Modifier) {
    val hs by ctx.heat.state.collectAsState()
    var venueIdx by remember { mutableIntStateOf(SettingsStore.i("hm.venue", 0)) }
    var intensity by remember { mutableFloatStateOf(SettingsStore.d("hm.int", 0.5).toFloat()) }
    var showReal by remember { mutableStateOf(SettingsStore.b("hm.real", true)) }
    var hover by remember { mutableStateOf<Offset?>(null) }
    val venues = if (venueIdx == 0) Venue.entries.toSet() else setOf(Venue.entries[venueIdx - 1])
    val d = hs.data
    val grids = remember(d, venueIdx) { d?.let { HeatmapModel.combine(it, venues) } }
    val ref = remember(grids) { grids?.let { q99(it.first, it.second) } ?: 0f }
    val img = remember(grids, intensity) { grids?.let { heatImage(it.first, it.second, ref / (0.25f + 1.5f * intensity)) } }
    val price = s.price ?: d?.candles?.lastOrNull()?.close
    val summary = remember(d, venueIdx, price?.let { (it / 50).toLong() }) {
        if (d != null && price != null) HeatmapModel.summary(d, venues, price, d.pStep * 2) else null
    }
    val real = remember(d, showReal, venueIdx, s.tick / 20) {
        if (d != null && showReal) ctx.db.liquidations(d.t0, System.currentTimeMillis(), venues) else emptyList()
    }
    val tm = rememberTextMeasurer()
    val axis = TextStyle(color = Q.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Liquidation heatmap · estimado (modelo V8) · " + if (venueIdx == 0) "todas as corretoras" else Venue.entries[venueIdx - 1].label,
            Modifier.weight(1f).fillMaxHeight()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chips("Período", HeatRange.entries.map { it.label }, hs.range.ordinal) { ctx.heat.load(HeatRange.entries[it]) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chips("Corretoras", listOf("TODAS") + Venue.entries.map { it.label.uppercase() }, venueIdx) { venueIdx = it; SettingsStore.put("hm.venue", it) }
                    T("Intensidade", Q.Muted, 11)
                    Slider(intensity, { intensity = it; SettingsStore.put("hm.int", it.toDouble()) }, Modifier.width(150.dp).height(20.dp),
                        colors = SliderDefaults.colors(thumbColor = Q.Accent, activeTrackColor = Q.Accent, inactiveTrackColor = Q.Line))
                    Toggle("Liquidações reais", showReal) { showReal = it; SettingsStore.put("hm.real", it) }
                    Spacer(Modifier.weight(1f))
                    T(when {
                        hs.loading -> "Carregando: ${hs.progress}…"
                        hs.error != null -> "Erro: ${hs.error}"
                        d != null -> "Atualizado ${hhmmss(d.builtAt)} · ${d.cols} colunas"
                        else -> ""
                    }, if (hs.error != null) Q.Sell else Q.Muted, 11)
                    Text("Atualizar", color = Q.Accent, fontSize = 11.sp, modifier = Modifier.border(1.dp, Q.Line).clickable { ctx.heat.load(hs.range, force = true) }.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.height(6.dp))
                BoxWithConstraints(Modifier.fillMaxSize().background(VIRIDIS[0])) {
                    if (d == null || img == null) {
                        Empty(if (hs.loading) "Montando o heatmap (${hs.progress})…" else "Heatmap indisponível", hs.error ?: "Usa velas e open interest históricos públicos das corretoras")
                        return@BoxWithConstraints
                    }
                    val axisW = 70f; val axisH = 18f
                    Canvas(Modifier.fillMaxSize()
                        .onPointerEvent(PointerEventType.Move) { hover = it.changes.first().position }
                        .onPointerEvent(PointerEventType.Exit) { hover = null }) {
                        val w = size.width - axisW; val h = size.height - axisH
                        val pMax = d.pMin + d.pStep * d.rows
                        fun y(p: Double) = (h - (p - d.pMin) / (pMax - d.pMin) * h).toFloat()
                        fun x(t: Long) = ((t - d.t0).toFloat() / (d.stepMs * d.cols)) * w
                        drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstSize = IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.Low)
                        // preço (fechamento das velas)
                        val path = Path()
                        d.candles.forEachIndexed { i, c -> val px = (i + 0.5f) / d.cols * w; if (i == 0) path.moveTo(px, y(c.close)) else path.lineTo(px, y(c.close)) }
                        drawPath(path, Color.White, style = Stroke(1.4f))
                        // liquidações reais
                        real.forEach { e ->
                            if (e.price < d.pMin || e.price > pMax) return@forEach
                            val r = (2f + sqrt(e.qtyBtc).toFloat() * 2.5f).coerceAtMost(10f)
                            val c = if (e.side == LiqSide.LONG) Q.LiqLong else Q.LiqShort
                            drawCircle(Color.Black, r + 1.2f, Offset(x(e.recvMs), y(e.price)))
                            drawCircle(c, r, Offset(x(e.recvMs), y(e.price)))
                        }
                        // eixos
                        drawRect(Q.Panel, Offset(w, 0f), Size(axisW, size.height))
                        drawRect(Q.Panel, Offset(0f, h), Size(size.width, axisH))
                        for (k in 0..7) {
                            val p = d.pMin + (pMax - d.pMin) * k / 7
                            drawText(tm, px(p), Offset(w + 6f, y(p) - 7f), axis)
                        }
                        val fmt = SimpleDateFormat(if (d.range.ms <= HeatRange.H48.ms) "HH:mm" else "dd/MM")
                        for (k in 0..5) {
                            val t = d.t0 + (d.stepMs * d.cols) * k / 6
                            drawText(tm, fmt.format(Date(t)), Offset(x(t) + 2f, h + 2f), axis)
                        }
                        price?.let { p ->
                            val yy = y(p)
                            drawLine(Q.Accent.copy(alpha = 0.6f), Offset(0f, yy), Offset(w, yy), 1f)
                            drawRect(Q.Accent, Offset(w, yy - 8f), Size(axisW, 16f))
                            drawText(tm, px(p), Offset(w + 6f, yy - 7f), axis.copy(color = Color.Black))
                        }
                        hover?.let { hp -> if (hp.x < w && hp.y < h) { drawLine(Color.White.copy(alpha = 0.35f), Offset(hp.x, 0f), Offset(hp.x, h), 1f); drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, hp.y), Offset(w, hp.y), 1f) } }
                    }
                    hover?.let { hp ->
                        val w = constraints.maxWidth - axisW; val h = constraints.maxHeight - axisH
                        if (hp.x < w && hp.y < h && grids != null) {
                            val col = (hp.x / w * d.cols).toInt().coerceIn(0, d.cols - 1)
                            val row = ((h - hp.y) / h * d.rows).toInt().coerceIn(0, d.rows - 1)
                            HeatTooltip(d, grids, venues, col, row, price, Modifier.offset {
                                IntOffset((hp.x + 16).toInt().coerceAtMost((w - 300).toInt().coerceAtLeast(0)), (hp.y + 12).toInt().coerceAtMost((h - 190).toInt().coerceAtLeast(0)))
                            })
                        }
                    }
                }
            }
        }
        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Níveis-chave de liquidação", Modifier.fillMaxWidth()) {
                Column {
                    if (summary == null) Empty("Aguardando o heatmap")
                    summary?.let { sm ->
                        val mx = (sm.above + sm.below).maxOfOrNull { it.btc } ?: 1.0
                        T("● Acima · liquidações de SHORT", Q.Sell, 11)
                        sm.above.forEach { KeyRow(it, mx, Q.Sell) }
                        Spacer(Modifier.height(6.dp))
                        T("● Abaixo · liquidações de LONG", Q.Buy, 11)
                        sm.below.forEach { KeyRow(it, mx, Q.Buy) }
                    }
                }
            }
            Section("Visão geral", Modifier.fillMaxWidth()) {
                Column {
                    summary?.let { sm ->
                        val tot = sm.longsBelowBtc + sm.shortsAboveBtc
                        T("Níveis de liquidação em aberto (estimados)", Q.Muted, 11)
                        T(btc(tot, 0), Q.Text, 20, bold = true, monoFont = true)
                        price?.let { T("≈ " + usdM(tot * it), Q.Muted, 11) }
                        Spacer(Modifier.height(6.dp))
                        val f = if (tot > 0) (sm.longsBelowBtc / tot).toFloat() else 0.5f
                        Row(Modifier.fillMaxWidth().height(6.dp)) {
                            Box(Modifier.weight(f.coerceIn(0.01f, 0.99f)).fillMaxHeight().background(Q.Buy))
                            Box(Modifier.weight((1 - f).coerceIn(0.01f, 0.99f)).fillMaxHeight().background(Q.Sell))
                        }
                        KV("Longs (abaixo do preço)", btc(sm.longsBelowBtc, 0) + " · " + num(f * 100.0, 0) + "%", Q.Buy)
                        KV("Shorts (acima do preço)", btc(sm.shortsAboveBtc, 0) + " · " + num((1 - f) * 100.0, 0) + "%", Q.Sell)
                    }
                    Spacer(Modifier.height(6.dp))
                    T("COBERTURA DO HISTÓRICO", Q.Muted, 10)
                    Venue.entries.forEach { v -> KV(v.label, d?.coverage?.get(v) ?: "—", if (d?.coverage?.get(v)?.startsWith("completo") == true) Q.Text else Q.Warn) }
                    Footnote("Estimativa: 10x/25x/50x/100x sobre o aumento de open interest de cada corretora; níveis atravessados pelo preço somem. " +
                        "Pontos laranja/azuis = liquidações REAIS reportadas (gravadas desde a instalação).", Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun KeyRow(k: KeyLevel, mx: Double, c: Color) {
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        T(px(k.price), Q.Text, 11, monoFont = true, modifier = Modifier.width(76.dp))
        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((k.btc / mx).toFloat().coerceIn(0.03f, 1f)).background(c.copy(alpha = 0.7f)))
        }
        T(num(k.btc, 0), Q.Text, 11, monoFont = true, modifier = Modifier.width(54.dp).padding(start = 6.dp))
        T(pct(k.distancePct), Q.Muted, 10, monoFont = true, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun HeatTooltip(d: HeatData, g: Pair<Array<FloatArray>, Array<FloatArray>>, venues: Set<Venue>, col: Int, row: Int, price: Double?, modifier: Modifier) {
    val t = d.t0 + d.stepMs * col
    val lo = d.pMin + d.pStep * row
    val longs = g.first[row][col]; val shorts = g.second[row][col]
    val close = d.candles.getOrNull(col)?.close
    Column(modifier.width(290.dp).background(Color(0xF0101317)).border(1.dp, Q.Line).padding(10.dp)) {
        T(SimpleDateFormat("dd/MM HH:mm").format(Date(t)) + "  ·  fechamento " + px(close), Q.Text, 11, bold = true)
        T("Faixa ${px(lo)} – ${px(lo + d.pStep)}", Q.Muted, 11)
        price?.let { T("Distância do preço atual: " + pct((lo + d.pStep / 2) / it * 100 - 100), Q.Muted, 11) }
        Spacer(Modifier.height(4.dp))
        KV("Longs a liquidar", btc(longs.toDouble(), 2), Q.Buy)
        KV("Shorts a liquidar", btc(shorts.toDouble(), 2), Q.Sell)
        KV("Total", btc((longs + shorts).toDouble(), 2))
        Spacer(Modifier.height(4.dp))
        venues.forEach { v ->
            val l = d.longGrid[v]?.get(row)?.get(col) ?: 0f; val s = d.shortGrid[v]?.get(row)?.get(col) ?: 0f
            if (d.longGrid[v] != null) KV("  ${v.label}", num((l + s).toDouble(), 2) + " BTC")
        }
        price?.let { T("≈ " + usdM((longs + shorts) * it), Q.Muted, 10) }
    }
}
