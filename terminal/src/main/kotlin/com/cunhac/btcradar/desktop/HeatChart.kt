package com.cunhac.btcradar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.engine.HeatData
import com.cunhac.btcradar.engine.LiqEvent
import com.cunhac.btcradar.engine.LiqSide
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Ferramentas do gráfico do heatmap. MOVER = navegar (zoom/arrastar) e selecionar marcações. */
enum class DrawTool(val label: String, val short: String, val hint: String) {
    NONE("Mover", "Mover", ""),
    HLINE("Linha de preço", "Linha", "Clique no preço onde a linha deve ficar"),
    TREND("Tendência", "Tendência", "Arraste do ponto inicial até o final"),
    ZONE("Zona", "Zona", "Arraste de um canto da zona até o canto oposto"),
    TEXT("Texto", "Texto", "Clique onde a nota deve ficar"),
}

/** Uma marcação do usuário em coordenadas do gráfico (tempo em ms, preço em US$); vale para todos os períodos. */
data class Drawing(val tool: DrawTool, val t1: Long, val p1: Double, val t2: Long, val p2: Double, val text: String = "")

/** Marcações salvas nas preferências (uma por linha: ferramenta|t1|p1|t2|p2|texto). Até 100 (limite de tamanho do registro). */
object Drawings {
    private const val KEY = "hm.drawings"
    private const val MAX = 100
    var list by mutableStateOf(load()); private set

    private fun load(): List<Drawing> = SettingsStore.s(KEY, "").lines().mapNotNull { l ->
        val f = l.split('|')
        if (f.size < 5) null
        else runCatching { Drawing(DrawTool.valueOf(f[0]), f[1].toLong(), f[2].toDouble(), f[3].toLong(), f[4].toDouble(), f.drop(5).joinToString("|")) }.getOrNull()
    }

    private fun save() = SettingsStore.put(KEY, list.joinToString("\n") {
        "${it.tool.name}|${it.t1}|${it.p1}|${it.t2}|${it.p2}|${it.text.replace('\n', ' ').take(80)}"
    })

    fun add(x: Drawing) { list = (list + x).takeLast(MAX); save() }
    fun remove(x: Drawing) { list = list - x; save() }
    fun clear() { list = emptyList(); save() }
}

/** Janela visível: tempo [tA, tB] em ms e preço [pA, pB] em US$. */
private data class View(val tA: Double, val tB: Double, val pA: Double, val pB: Double)

/** Limita o zoom (mín. ~4 células, máx. 1,5× os dados) e não deixa a tela sair dos dados (pelo menos 20% com dados). */
private fun clampAxis(a0: Double, b0: Double, lo: Double, hi: Double, minSpan: Double): Pair<Double, Double> {
    var a = a0; var b = b0
    val maxSpan = (hi - lo) * 1.5
    val span = b - a
    if (span < minSpan || span > maxSpan) {
        val c = (a + b) / 2; val s = span.coerceIn(minSpan, maxSpan); a = c - s / 2; b = c + s / 2
    }
    val keep = (b - a) * 0.2
    if (b < lo + keep) { val s = lo + keep - b; a += s; b += s }
    if (a > hi - keep) { val s = a - (hi - keep); a -= s; b -= s }
    return a to b
}

/** Distância (px) de um ponto até um segmento. */
private fun segDist(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

private val AXIS_W = 64.dp
private val AXIS_H = 18.dp

/**
 * Gráfico do heatmap com navegação e marcações, igual a um gráfico de preço:
 * roda do mouse = zoom no tempo (sobre a escala de preço = zoom no preço), arrastar = mover,
 * arrastar a escala de preço = esticar/encolher o preço, duplo clique = voltar ao período inteiro;
 * no celular, pinça = zoom e toque duplo = voltar. Ferramentas: linha de preço, tendência, zona e texto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeatChart(d: HeatData, img: ImageBitmap, real: List<LiqEvent>, price: Double?, modifier: Modifier,
              tooltip: @Composable (col: Int, row: Int, modifier: Modifier) -> Unit) {
    val pMax = d.pMin + d.pStep * d.rows
    val full = View(d.t0.toDouble(), d.t0 + d.stepMs.toDouble() * d.cols, d.pMin, pMax)
    val minT = d.stepMs * 4.0
    val minP = d.pStep * 4.0
    // A janela só é guardada quando o usuário mexe; sem zoom, acompanha os dados novos a cada atualização.
    var custom by remember(d.range) { mutableStateOf<View?>(null) }
    val view = custom ?: full
    fun setView(v: View) {
        val (a, b) = clampAxis(v.tA, v.tB, full.tA, full.tB, minT)
        val (pa, pb) = clampAxis(v.pA, v.pB, full.pA, full.pB, minP)
        custom = View(a, b, pa, pb)
    }
    var tool by remember { mutableStateOf(DrawTool.NONE) }
    var selected by remember { mutableStateOf<Drawing?>(null) }
    var draft by remember { mutableStateOf<Drawing?>(null) }
    var pendingText by remember { mutableStateOf<Pair<Long, Double>?>(null) }
    var noteText by remember { mutableStateOf("") }
    var hover by remember { mutableStateOf<Offset?>(null) }
    val tm = rememberTextMeasurer()
    val axis = TextStyle(color = Q.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    val note = TextStyle(color = Color.White, fontSize = 11.sp)
    val small = compact // lido aqui: dentro do desenho não dá para ler valores de composição

    Column(modifier) {
        // Barra de ferramentas
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 4.dp)) {
            Chips(null, DrawTool.entries.map { if (compact) it.short else it.label }, tool.ordinal) { tool = DrawTool.entries[it]; draft = null; pendingText = null }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlineButton("−", color = Q.Text, border = Q.Line, size = 12) { val c = (view.tA + view.tB) / 2; setView(view.copy(tA = c - (c - view.tA) * 1.5, tB = c + (view.tB - c) * 1.5)) }
                OutlineButton("+", color = Q.Text, border = Q.Line, size = 12) { val c = (view.tA + view.tB) / 2; setView(view.copy(tA = c - (c - view.tA) / 1.5, tB = c + (view.tB - c) / 1.5)) }
                OutlineButton("Ajustar", color = if (custom != null) Q.Accent else Q.Muted, border = Q.Line, size = 12) { custom = null }
            }
            selected?.let { sel ->
                OutlineButton("Apagar marcação", color = Q.Sell, border = Q.Sell, size = 12) { Drawings.remove(sel); selected = null }
            }
            if (Drawings.list.isNotEmpty())
                OutlineButton("Apagar todas (${Drawings.list.size})", color = Q.Muted, border = Q.Line, size = 12) { Drawings.clear(); selected = null }
        }
        // Instrução só enquanto uma ferramenta de desenho está ativa; a ajuda de navegação fica dentro do gráfico.
        if (tool != DrawTool.NONE) T(tool.hint, Q.Accent, 11, wrap = true, modifier = Modifier.padding(bottom = 4.dp))
        pendingText?.let { (t, p) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                T("Nota em ${px(p)}:", Q.Muted, 11)
                BasicTextField(noteText, { noteText = it.take(80) }, singleLine = true, textStyle = mono, cursorBrush = SolidColor(Q.Accent),
                    modifier = Modifier.width(240.dp).background(Q.Panel2).border(1.dp, Q.Line).padding(horizontal = 6.dp, vertical = if (compact) 12.dp else 5.dp))
                OutlineButton("Salvar", color = Q.Accent, border = Q.Accent, size = 12) {
                    if (noteText.isNotBlank()) Drawings.add(Drawing(DrawTool.TEXT, t, p, t, p, noteText.trim()))
                    pendingText = null; noteText = ""; tool = DrawTool.NONE
                }
                OutlineButton("Cancelar", border = Q.Line, size = 12) { pendingText = null; noteText = "" }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(HEAT[0])) {
            val u = LocalDensity.current.density
            val axisWpx = AXIS_W.value * u; val axisHpx = AXIS_H.value * u
            val cw = constraints.maxWidth - axisWpx; val chh = constraints.maxHeight - axisHpx
            fun tAt(v: View, x: Float, w: Float) = v.tA + (x / w) * (v.tB - v.tA)
            fun pAt(v: View, y: Float, h: Float) = v.pA + ((h - y) / h) * (v.pB - v.pA)

            Canvas(Modifier.fillMaxSize().pointerInput(d, tool) {
                // Reinicia quando os dados ou a ferramenta mudam, para o tratamento de toque usar os valores atuais.
                var lastTapAt = 0L
                var lastTapPos = Offset.Zero
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: continue
                        val w = size.width - axisWpx; val h = size.height - axisHpx
                        when (e.type) {
                            PointerEventType.Scroll -> {
                                val dy = ch.scrollDelta.y
                                if (dy != 0f) {
                                    val f = 1.15.pow(dy.toDouble())
                                    val v = custom ?: full
                                    if (ch.position.x > w) {
                                        val p = pAt(v, ch.position.y, h)
                                        setView(v.copy(pA = p - (p - v.pA) * f, pB = p + (v.pB - p) * f))
                                    } else {
                                        val t = tAt(v, ch.position.x, w)
                                        setView(v.copy(tA = t - (t - v.tA) * f, tB = t + (v.tB - t) * f))
                                    }
                                    ch.consume()
                                }
                            }
                            PointerEventType.Move -> if (!ch.pressed) hover = ch.position
                            PointerEventType.Exit -> hover = null
                            PointerEventType.Press -> {
                                val start = ch.position
                                val startView = custom ?: full
                                val onAxis = start.x > w
                                val touch = ch.type == PointerType.Touch
                                var moved = false
                                var last = start
                                var pinchStart: Triple<Float, Float, Offset>? = null
                                var pinchView = startView
                                if (touch && tool == DrawTool.NONE) hover = start
                                ch.consume()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val down = ev.changes.filter { it.pressed }
                                    if (down.isEmpty()) break
                                    if (down.size >= 2) {
                                        // Pinça: afastar os dedos aproxima; o centro dos dedos arrasta o gráfico.
                                        val a = down[0].position; val b = down[1].position
                                        val sx = abs(a.x - b.x).coerceAtLeast(24f); val sy = abs(a.y - b.y).coerceAtLeast(24f)
                                        val c = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                                        val ps = pinchStart
                                        if (ps == null) { pinchStart = Triple(sx, sy, c); pinchView = custom ?: full }
                                        else {
                                            var v = pinchView
                                            val t0 = tAt(v, ps.third.x, w); val fx = (ps.first / sx).toDouble()
                                            v = v.copy(tA = t0 - (t0 - v.tA) * fx, tB = t0 + (v.tB - t0) * fx)
                                            val p0 = pAt(v, ps.third.y, h); val fy = (ps.second / sy).toDouble()
                                            v = v.copy(pA = p0 - (p0 - v.pA) * fy, pB = p0 + (v.pB - p0) * fy)
                                            val dt = (c.x - ps.third.x) / w * (v.tB - v.tA)
                                            val dp = (c.y - ps.third.y) / h * (v.pB - v.pA)
                                            setView(View(v.tA - dt, v.tB - dt, v.pA + dp, v.pB + dp))
                                        }
                                        moved = true; hover = null
                                        ev.changes.forEach { it.consume() }
                                        continue
                                    }
                                    val pc = down[0]
                                    last = pc.position
                                    val delta = pc.position - start
                                    if (!moved && delta.getDistance() > 6f) moved = true
                                    if (moved && pinchStart == null) {
                                        when (tool) {
                                            DrawTool.NONE -> if (onAxis) {
                                                // Arrastar a escala de preço: para baixo encolhe, para cima estica.
                                                val f = exp(delta.y / h * 2.0)
                                                val c = (startView.pA + startView.pB) / 2
                                                setView(startView.copy(pA = c - (c - startView.pA) * f, pB = c + (startView.pB - c) * f))
                                            } else {
                                                val dt = delta.x / w * (startView.tB - startView.tA)
                                                val dp = delta.y / h * (startView.pB - startView.pA)
                                                setView(View(startView.tA - dt, startView.tB - dt, startView.pA + dp, startView.pB + dp))
                                                if (touch) hover = null
                                            }
                                            DrawTool.TREND, DrawTool.ZONE -> draft = Drawing(tool,
                                                tAt(startView, start.x, w).toLong(), pAt(startView, start.y, h),
                                                tAt(startView, last.x.coerceIn(0f, w), w).toLong(), pAt(startView, last.y.coerceIn(0f, h), h))
                                            else -> {}
                                        }
                                    }
                                    pc.consume()
                                }
                                // Soltou
                                val v = custom ?: full
                                if (!moved && start.x < w && start.y < h) {
                                    val t = tAt(v, start.x, w).toLong(); val p = pAt(v, start.y, h)
                                    when (tool) {
                                        DrawTool.NONE -> {
                                            val now = ch.uptimeMillis
                                            if (now - lastTapAt < 350 && (start - lastTapPos).getDistance() < 24f) { custom = null; lastTapAt = 0L }
                                            else {
                                                lastTapAt = now; lastTapPos = start
                                                selected = hitTest(Drawings.list, start, v, w, h, u)
                                            }
                                        }
                                        DrawTool.HLINE -> { Drawings.add(Drawing(DrawTool.HLINE, t, p, t, p)); tool = DrawTool.NONE }
                                        DrawTool.TEXT -> { pendingText = t to p; noteText = "" }
                                        else -> {}
                                    }
                                }
                                draft?.let { dr ->
                                    if (abs(dr.t2 - dr.t1) > 0 || abs(dr.p2 - dr.p1) > 0) Drawings.add(dr)
                                    draft = null; tool = DrawTool.NONE
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }) {
                val w = size.width - axisWpx; val h = size.height - axisHpx
                val v = view
                fun x(t: Double) = ((t - v.tA) / (v.tB - v.tA) * w).toFloat()
                fun y(p: Double) = (h - (p - v.pA) / (v.pB - v.pA) * h).toFloat()
                clipRect(0f, 0f, w, h) {
                    val x0 = x(full.tA); val x1 = x(full.tB); val yT = y(pMax); val yB = y(d.pMin)
                    drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(x0.roundToInt(), yT.roundToInt()),
                        dstSize = IntSize((x1 - x0).roundToInt().coerceAtLeast(1), (yB - yT).roundToInt().coerceAtLeast(1)),
                        filterQuality = if (custom == null) FilterQuality.Low else FilterQuality.None)
                    // preço (fechamento das velas)
                    val path = Path()
                    d.candles.forEachIndexed { i, c ->
                        val px = x(d.t0 + (i + 0.5) * d.stepMs)
                        if (i == 0) path.moveTo(px, y(c.close)) else path.lineTo(px, y(c.close))
                    }
                    drawPath(path, Color.White, style = Stroke(1.4f * u))
                    // liquidações reais
                    real.forEach { e ->
                        val ex = x(e.recvMs.toDouble()); val ey = y(e.price)
                        if (ex < -10 || ex > w + 10 || ey < -10 || ey > h + 10) return@forEach
                        val r = (2f + sqrt(e.qtyBtc).toFloat() * 2.5f).coerceAtMost(10f)
                        drawCircle(Color.Black, r + 1.2f, Offset(ex, ey))
                        drawCircle(if (e.side == LiqSide.LONG) Q.LiqLong else Q.LiqShort, r, Offset(ex, ey))
                    }
                    // marcações
                    (Drawings.list + listOfNotNull(draft)).forEach { dr ->
                        val sel = dr == selected
                        val col = if (sel) Color.White else Q.Accent
                        val sw = (if (sel) 2.4f else 1.4f) * u
                        val a = Offset(x(dr.t1.toDouble()), y(dr.p1)); val b = Offset(x(dr.t2.toDouble()), y(dr.p2))
                        when (dr.tool) {
                            DrawTool.HLINE -> drawLine(col, Offset(0f, a.y), Offset(w, a.y), sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * u, 5f * u)))
                            DrawTool.TREND -> { drawLine(col, a, b, sw); drawCircle(col, 3f * u, a); drawCircle(col, 3f * u, b) }
                            DrawTool.ZONE -> {
                                val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y)); val sz = Size(abs(a.x - b.x), abs(a.y - b.y))
                                drawRect(Q.Accent.copy(alpha = 0.14f), tl, sz)
                                drawRect(col, tl, sz, style = Stroke(sw))
                            }
                            DrawTool.TEXT -> {
                                val lay = tm.measure(dr.text, note)
                                drawCircle(col, 3f * u, a)
                                drawRect(Color.Black.copy(alpha = 0.7f), Offset(a.x + 6f * u, a.y - lay.size.height - 2f * u),
                                    Size(lay.size.width + 8f * u, lay.size.height + 4f * u))
                                if (sel) drawRect(col, Offset(a.x + 6f * u, a.y - lay.size.height - 2f * u),
                                    Size(lay.size.width + 8f * u, lay.size.height + 4f * u), style = Stroke(1f * u))
                                drawText(lay, topLeft = Offset(a.x + 10f * u, a.y - lay.size.height))
                            }
                            DrawTool.NONE -> {}
                        }
                    }
                }
                // eixos
                drawRect(Q.Panel, Offset(w, 0f), Size(axisWpx, size.height))
                drawRect(Q.Panel, Offset(0f, h), Size(size.width, axisHpx))
                for (k in 0..7) {
                    val p = v.pA + (v.pB - v.pA) * k / 7
                    val yy = y(p)
                    if (yy >= 7f * u && yy <= h - 7f * u) drawText(tm, px(p), Offset(w + 6f * u, yy - 7f * u), axis)
                }
                val span = v.tB - v.tA
                val fmt = SimpleDateFormat(when {
                    span <= 36 * 3_600_000.0 -> "HH:mm"
                    span <= 10 * 86_400_000.0 -> "dd/MM HH:mm"
                    else -> "dd/MM/yy"
                })
                for (k in 0..5) {
                    val t = v.tA + span * k / 6
                    drawText(tm, fmt.format(Date(t.toLong())), Offset(x(t) + 2f * u, h + 2f * u), axis)
                }
                // rótulos das linhas de preço na escala
                Drawings.list.filter { it.tool == DrawTool.HLINE }.forEach { dr ->
                    val yy = y(dr.p1)
                    if (yy in 0f..h) {
                        drawRect(if (dr == selected) Color.White else Q.Accent.copy(alpha = 0.75f), Offset(w, yy - 8f * u), Size(axisWpx, 16f * u))
                        drawText(tm, px(dr.p1), Offset(w + 6f * u, yy - 7f * u), axis.copy(color = Color.Black))
                    }
                }
                price?.let { p ->
                    val yy = y(p)
                    if (yy in 0f..h) {
                        drawLine(Q.Accent.copy(alpha = 0.6f), Offset(0f, yy), Offset(w, yy), 1f)
                        drawRect(Q.Accent, Offset(w, yy - 8f * u), Size(axisWpx, 16f * u))
                        drawText(tm, px(p), Offset(w + 6f * u, yy - 7f * u), axis.copy(color = if (Look.dark) Color.Black else Color.White))
                    }
                }
                // Ajuda de navegação dentro do gráfico (some ao dar zoom ou passar o mouse), sem ocupar altura.
                if (custom == null && hover == null && tool == DrawTool.NONE) {
                    val help = tm.measure(if (small) "Pinça: zoom · arrastar: mover · toque duplo: voltar"
                        else "Roda: zoom · na escala: zoom no preço · arrastar: mover · duplo clique: voltar", axis)
                    drawRect(Color.Black.copy(alpha = 0.55f), Offset(4f * u, 4f * u), Size(help.size.width + 8f * u, help.size.height + 4f * u))
                    drawText(help, topLeft = Offset(8f * u, 6f * u))
                }
                hover?.let { hp ->
                    if (hp.x < w && hp.y < h) {
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(hp.x, 0f), Offset(hp.x, h), 1f)
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, hp.y), Offset(w, hp.y), 1f)
                        drawRect(Q.Panel2, Offset(w, hp.y - 8f * u), Size(axisWpx, 16f * u))
                        drawText(tm, px(v.pA + (h - hp.y) / h * (v.pB - v.pA)), Offset(w + 6f * u, hp.y - 7f * u), axis.copy(color = Q.Text))
                    }
                }
            }
            if (tool == DrawTool.NONE) hover?.let { hp ->
                if (hp.x < cw && hp.y < chh) {
                    val t = tAt(view, hp.x, cw); val p = pAt(view, hp.y, chh)
                    val col = ((t - d.t0) / d.stepMs).toInt(); val row = ((p - d.pMin) / d.pStep).toInt()
                    if (col in 0 until d.cols && row in 0 until d.rows) tooltip(col, row, Modifier.offset {
                        IntOffset((hp.x + 16 * u).toInt().coerceAtMost((cw - 300 * u).toInt().coerceAtLeast(0)),
                            (hp.y + 12 * u).toInt().coerceAtMost((chh - 190 * u).toInt().coerceAtLeast(0)))
                    })
                }
            }
        }
    }
}

/** Marcação mais próxima do ponto (até 8 dp), para selecionar e apagar. */
private fun hitTest(list: List<Drawing>, p: Offset, v: View, w: Float, h: Float, u: Float): Drawing? {
    fun x(t: Long) = ((t - v.tA) / (v.tB - v.tA) * w).toFloat()
    fun y(pr: Double) = (h - (pr - v.pA) / (v.pB - v.pA) * h).toFloat()
    val tol = 8f * u
    return list.lastOrNull { dr ->
        val a = Offset(x(dr.t1), y(dr.p1)); val b = Offset(x(dr.t2), y(dr.p2))
        when (dr.tool) {
            DrawTool.HLINE -> abs(p.y - a.y) < tol
            DrawTool.TREND -> segDist(p, a, b) < tol
            DrawTool.ZONE -> p.x in (minOf(a.x, b.x) - tol)..(maxOf(a.x, b.x) + tol) && p.y in (minOf(a.y, b.y) - tol)..(maxOf(a.y, b.y) + tol)
            DrawTool.TEXT -> p.x in (a.x - tol)..(a.x + (dr.text.length * 7f + 16f) * u) && p.y in (a.y - 20f * u)..(a.y + tol)
            DrawTool.NONE -> false
        }
    }
}
