package com.cunhac.btcradar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.engine.AlertKind
import com.cunhac.btcradar.engine.Analytics
import com.cunhac.btcradar.engine.CORE_VENUES
import com.cunhac.btcradar.engine.FlowBucket
import com.cunhac.btcradar.engine.TerminalState
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

// =====================================================================
// MARKET FLOW CORRELATION
// =====================================================================
private val CORR_WINDOWS = listOf("20 min" to 20, "40 min" to 40, "60 min" to 60)

@Composable
fun CorrelationTab(ctx: AppContext, s: TerminalState) {
    var wIdx by remember { mutableIntStateOf(SettingsStore.i("co.w", 2)) }
    val n = CORR_WINDOWS[wIdx].second
    val buckets = remember(s.tick / 2, wIdx) {
        val now = System.currentTimeMillis()
        val from = now / 60_000 * 60_000 - (n - 1) * 60_000L
        val cum = ctx.hub.flow.cvdSeries(n * 60_000L + 60_000, now).map { it.first to it.second.sum() }
        val perSec = cum.mapIndexed { i, p -> p.first to (p.second - if (i == 0) 0.0 else cum[i - 1].second) }
        Analytics.buckets(ctx.hub.samples(n * 60_000L + 120_000), perSec, ctx.hub.liqs.since(from - 60_000), from, 60_000L, n)
    }
    // Só minutos com preço (desde a abertura do app) entram na correlação; zeros sem dados distorceriam o r.
    val price = buckets.map { it.priceChgPct }
    val cvd = buckets.map { b -> if (b.priceChgPct == null) null else b.cvdDeltaBtc }
    val oi = buckets.map { b -> if (b.priceChgPct == null) null else b.oiChgBtc }
    val liq = buckets.map { b -> if (b.priceChgPct == null) null else b.liqNetBtc }
    val pairs = listOf(
        Triple("Preço × CVD", Analytics.pearson(price, cvd), "Positiva = o preço anda junto com a agressão compradora/vendedora."),
        Triple("Preço × open interest", Analytics.pearson(price, oi), "Positiva = alta com abertura de posições; negativa = alta com fechamento."),
        Triple("Preço × liquidações", Analytics.pearson(price, liq), "Positiva = alta acompanhada de shorts liquidados (squeeze)."),
        Triple("CVD × open interest", Analytics.pearson(cvd, oi), "Positiva = compra agressora abrindo posições."),
        Triple("CVD × liquidações", Analytics.pearson(cvd, liq), "Positiva = compras forçadas de shorts liquidados entrando no CVD."),
    )
    Panes(Modifier.fillMaxSize(), Pane(1.6f) { pm ->
        Column(pm, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Controls {
                Chips("Janela", CORR_WINDOWS.map { it.first }, wIdx) { wIdx = it; SettingsStore.put("co.w", it) }
                T("Baldes de 1 minuto · preço, CVD, open interest e liquidações lado a lado", Q.Muted, 11, wrap = compact)
            }
            Section("Preço, fluxo, posições e liquidações por minuto", Modifier.fillMaxWidth().then(grow(1f, 520.dp))) {
                if (buckets.count { it.priceClose != null } < 3) Empty("Juntando dados", "Precisa de alguns minutos de dados desde a abertura do app (guarda até 1 h)")
                else CorrelationChart(buckets, Modifier.fillMaxSize())
            }
        }
    }, Pane(1f) { pm ->
        Column(pm.then(if (compact) Modifier else Modifier.verticalScroll(rememberScrollState())), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Correlação (Pearson) na janela", Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pairs.forEach { (name, r, hint) -> CorrRow(name, r, hint) }
                }
            }
            Section("Leitura dos últimos 5 minutos", Modifier.fillMaxWidth()) {
                Column {
                    val last = buckets.takeLast(5)
                    val dp = last.mapNotNull { it.priceChgPct }.sum()
                    val dc = last.sumOf { it.cvdDeltaBtc }
                    val doi = last.mapNotNull { it.oiChgBtc }.let { if (it.isEmpty()) null else it.sum() }
                    val ll = last.sumOf { it.liqLongBtc }; val ls = last.sumOf { it.liqShortBtc }
                    KV("Preço", pct(dp, 3), if (dp >= 0) Q.Buy else Q.Sell)
                    KV("CVD", String.format(BR, "%+.2f BTC", dc), if (dc >= 0) Q.Buy else Q.Sell)
                    KV("Open interest", doi?.let { String.format(BR, "%+,.0f BTC", it) } ?: "aguardando as 3 fontes", if ((doi ?: 0.0) >= 0) Q.Text else Q.Muted)
                    KV("Liquidações LONG / SHORT", "${num(ll, 2)} / ${num(ls, 2)} BTC")
                    Spacer(Modifier.height(6.dp))
                    T(regime(dp, dc, doi, ll, ls), Q.Text, 12, wrap = true)
                    Footnote("Leitura automática a partir dos números acima; é inferência, não prova de quem abriu ou fechou posição.", Modifier.padding(top = 4.dp))
                }
            }
            Footnote("Correlação com menos de 8 minutos de dados aparece como \"sem dados\". |r| ≥ 0,7 forte, ≥ 0,4 moderada, ≥ 0,2 fraca. " +
                "OI somado só conta quando Binance, Bybit e OKX reportaram. Liquidações = 6 corretoras (positivo = mais shorts liquidados).")
        }
    })
}

private fun regime(dp: Double, dc: Double, doi: Double?, ll: Double, ls: Double): String {
    val up = dp > 0.03; val down = dp < -0.03
    val oiUp = doi != null && doi > 0; val oiDown = doi != null && doi < 0
    return when {
        up && dc > 0 && oiUp -> "Alta com compra agressora e open interest subindo: compatível com novas posições compradas puxando o preço."
        up && oiDown && ls > ll -> "Alta com open interest caindo e shorts liquidados: compatível com short squeeze."
        up && dc < 0 -> "Alta com CVD negativo: o preço sobe apesar da venda agressora (compra passiva absorvendo)."
        down && dc < 0 && oiUp -> "Queda com venda agressora e open interest subindo: compatível com novas posições vendidas."
        down && oiDown && ll > ls -> "Queda com open interest caindo e longs liquidados: compatível com cascata de liquidação de longs."
        down && dc > 0 -> "Queda com CVD positivo: o preço cai apesar da compra agressora (venda passiva absorvendo)."
        else -> "Sem regime claro: preço lateral ou sinais misturados."
    }
}

@Composable
private fun CorrRow(name: String, r: Double?, hint: String) {
    val c = when { r == null -> Q.Muted; r > 0.2 -> Q.Buy; r < -0.2 -> Q.Sell; else -> Q.Muted }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(name, Q.Text, 12, modifier = Modifier.weight(1f))
            T(r?.let { String.format(BR, "%+.2f", it) } ?: "—", c, 13, bold = true, monoFont = true, modifier = Modifier.width(56.dp))
            T(Analytics.corrLabel(r), c, 11, modifier = Modifier.width(120.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val mid = size.width / 2
            drawRect(Q.Panel2, Offset.Zero, size)
            r?.let { v -> val w = (v.coerceIn(-1.0, 1.0) * mid).toFloat(); if (w >= 0) drawRect(c, Offset(mid, 0f), Size(w, size.height)) else drawRect(c, Offset(mid + w, 0f), Size(-w, size.height)) }
            drawLine(Q.Muted, Offset(mid, 0f), Offset(mid, size.height), 1f)
        }
        T(hint, Q.Muted, 10, wrap = true)
    }
}

@Composable
private fun CorrelationChart(b: List<FlowBucket>, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val buy = Q.Buy; val sell = Q.Sell; val text = Q.Text; val muted = Q.Muted; val line = Q.Line; val liqL = Q.LiqLong; val liqS = Q.LiqShort; val accent = Q.Accent
    Canvas(modifier) {
        val u = density
        val labelW = 110f * u
        val w = size.width - labelW
        val gap = 10f * u
        val bandH = (size.height - gap * 3 - 16f * u) / 4
        val cw = w / b.size
        val st = TextStyle(color = muted, fontSize = 10.sp)
        val vst = TextStyle(color = text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        fun band(i: Int) = i * (bandH + gap)
        fun bars(top: Float, vals: List<Double?>, pos: Color, neg: Color) {
            val mx = vals.maxOfOrNull { abs(it ?: 0.0) }?.takeIf { it > 0 } ?: 1.0
            val mid = top + bandH / 2
            drawLine(line, Offset(labelW, mid), Offset(size.width, mid), 1f)
            vals.forEachIndexed { i, v -> v ?: return@forEachIndexed
                val hh = (v / mx * (bandH / 2 - 2)).toFloat()
                drawRect(if (v >= 0) pos else neg, Offset(labelW + i * cw + 1f, if (hh >= 0) mid - hh else mid), Size((cw - 2f).coerceAtLeast(1f), abs(hh)))
            }
        }
        // 1. preço
        val pts = b.mapIndexedNotNull { i, x -> x.priceClose?.let { i to it } }
        if (pts.size >= 2) {
            val lo = pts.minOf { it.second }; val hi = pts.maxOf { it.second }; val span = (hi - lo).takeIf { it > 0 } ?: 1.0
            val path = Path()
            pts.forEachIndexed { k, (i, p) ->
                val x = labelW + (i + 0.5f) * cw; val y = (band(0) + bandH - (p - lo) / span * (bandH - 4) - 2).toFloat()
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, accent, style = Stroke(1.8f))
            drawText(tm, px(hi), Offset(2f, band(0) + 16f * u), st); drawText(tm, px(lo), Offset(2f, band(0) + bandH - 14f * u), st)
        }
        drawText(tm, "PREÇO", Offset(2f, band(0)), vst)
        // 2. CVD
        drawText(tm, "CVD (BTC/min)", Offset(2f, band(1)), vst)
        bars(band(1), b.map { it.cvdDeltaBtc }, buy, sell)
        // 3. OI
        drawText(tm, "Δ OI (BTC/min)", Offset(2f, band(2)), vst)
        bars(band(2), b.map { it.oiChgBtc }, text.copy(alpha = 0.7f), muted)
        // 4. liquidações: shorts para cima (azul), longs para baixo (laranja)
        drawText(tm, "LIQUIDAÇÕES", Offset(2f, band(3)), vst)
        drawText(tm, "▲ short  ▼ long", Offset(2f, band(3) + 16f * u), st)
        val mxL = b.maxOfOrNull { max(it.liqLongBtc, it.liqShortBtc) }?.takeIf { it > 0 } ?: 1.0
        val mid = band(3) + bandH / 2
        drawLine(line, Offset(labelW, mid), Offset(size.width, mid), 1f)
        b.forEachIndexed { i, x ->
            val hs = (x.liqShortBtc / mxL * (bandH / 2 - 2)).toFloat(); val hl = (x.liqLongBtc / mxL * (bandH / 2 - 2)).toFloat()
            drawRect(liqS, Offset(labelW + i * cw + 1f, mid - hs), Size((cw - 2f).coerceAtLeast(1f), hs))
            drawRect(liqL, Offset(labelW + i * cw + 1f, mid), Size((cw - 2f).coerceAtLeast(1f), hl))
        }
        val tf = SimpleDateFormat("HH:mm")
        val every = max(1, b.size / 8)
        for (i in b.indices step every) drawText(tm, tf.format(Date(b[i].startMs)), Offset(labelW + i * cw, size.height - 14f * u), st)
    }
}

// =====================================================================
// LIQUIDITY HISTORY
// =====================================================================
private val LH_PERIODS = listOf("1h" to 3_600_000L, "6h" to 21_600_000L, "24h" to 86_400_000L, "7 dias" to 604_800_000L, "30 dias" to 2_592_000_000L)
private val END_STATUSES = setOf("DISAPPEARED", "POSSIBLY_EXECUTED", "EXECUTION_CORROBORATED")

@Composable
fun LiquidityHistoryTab(ctx: AppContext, s: TerminalState) {
    var pIdx by remember { mutableIntStateOf(SettingsStore.i("lh.p", 2)) }
    var vIdx by remember { mutableIntStateOf(0) }
    var sideIdx by remember { mutableIntStateOf(0) }
    var minPeak by remember { mutableStateOf(SettingsStore.d("lh.min", 3.0)) }
    val period = LH_PERIODS[pIdx].second
    val all = remember(s.tick / 10, pIdx) { val now = System.currentTimeMillis(); ctx.db.wallEvents(now - period, now) }
    val ev = all.filter { e ->
        e.status in END_STATUSES && e.peak >= minPeak && (vIdx == 0 || e.venue == CORE_VENUES[vIdx - 1]) &&
            (sideIdx == 0 || (sideIdx == 1) == e.bid)
    }
    val price = s.price
    val bin = price?.let { max(10.0, floor(it * 0.001 / 10) * 10) } ?: 50.0
    val levels = remember(ev, bin) {
        ev.groupBy { floor(it.price / bin) * bin }.map { (p, l) ->
            LhLevel(p, l.size, l.sumOf { it.peak }, l.count { it.status == "EXECUTION_CORROBORATED" }, l.count { it.status == "DISAPPEARED" }, l.maxOf { it.tsMs }, l.count { it.bid } >= l.size / 2.0)
        }.sortedByDescending { it.count * 1000 + it.peakSum }.take(40)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Controls {
            Chips("Período", LH_PERIODS.map { it.first }, pIdx) { pIdx = it; SettingsStore.put("lh.p", it) }
            Chips("Corretora", listOf("TODAS") + CORE_VENUES.map { it.label.uppercase() }, vIdx) { vIdx = it }
            Chips("Lado", listOf("AMBOS", "COMPRA", "VENDA"), sideIdx) { sideIdx = it }
            NumField("Pico ≥ (BTC)", minPeak, 56.dp) { it?.let { v -> minPeak = v; SettingsStore.put("lh.min", v) } }
            T("${ev.size} paredes encerradas · ${all.size} eventos gravados no período", Q.Muted, 11)
        }
        Panes(grow(1f), Pane(1.5f, 420.dp) { m ->
            Section("Onde as paredes estiveram e como terminaram", m) {
                if (ev.isEmpty()) Empty("Nenhuma parede encerrada no período com esses filtros",
                    "O histórico vem do banco local: paredes que saíram do livro desde que o app foi instalado. Reduza o pico mínimo ou aumente o período.")
                else WallScatter(ev, period, price, Modifier.fillMaxSize())
            }
        }, Pane(1f) { pm ->
            Column(pm, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Como terminaram", Modifier.fillMaxWidth()) {
                    Column {
                        val tot = ev.size.coerceAtLeast(1)
                        listOf("EXECUTION_CORROBORATED" to "Execução confirmada pelos trades", "POSSIBLY_EXECUTED" to "Preço atravessou (não confirmada)",
                            "DISAPPEARED" to "Retirada sem execução").forEach { (k, lbl) ->
                            val n = ev.count { it.status == k }
                            val c = when (k) { "EXECUTION_CORROBORATED" -> Q.Accent; "POSSIBLY_EXECUTED" -> Q.Warn; else -> Q.Muted }
                            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                                T(lbl, Q.Text, 11, modifier = Modifier.width(210.dp))
                                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 5.dp)) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth((n.toFloat() / tot).coerceIn(0.01f, 1f)).background(c))
                                }
                                T("$n · ${num(n * 100.0 / tot, 0)}%", Q.Text, 11, monoFont = true, modifier = Modifier.width(80.dp).padding(start = 6.dp))
                            }
                        }
                    }
                }
                Section("Níveis onde a liquidez mais aparece (faixas de ${px(bin)} US$)", Modifier.fillMaxWidth().then(grow(1f, 420.dp)), minWidth = 470.dp) {
                    Column {
                        Th("FAIXA" to 80, "LADO" to 52, "VEZES" to 50, "PICO SOMADO" to 90, "EXECUT." to 56, "RETIR." to 52, "ÚLTIMA" to 0)
                        if (levels.isEmpty()) Empty("Sem níveis no período")
                        LazyColumn {
                            items(levels) { l ->
                                Td(Triple(px(l.price), 80, Q.Text), Triple(if (l.bid) "COMPRA" else "VENDA", 52, if (l.bid) Q.Buy else Q.Sell),
                                    Triple(l.count.toString(), 50, Q.Text), Triple(num(l.peakSum, 1), 90, Q.Text), Triple(l.executed.toString(), 56, Q.Accent),
                                    Triple(l.pulled.toString(), 52, Q.Muted), Triple(hhmmss(l.lastMs), 0, Q.Muted))
                            }
                        }
                    }
                }
            }
        })
        Footnote("Cada ponto é uma parede que saiu do livro: posição = hora e preço; tamanho = pico em BTC; cor = lado (verde compra, vermelho venda); " +
            "anel laranja = execução confirmada, anel amarelo = preço atravessou, sem anel = retirada. Níveis que se repetem costumam ser suporte/resistência defendidos.")
    }
}

private data class LhLevel(val price: Double, val count: Int, val peakSum: Double, val executed: Int, val pulled: Int, val lastMs: Long, val bid: Boolean)

@Composable
private fun WallScatter(ev: List<WallRecord>, period: Long, price: Double?, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val buy = Q.Buy; val sell = Q.Sell; val muted = Q.Muted; val accent = Q.Accent; val warn = Q.Warn; val line = Q.Line; val panel2 = Q.Panel2
    Canvas(modifier) {
        val u = density
        val axisW = 66f * u; val axisH = 18f * u
        val w = size.width - axisW; val h = size.height - axisH
        val now = System.currentTimeMillis(); val t0 = now - period
        val lo = (ev.minOf { it.price } * 0.999).let { p -> price?.let { minOf(p, it) } ?: p }
        val hi = (ev.maxOf { it.price } * 1.001).let { p -> price?.let { maxOf(p, it) } ?: p }
        fun x(t: Long) = ((t - t0).toFloat() / period) * w
        fun y(p: Double) = (h - (p - lo) / (hi - lo) * h).toFloat()
        val st = TextStyle(color = muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        for (k in 0..5) { val p = lo + (hi - lo) * k / 5; drawLine(line, Offset(0f, y(p)), Offset(w, y(p)), 0.6f) }
        val mx = ev.maxOf { it.peak }
        ev.asReversed().forEach { e ->
            val r = (2.5f + sqrt(e.peak / mx).toFloat() * 11f) * u
            val c = (if (e.bid) buy else sell).copy(alpha = 0.55f)
            val o = Offset(x(e.tsMs).coerceIn(r, w - r), y(e.price))
            drawCircle(c, r, o)
            when (e.status) {
                "EXECUTION_CORROBORATED" -> drawCircle(accent, r + 1.5f, o, style = Stroke(1.6f))
                "POSSIBLY_EXECUTED" -> drawCircle(warn, r + 1.5f, o, style = Stroke(1.2f))
            }
        }
        price?.let { p -> drawLine(accent.copy(alpha = 0.6f), Offset(0f, y(p)), Offset(w, y(p)), 1f) }
        drawRect(panel2, Offset(w, 0f), Size(axisW, h))
        for (k in 0..5) { val p = lo + (hi - lo) * k / 5; drawText(tm, px(p), Offset(w + 4f * u, (y(p) - 7f * u).coerceIn(0f, h - 14f * u)), st) }
        drawRect(panel2, Offset(0f, h), Size(size.width, axisH))
        val fmt = SimpleDateFormat(if (period <= 86_400_000L) "HH:mm" else "dd/MM HH:mm")
        for (k in 0..5) { val t = t0 + period * k / 6; drawText(tm, fmt.format(Date(t)), Offset(x(t) + 2f * u, h + 2f * u), st) }
    }
}

// =====================================================================
// ALERTAS (Telegram / e-mail)
// =====================================================================
@Composable
fun AlertsTab(ctx: AppContext, s: TerminalState) {
    val cfg by ctx.alerts.config.collectAsState()
    val sent by ctx.alerts.sent.collectAsState()
    var c by remember { mutableStateOf(cfg) }
    val dirty = c != cfg
    Panes(Modifier.fillMaxSize(), Pane(1f) { pm ->
        Column(pm.then(if (compact) Modifier else Modifier.verticalScroll(rememberScrollState())), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Notificações ${if (Platform.isMobile) "neste celular" else "do Windows"}", Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle(if (Platform.isMobile) "Mostrar notificação no celular" else "Mostrar notificação na bandeja do Windows", c.deviceOn) { c = c.copy(deviceOn = it) }
                    if (Platform.backgroundSupported) {
                        var bg by remember { mutableStateOf(Platform.backgroundOn) }
                        Toggle("Continuar monitorando com o app fechado", bg) { bg = it; Platform.setBackground(it) }
                        Footnote("Mantém as conexões e os alertas ativos em segundo plano, com uma notificação fixa. Usa mais bateria e dados; desligue quando não precisar.")
                    }
                    if (Platform.isMobile && !Platform.notifySupported) Footnote("Permita notificações do Radar BTC nas configurações do Android.")
                }
            }
            Section("O que avisar", Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlertKind.entries.forEach { k ->
                        if (compact) Column {
                            Toggle(k.label, k in c.kinds) { on -> c = c.copy(kinds = if (on) c.kinds + k else c.kinds - k) }
                            T(kindHint(k, c), Q.Muted, 11, wrap = true, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                        } else Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(260.dp)) { Toggle(k.label, k in c.kinds) { on -> c = c.copy(kinds = if (on) c.kinds + k else c.kinds - k) } }
                            T(kindHint(k, c), Q.Muted, 11, modifier = Modifier.weight(1f))
                        }
                    }
                    Controls {
                        NumField("Zona: distância ≤ %", c.zonePct, 50.dp) { it?.let { v -> c = c.copy(zonePct = v) } }
                        NumField("Zona: tamanho ≥ BTC", c.zoneMinBtc, 64.dp) { it?.let { v -> c = c.copy(zoneMinBtc = v) } }
                        NumField("Repetir o mesmo aviso após (min)", c.cooldownMin, 44.dp) { it?.let { v -> c = c.copy(cooldownMin = v) } }
                    }
                    Footnote("Liquidação EXTREME usa o limite da aba LIQUIDATION RADAR; parede grande = classe LARGE ou EXTREME da aba LIQUIDITY WALLS; " +
                        "mudança de sinal = janela de 5 min da aba LONG & SHORT FLOW. Máximo de 20 envios por minuto.")
                }
            }
            Section("Telegram", Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle("Enviar pelo Telegram", c.telegramOn) { c = c.copy(telegramOn = it) }
                    TextSetting("Token do bot", c.telegramToken, if (compact) 210.dp else 420.dp, secret = true) { c = c.copy(telegramToken = it) }
                    TextSetting("Chat ID", c.telegramChat, 200.dp) { c = c.copy(telegramChat = it) }
                    Footnote("Crie um bot com o @BotFather, mande uma mensagem para ele e pegue o chat ID em api.telegram.org/bot<token>/getUpdates.")
                }
            }
            Section("E-mail (SMTP)", Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle("Enviar por e-mail", c.mailOn) { c = c.copy(mailOn = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextSetting("Servidor", c.smtpHost, if (compact) 170.dp else 180.dp) { c = c.copy(smtpHost = it) }
                        TextSetting("Porta", c.smtpPort.toString(), 60.dp) { t -> t.toIntOrNull()?.let { c = c.copy(smtpPort = it) } }
                    }
                    TextSetting("Usuário (e-mail de envio)", c.smtpUser, if (compact) 250.dp else 280.dp) { c = c.copy(smtpUser = it) }
                    TextSetting("Senha de app", c.smtpPass, 220.dp, secret = true) { c = c.copy(smtpPass = it) }
                    TextSetting("Enviar para", c.mailTo, if (compact) 250.dp else 280.dp) { c = c.copy(mailTo = it) }
                    Footnote("Gmail: smtp.gmail.com, porta 465, com uma senha de app (Conta Google → Segurança → Senhas de app). Porta 587 usa STARTTLS.")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(if (dirty) "Salvar alterações" else "Salvo", dirty) { ctx.alerts.update(c) }
                ActionButton("Enviar teste", true) { ctx.alerts.update(c); ctx.alerts.test() }
            }
            T(if (Platform.isMobile) "Token e senha ficam guardados só neste celular (preferências do app), sem criptografia própria."
              else "Token e senha ficam guardados só neste computador (registro do usuário do Windows), sem criptografia própria.", Q.Muted, 11, wrap = true)
        }
    }, Pane(0.9f, 420.dp) { m ->
        Section("Avisos enviados", m) {
            Column {
                if (sent.isEmpty()) Empty("Nenhum aviso enviado nesta sessão", if (!cfg.telegramOn && !cfg.mailOn && !cfg.deviceOn) "Ligue um canal de aviso e salve" else "Os avisos aparecem aqui com o resultado de cada envio")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sent) { a ->
                        Column(Modifier.fillMaxWidth().background(Q.Panel2).padding(8.dp)) {
                            Row { T(hhmmss(a.timeMs), Q.Muted, 11, monoFont = true); Spacer(Modifier.width(8.dp)); T(a.kind.label, Q.Accent, 11, bold = true) }
                            T(a.text, Q.Text, 11, wrap = true)
                            T(a.result, if (a.ok) Q.Buy else Q.Sell, 10, wrap = true)
                        }
                    }
                }
            }
        }
    })
}

private fun kindHint(k: AlertKind, c: AlertConfig) = when (k) {
    AlertKind.LIQ_EXTREME -> "Uma liquidação real acima do limite EXTREME em qualquer das 6 corretoras"
    AlertKind.WALL_PULLED -> "Parede LARGE/EXTREME saiu do livro (retirada ou executada)"
    AlertKind.WALL_SIGNAL -> "Parede absorvendo execução ou com reposição tipo iceberg"
    AlertKind.SIGNAL_CHANGE -> "Fluxo inferido muda de LONG para SHORT ou o contrário"
    AlertKind.DIVERGENCE -> "Preço e CVD divergem em 5 min, 15 min ou 1 h"
    AlertKind.HEAT_ZONE -> String.format(BR, "Preço a até %.2f%% de uma zona do heatmap com ≥ %,.0f BTC", c.zonePct, c.zoneMinBtc)
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) =
    Text(label, color = if (enabled) Q.Accent else Q.Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
        modifier = Modifier.border(1.dp, if (enabled) Q.Accent else Q.Line).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))

@Composable
private fun TextSetting(label: String, value: String, width: Dp, secret: Boolean = false, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Column {
        T(label, Q.Muted, 11)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, onChange, singleLine = true, textStyle = mono,
                visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
                cursorBrush = SolidColor(Q.Accent),
                modifier = Modifier.width(width).background(Q.Panel2).border(1.dp, Q.Line).padding(horizontal = 6.dp, vertical = 5.dp))
            if (secret) T(if (show) "ocultar" else "mostrar", Q.Accent, 11, modifier = Modifier.padding(start = 8.dp).clickable { show = !show })
        }
    }
}
