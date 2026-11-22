package com.cunhac.btcradar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.engine.HEAT_TIERS
import com.cunhac.btcradar.engine.HeatData
import com.cunhac.btcradar.engine.HeatRange
import com.cunhac.btcradar.engine.HeatmapModel
import com.cunhac.btcradar.engine.KeyLevel
import com.cunhac.btcradar.engine.LiqEvent
import com.cunhac.btcradar.engine.LiqSide
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.CORE_VENUES
import com.cunhac.btcradar.engine.Venue
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

/** Escala divergente para LONGS × SHORTS: cor de LONG = mais longs a liquidar, cor de SHORT = mais shorts (as mesmas do app todo). */
fun diverging(t: Float): Color = if (t >= 0) lerp(VIRIDIS[0], Q.LiqLong, t.coerceIn(0f, 1f)) else lerp(VIRIDIS[0], Q.LiqShort, (-t).coerceIn(0f, 1f))

/** Converte a grade numa imagem (1 pixel por célula) para desenhar rápido mesmo com 730 colunas. */
private fun heatImage(rows: Int, cols: Int, color: (Int, Int) -> Color): ImageBitmap {
    val px = IntArray(rows * cols)
    for (r in 0 until rows) for (c in 0 until cols) px[(rows - 1 - r) * cols + c] = color(r, c).toArgb()
    return argbImage(cols, rows, px)
}

private fun q99(rows: Int, cols: Int, v: (Int, Int) -> Float): Float {
    val vals = ArrayList<Float>()
    for (r in 0 until rows) for (c in 0 until cols) { val x = kotlin.math.abs(v(r, c)); if (x > 0) vals += x }
    if (vals.isEmpty()) return 0f
    vals.sort(); return vals[(vals.size * 0.99).toInt().coerceAtMost(vals.size - 1)]
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiqHeatmapPanel(ctx: AppContext, s: TerminalState, modifier: Modifier) {
    val hs by ctx.heat.state.collectAsState()
    var venueIdx by remember { mutableIntStateOf(SettingsStore.i("hm.venue", 0)) }
    var intensity by remember { mutableFloatStateOf(SettingsStore.d("hm.int", 0.5).toFloat()) }
    var showReal by remember { mutableStateOf(SettingsStore.b("hm.real", true)) }
    var hover by remember { mutableStateOf<Offset?>(null) }
    var mode by remember { mutableIntStateOf(SettingsStore.i("hm.mode", 0)) }
    var tierMask by remember { mutableIntStateOf(SettingsStore.i("hm.tiers", 0b1111).takeIf { it in 1..15 } ?: 0b1111) }
    val tiers = HEAT_TIERS.indices.filter { tierMask and (1 shl it) != 0 }.toSet()
    val venues = if (venueIdx == 0) CORE_VENUES.toSet() else setOf(CORE_VENUES[venueIdx - 1])
    val d = hs.data
    val grids = remember(d, venueIdx, tierMask) { d?.let { HeatmapModel.combine(it, venues, tiers) } }
    val entries = remember(d, venueIdx, mode) { if (d != null && mode == 1) HeatmapModel.entries(d, venues) else null }
    val delta = remember(grids) { grids?.let { HeatmapModel.levelDelta(it) } }
    val value: (Int, Int) -> Float = { r, c ->
        when (mode) {
            1 -> entries?.get(r)?.get(c) ?: 0f
            2 -> grids?.let { it.first[r][c] - it.second[r][c] } ?: 0f
            else -> grids?.let { it.first[r][c] + it.second[r][c] } ?: 0f
        }
    }
    val ref = remember(grids, entries, mode) { d?.let { q99(it.rows, it.cols, value) } ?: 0f }
    /** Valor (BTC por faixa) que chega à cor mais forte da escala. */
    val k = ref / (0.25f + 1.5f * intensity)
    val img = remember(grids, entries, mode, intensity, Look.colorblind, Look.dark) {
        if (d == null || grids == null) null else {
            heatImage(d.rows, d.cols) { r, c -> val v = value(r, c); if (mode == 2) diverging(if (k > 0) v / k else 0f) else viridis(if (k > 0) v / k else 0f) }
        }
    }
    val price = s.price ?: d?.candles?.lastOrNull()?.close
    val summary = remember(d, venueIdx, tierMask, price?.let { (it / 50).toLong() }) {
        if (d != null && price != null) HeatmapModel.summary(d, venues, price, d.pStep * 2, tiers = tiers) else null
    }
    val real = remember(d, showReal, venueIdx, s.tick / 20) {
        if (d != null && showReal) ctx.db.liquidations(d.t0, System.currentTimeMillis(), if (venueIdx == 0) Venue.entries.toSet() else venues) else emptyList()
    }
    val tm = rememberTextMeasurer()
    val axis = TextStyle(color = Q.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)

    Panes(modifier, Pane(1f, 640.dp) { hm ->
        Section(HEAT_MODES[mode] + " · estimado (modelo V8) · " + if (venueIdx == 0) "Binance + Bybit + OKX" else CORE_VENUES[venueIdx - 1].label,
            hm) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Chips("Período", HeatRange.entries.map { it.label }, hs.range.ordinal) { ctx.heat.load(HeatRange.entries[it]) }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chips("Mapa", listOf("LIQUIDAÇÕES", "OI POR ENTRADA", "LONGS × SHORTS"), mode) { mode = it; SettingsStore.put("hm.mode", it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        T("Alavancagem", Q.Muted, 11, modifier = Modifier.padding(top = 4.dp))
                        HEAT_TIERS.forEachIndexed { i, t ->
                            Toggle(t, tierMask and (1 shl i) != 0) { on ->
                                val m = if (on) tierMask or (1 shl i) else tierMask and (1 shl i).inv()
                                if (m != 0) { tierMask = m; SettingsStore.put("hm.tiers", m) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chips("Corretoras", listOf("TODAS") + CORE_VENUES.map { it.label.uppercase() }, venueIdx) { venueIdx = it; SettingsStore.put("hm.venue", it) }
                    T("Intensidade", Q.Muted, 11)
                    Slider(intensity, { intensity = it; SettingsStore.put("hm.int", it.toDouble()) }, Modifier.width(150.dp).height(20.dp),
                        colors = SliderDefaults.colors(thumbColor = Q.Accent, activeTrackColor = Q.Accent, inactiveTrackColor = Q.Line))
                    Toggle("Liquidações reais", showReal) { showReal = it; SettingsStore.put("hm.real", it) }
                    T(when {
                        hs.loading -> "Carregando: ${hs.progress}…"
                        hs.error != null -> "Erro: ${hs.error}"
                        d != null -> "Atualizado ${hhmmss(d.builtAt)} · ${d.cols} colunas"
                        else -> ""
                    }, if (hs.error != null) Q.Sell else Q.Muted, 11)
                    OutlineButton("Atualizar", border = Q.Line, size = 11) { ctx.heat.load(hs.range, force = true) }
                }
                Spacer(Modifier.height(6.dp))
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(if (d == null) Q.Panel2 else VIRIDIS[0])) {
                    if (d == null || img == null) {
                        Empty(if (hs.loading) "Montando o heatmap (${hs.progress})…" else "Heatmap indisponível", hs.error ?: "Usa velas e open interest históricos públicos das corretoras")
                        return@BoxWithConstraints
                    }
                    val u = LocalDensity.current.density
                    val axisW = 64f * u; val axisH = 18f * u
                    // Mouse (PC) ou toque (celular): mostra a cruz e o detalhe do ponto.
                    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type == PointerEventType.Exit) hover = null else e.changes.firstOrNull()?.let { hover = it.position }
                            }
                        }
                    }) {
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
                            drawText(tm, px(p), Offset(w + 6f * u, y(p) - 7f * u), axis)
                        }
                        val fmt = SimpleDateFormat(if (d.range.ms <= HeatRange.H48.ms) "HH:mm" else "dd/MM")
                        for (k in 0..5) {
                            val t = d.t0 + (d.stepMs * d.cols) * k / 6
                            drawText(tm, fmt.format(Date(t)), Offset(x(t) + 2f * u, h + 2f * u), axis)
                        }
                        price?.let { p ->
                            val yy = y(p)
                            drawLine(Q.Accent.copy(alpha = 0.6f), Offset(0f, yy), Offset(w, yy), 1f)
                            drawRect(Q.Accent, Offset(w, yy - 8f * u), Size(axisW, 16f * u))
                            drawText(tm, px(p), Offset(w + 6f * u, yy - 7f * u), axis.copy(color = if (Look.dark) Color.Black else Color.White))
                        }
                        hover?.let { hp -> if (hp.x < w && hp.y < h) { drawLine(Color.White.copy(alpha = 0.35f), Offset(hp.x, 0f), Offset(hp.x, h), 1f); drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, hp.y), Offset(w, hp.y), 1f) } }
                    }
                    hover?.let { hp ->
                        val w = constraints.maxWidth - axisW; val h = constraints.maxHeight - axisH
                        if (hp.x < w && hp.y < h && grids != null) {
                            val col = (hp.x / w * d.cols).toInt().coerceIn(0, d.cols - 1)
                            val row = ((h - hp.y) / h * d.rows).toInt().coerceIn(0, d.rows - 1)
                            HeatTooltip(d, grids, entries, mode, venues, col, row, price, Modifier.offset {
                                IntOffset((hp.x + 16 * u).toInt().coerceAtMost((w - 300 * u).toInt().coerceAtLeast(0)), (hp.y + 12 * u).toInt().coerceAtMost((h - 190 * u).toInt().coerceAtLeast(0)))
                            })
                        }
                    }
                }
                if (d != null && img != null) HeatLegend(mode, k, showReal, Modifier.fillMaxWidth().padding(top = 6.dp))
                if (d != null && delta != null && delta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    DeltaStrip(delta, Modifier.fillMaxWidth().height(54.dp))
                }
            }
        }
    }, Pane(0.25f) { pm ->
        Column(if (compact) pm else Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    CORE_VENUES.forEach { v -> KV(v.label, d?.coverage?.get(v) ?: "—", if (d?.coverage?.get(v)?.startsWith("completo") == true) Q.Text else Q.Warn) }
                    Footnote("Estimativa: 10x/25x/50x/100x sobre o aumento de open interest de cada corretora; níveis atravessados pelo preço somem. " +
                        "OI POR ENTRADA = onde o modelo estima que as posições ainda abertas entraram. LONGS × SHORTS: verde = mais longs a liquidar naquele preço, vermelho = mais shorts. " +
                        "Faixa inferior = total de níveis LONG em aberto menos SHORT. O filtro de alavancagem vale para o mapa e os níveis-chave. " +
                        "Pontos = liquidações REAIS reportadas pelas 6 corretoras (gravadas desde a instalação), na cor de LONG ou de SHORT.", Modifier.padding(top = 6.dp))
                }
            }
        }
    })
}

/** Legenda de cores do mapa: quanto BTC cada tom representa (e o significado dos pontos). */
@Composable
private fun HeatLegend(mode: Int, k: Float, showReal: Boolean, modifier: Modifier) {
    val fmt = { v: Float -> num(v.toDouble(), if (v < 10) 1 else 0) }
    Column(modifier) {
        T(if (mode == 2) "Escala: longs − shorts a liquidar por faixa (BTC)" else if (mode == 1) "Escala: OI aberto por faixa de preço (BTC)" else "Escala: BTC a liquidar por faixa de preço",
            Q.Muted, 10, wrap = true)
        val stops = if (mode == 2) listOf(Q.LiqShort, VIRIDIS[0], Q.LiqLong) else VIRIDIS
        Canvas(Modifier.fillMaxWidth().height(10.dp).padding(vertical = 1.dp)) {
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(stops), size = size)
        }
        Row(Modifier.fillMaxWidth()) {
            if (mode == 2) {
                T("≥ ${fmt(k)} mais shorts", Q.LiqShort, 10, monoFont = true)
                T("0", Q.Muted, 10, monoFont = true, modifier = Modifier.weight(1f), align = TextAlign.Center)
                T("≥ ${fmt(k)} mais longs", Q.LiqLong, 10, monoFont = true)
            } else {
                T("0", Q.Muted, 10, monoFont = true)
                T(fmt(k / 2), Q.Muted, 10, monoFont = true, modifier = Modifier.weight(1f), align = TextAlign.Center)
                T("≥ ${fmt(k)}", Q.Muted, 10, monoFont = true)
            }
        }
        if (showReal) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T("Pontos:", Q.Muted, 10); T("● LONG liquidado", Q.LiqLong, 10); T("● SHORT liquidado", Q.LiqShort, 10)
        }
    }
}

private val HEAT_MODES = listOf("Liquidation heatmap", "Open interest por preço de entrada", "Longs × shorts a liquidar")

/** Faixa inferior: níveis LONG em aberto − níveis SHORT em aberto ao longo do tempo (mesmo eixo X do mapa). */
@Composable
private fun DeltaStrip(delta: FloatArray, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val st = TextStyle(color = Q.Muted, fontSize = 10.sp)
    Canvas(modifier.background(Q.Panel2)) {
        val axisW = 64.dp.toPx()
        val w = size.width - axisW
        val mx = (delta.maxOfOrNull { kotlin.math.abs(it) } ?: 0f).takeIf { it > 0 } ?: 1f
        val mid = size.height / 2
        val bw = w / delta.size
        delta.forEachIndexed { i, v ->
            val hh = v / mx * (mid - 4)
            drawRect(if (v >= 0) Q.Buy.copy(alpha = 0.75f) else Q.Sell.copy(alpha = 0.75f),
                Offset(i * bw, if (hh >= 0) mid - hh else mid), Size(bw.coerceAtLeast(1f), kotlin.math.abs(hh)))
        }
        drawLine(Q.Line, Offset(0f, mid), Offset(w, mid), 1f)
        drawText(tm, "Delta", Offset(w + 4.dp.toPx(), 2.dp.toPx()), st)
        drawText(tm, String.format(BR, "%+,.0f BTC", delta.last()), Offset(w + 4.dp.toPx(), mid + 4.dp.toPx()), st.copy(color = if (delta.last() >= 0) Q.Buy else Q.Sell))
    }
}

@Composable
private fun KeyRow(k: KeyLevel, mx: Double, c: Color) {
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        T(px(k.price), Q.Text, 11, monoFont = true, modifier = Modifier.width(76.dp))
        Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((k.btc / mx).toFloat().coerceIn(0.03f, 1f)).background(c.copy(alpha = 0.7f)))
        }
        T(num(k.btc, if (k.btc < 10) 1 else 0), Q.Text, 11, monoFont = true, modifier = Modifier.width(54.dp).padding(start = 6.dp))
        T(pct(k.distancePct), Q.Muted, 10, monoFont = true, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun HeatTooltip(d: HeatData, g: Pair<Array<FloatArray>, Array<FloatArray>>, entries: Array<FloatArray>?, mode: Int, venues: Set<Venue>,
                        col: Int, row: Int, price: Double?, modifier: Modifier) {
    val t = d.t0 + d.stepMs * col
    val lo = d.pMin + d.pStep * row
    val longs = g.first[row][col]; val shorts = g.second[row][col]
    val close = d.candles.getOrNull(col)?.close
    Column(modifier.width(290.dp).background(Q.Tip).border(1.dp, Q.Line).padding(10.dp)) {
        T(SimpleDateFormat("dd/MM HH:mm").format(Date(t)) + "  ·  fechamento " + px(close), Q.Text, 11, bold = true)
        T("Faixa ${px(lo)} – ${px(lo + d.pStep)}", Q.Muted, 11)
        price?.let { T("Distância do preço atual: " + pct((lo + d.pStep / 2) / it * 100 - 100), Q.Muted, 11) }
        Spacer(Modifier.height(4.dp))
        KV("Longs a liquidar", btc(longs.toDouble(), 2), Q.Buy)
        KV("Shorts a liquidar", btc(shorts.toDouble(), 2), Q.Sell)
        KV("Total", btc((longs + shorts).toDouble(), 2))
        KV("Longs − shorts", String.format(BR, "%+.2f BTC", (longs - shorts).toDouble()), if (longs >= shorts) Q.Buy else Q.Sell)
        if (mode == 1 && entries != null) KV("OI aberto neste preço", btc(entries[row][col].toDouble(), 2), Q.Accent)
        Spacer(Modifier.height(4.dp))
        venues.forEach { v ->
            val l = d.longGrid[v]?.get(row)?.get(col) ?: 0f; val s = d.shortGrid[v]?.get(row)?.get(col) ?: 0f
            if (d.longGrid[v] != null) KV("  ${v.label}", num((l + s).toDouble(), 2) + " BTC")
        }
        price?.let { T("≈ " + usdM((longs + shorts) * it), Q.Muted, 10) }
    }
}
