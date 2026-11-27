package com.cunhac.btcradar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.cunhac.btcradar.engine.CalEvent
import com.cunhac.btcradar.engine.Confluence
import com.cunhac.btcradar.engine.EconSeries
import com.cunhac.btcradar.engine.MacroDir
import com.cunhac.btcradar.engine.MacroEvents
import com.cunhac.btcradar.engine.MacroFactor
import com.cunhac.btcradar.engine.MacroIntensity
import com.cunhac.btcradar.engine.MacroNews
import com.cunhac.btcradar.engine.NewsCategory
import java.text.SimpleDateFormat
import java.util.Date

private val DT = SimpleDateFormat("dd/MM HH:mm")
private val WD: SimpleDateFormat get() = SimpleDateFormat("EEE dd/MM HH:mm", BR)

private fun dirColor(d: MacroDir) = when (d) { MacroDir.FAVORABLE -> Q.Buy; MacroDir.UNFAVORABLE -> Q.Sell; MacroDir.NEUTRAL -> Q.Text; MacroDir.UNDETERMINED -> Q.Muted }
private fun ageTxt(now: Long, t: Long): String { val m = (now - t) / 60_000; return when { m < 1 -> "agora"; m < 60 -> "há $m min"; m < 1440 -> "há ${m / 60} h"; else -> "há ${m / 1440} d" } }
private fun f2(v: Double?, d: Int = 2) = if (v == null) "—" else String.format(BR, "%,.${d}f", v)
@Composable private fun scroll(pm: Modifier, compact: Boolean) = if (compact) pm else pm.verticalScroll(rememberScrollState())

/** RADAR MACRO-GEOPOLÍTICO BTC: score, indicadores, calendário, notícias e leitura integrada com o order flow. */
@Composable
fun MacroTab(ctx: AppContext) {
    val m = ctx.macro
    val s by m.state.collectAsState()
    if (s.updatedMs == 0L) {
        Section("Radar macro", if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp)) {
                Empty("Coletando dados macro…", "Mercado americano, juros, inflação, emprego, calendário e notícias. A primeira coleta leva cerca de 1 minuto.")
            }
        }
        return
    }
    val c = compact
    Panes(Modifier.fillMaxSize(),
        Pane(1.1f, null, "Visão geral", 400.dp) { pm -> Column(scroll(pm, c), verticalArrangement = Arrangement.spacedBy(8.dp)) { Overview(s) } },
        Pane(1f, null, "Indicadores", 400.dp) { pm -> Column(scroll(pm, c), verticalArrangement = Arrangement.spacedBy(8.dp)) { Indicators(s) } },
        Pane(1f, null, "Mais dados", 400.dp) { pm -> Column(scroll(pm, c), verticalArrangement = Arrangement.spacedBy(8.dp)) { MoreData(s) } },
        Pane(1f, null, "Calendário", 380.dp) { pm -> Column(scroll(pm, c), verticalArrangement = Arrangement.spacedBy(8.dp)) { Calendar(s) } },
        Pane(1.1f, null, "Notícias", 400.dp) { pm -> Column(scroll(pm, c), verticalArrangement = Arrangement.spacedBy(8.dp)) { News(s) } },
        Pane(0.9f, null, "Ajustes", 360.dp) { pm -> Column(scroll(pm, c), verticalArrangement = Arrangement.spacedBy(8.dp)) { Settings(m, s) } },
        Pane(1f, null, "Fontes", 380.dp) { pm -> Column(scroll(pm, c), verticalArrangement = Arrangement.spacedBy(8.dp)) { Sources(m, s) } },
    )
}

// ------------------------------------------------------------------ visão geral

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Overview(s: MacroState) {
    val sc = s.score
    Section("BTC Macro Sentiment", Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                T(sc?.score?.let { String.format(BR, "%+d", it) } ?: "—", dirColor(sc?.state ?: MacroDir.UNDETERMINED), 34, bold = true, monoFont = true)
                T("  de −100 a +100", Q.Muted, 11, modifier = Modifier.padding(bottom = 6.dp))
            }
            T(sc?.label ?: "Sem dados", Q.Text, 14, bold = true)
            ScoreBar(sc?.score)
            T("Confiança ${sc?.confidence ?: 0}% (parte dos pesos com dado válido)", Q.Muted, 11, wrap = true)
            if (sc != null && sc.missing.isNotEmpty()) T("Sem dado: " + sc.missing.joinToString(", "), Q.Warn, 11, wrap = true)
            Footnote("Mede se as condições macro estão favoráveis ou desfavoráveis ao BTC agora. Não é previsão de preço.", Modifier.padding(top = 4.dp))
        }
    }
    s.integrated?.let { iv ->
        Section("Leitura integrada · macro + order flow", Modifier.fillMaxWidth()) {
            Column {
                KV("Cenário macro", iv.macro.label, dirColor(iv.macro))
                KV("Order flow (15 min)", iv.flow.label, when (iv.flow.name) { "BUYER" -> Q.Buy; "SELLER" -> Q.Sell; else -> Q.Text })
                KV("Liquidações (1 h)", iv.liq.label, when (iv.liq.name) { "LONG" -> Q.LiqLong; "SHORT" -> Q.LiqShort; else -> Q.Text })
                KV("Confluência", iv.confluence.label, when (iv.confluence) { Confluence.HIGH -> Q.Accent; Confluence.MEDIUM -> Q.Text; Confluence.LOW -> Q.Muted })
                s.flow?.let { f ->
                    T(String.format(BR, "Delta 15 min %s BTC · liquidações 1 h: %s BTC LONG / %s BTC SHORT · absorções: %d na compra, %d na venda",
                        f.deltaBtc15m?.let { signed(it, 1) } ?: "—", num(f.longLiqBtc1h, 1), num(f.shortLiqBtc1h, 1), f.bidAbsorptions, f.askAbsorptions),
                        Q.Muted, 11, wrap = true, modifier = Modifier.padding(top = 4.dp))
                }
                T(iv.reading, Q.Text, 12, wrap = true, modifier = Modifier.padding(top = 6.dp))
                T(com.cunhac.btcradar.engine.MacroConfluence.NOTE, Q.Muted, 11, wrap = true)
            }
        }
    }
    val now = s.updatedMs
    val next = s.calendar.firstOrNull { it.high && it.timeMs > now }
    val fomc = s.fomc.firstOrNull { it > now }
    Section("Próximos eventos de alto impacto", Modifier.fillMaxWidth()) {
        Column {
            if (next == null && fomc == null) T("Nenhum evento de alto impacto nesta semana no calendário.", Q.Muted, 12, wrap = true)
            next?.let { e ->
                T(e.title, Q.Text, 13, bold = true, wrap = true)
                T("${WD.format(Date(e.timeMs))} (horário local) · faltam ${until(e.timeMs - now)}", Q.Muted, 11, wrap = true)
                T("Expectativa ${e.forecast.ifBlank { "—" }} · anterior ${e.previous.ifBlank { "—" }}", Q.Muted, 11, wrap = true)
            }
            fomc?.let { T("Próxima decisão do FOMC: ${WD.format(Date(it))} (faltam ${until(it - now)})", Q.Text, 12, wrap = true, modifier = Modifier.padding(top = 6.dp)) }
            s.fedOdds.firstOrNull()?.outcomes?.firstOrNull()?.let { (n, p) ->
                T(L("Mercado de previsões (Kalshi)") + ": " + L(fedOutcome(n)) + String.format(BR, " · %.0f%%", p * 100), Q.Muted, 12, wrap = true, tr = false)
            }
            s.fearGreed?.let { g -> T(L("Sentimento cripto (Fear & Greed)") + ": ${g.value} · " + L(fngLabel(g.label)), Q.Muted, 12, wrap = true, tr = false) }
        }
    }
    Section("Fatores do score", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (sc?.factors ?: emptyList()).forEach { f ->
                Column(Modifier.fillMaxWidth().background(Q.Panel2).padding(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(f.factor.label, Q.Text, 12, bold = true, wrap = true, modifier = Modifier.weight(1f))
                        T("peso ${f.weight}", Q.Muted, 11, modifier = Modifier.padding(horizontal = 6.dp))
                        T(if (f.value == null) "sem dado" else String.format(BR, "%+.1f pts", f.points),
                            if (f.value == null) Q.Muted else if (f.points >= 0.5) Q.Buy else if (f.points <= -0.5) Q.Sell else Q.Text, 12, monoFont = true)
                    }
                    T(f.reason + (f.asOf?.let { " · ref. $it" } ?: ""), Q.Muted, 11, wrap = true)
                }
            }
            Footnote("Contribuição = leitura do fator (−1 a +1) × peso ÷ soma dos pesos com dado × 100. Regras e pesos: aba Ajustes e docs/MACRO.md.")
        }
    }
}

private fun until(ms: Long): String { val m = ms / 60_000; return when { m < 60 -> "$m min"; m < 1440 -> "${m / 60} h ${m % 60} min"; else -> "${m / 1440} d ${(m % 1440) / 60} h" } }

/** Barra de −100 a +100 com marca no zero e no valor (cor da faixa). */
@Composable
private fun ScoreBar(score: Int?) {
    val neg = Q.Sell; val pos = Q.Buy; val line = Q.Line; val txt = Q.Text
    Canvas(Modifier.fillMaxWidth().height(14.dp).padding(vertical = 4.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(line, Offset.Zero, Size(w, h), CornerRadius(h / 2))
        drawRect(txt.copy(alpha = 0.5f), Offset(w / 2 - 1, 0f), Size(2f, h))
        if (score != null) {
            val x = w / 2 + w / 2 * score / 100f
            val col = if (score >= 0) pos else neg
            val left = minOf(x, w / 2); val right = maxOf(x, w / 2)
            drawRoundRect(col, Offset(left, 0f), Size((right - left).coerceAtLeast(2f), h), CornerRadius(h / 2))
        }
    }
}

// ------------------------------------------------------------------ indicadores

@Composable
private fun Indicators(s: MacroState) {
    val now = s.updatedMs
    Section("Mercado americano", Modifier.fillMaxWidth(), minWidth = 360.dp) {
        Column {
            Row { H("Ativo", 1f); H("Último", 0.8f); H("Dia", 0.6f); H("5 pregões", 0.7f) }
            listOf("SPX", "NDX", "DJI", "VIX", "DXY", "TNX", "GOLD", "WTI", "BRENT", "BTC").forEach { k ->
                val ms = s.markets[k]
                val unit = ms?.unit ?: ""
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(ms?.label ?: k, Q.Text, 12, wrap = true, modifier = Modifier.weight(1f))
                    T(ms?.last?.let { f2(it, if (k == "TNX") 3 else 2) + unit } ?: "—", Q.Text, 12, monoFont = true, modifier = Modifier.weight(0.8f))
                    val d = if (k == "TNX") ms?.dayChange?.let { it * 100 } else ms?.dayPct
                    T(if (d == null) "—" else if (k == "TNX") String.format(BR, "%+.0f bp", d) else pct(d), chg(d), 12, monoFont = true, modifier = Modifier.weight(0.6f))
                    val w = if (k == "TNX") ms?.changeOverSessions(5)?.let { it * 100 } else ms?.pctOverSessions(5)
                    T(if (w == null) "—" else if (k == "TNX") String.format(BR, "%+.0f bp", w) else pct(w), chg(w), 12, monoFont = true, modifier = Modifier.weight(0.7f))
                }
            }
            val t = s.markets["SPX"]?.lastTimeMs
            Footnote("Yahoo Finance (não oficial), barras de 1 h. Cotação de ${t?.let { DT.format(Date(it)) } ?: "—"}; fora do pregão mostra o último fechamento.")
        }
    }
    Section("Juros", Modifier.fillMaxWidth()) {
        Column {
            s.fed?.let { KV("Meta do Fed (intervalo)", String.format(BR, "%.2f%% – %.2f%%", it.targetLow, it.targetHigh)); KV("Taxa efetiva (EFFR) · ${it.date}", f2(it.effr) + "%") }
                ?: KV("Taxa do Fed", "sem dado", Q.Muted)
            val cv = s.curve
            if (cv == null) KV("Treasuries (oficial)", "sem dado", Q.Muted) else {
                val y2 = cv.last2y; val y10 = cv.last10y
                fun bp5(d: Double?) = d?.let { String.format(BR, " (%+.0f bp 5d)", it * 100) } ?: ""
                KV("Treasury 2 anos (oficial) · ${cv.date ?: "—"}", if (y2 == null) "—" else f2(y2) + "%" + bp5(cv.change2y(5)))
                KV("Treasury 10 anos (oficial)", if (y10 == null) "—" else f2(y10) + "%" + bp5(cv.change10y(5)))
                if (y2 != null && y10 != null) KV("Inclinação 10a − 2a", String.format(BR, "%+.0f bp", (y10 - y2) * 100))
            }
            Footnote("Fontes: NY Fed (EFFR) e U.S. Treasury (curva diária de fechamento).")
        }
    }
    Section("Inflação e emprego", Modifier.fillMaxWidth()) {
        Column {
            Econ("CPI em 12 meses", s.econ["CPI"], now) { it.pctChange(12)?.let { v -> String.format(BR, "%.1f%%", v) } }
            Econ("CPI no mês", s.econ["CPI"], now) { it.pctChange(1)?.let { v -> String.format(BR, "%+.1f%%", v) } }
            Econ("Núcleo do CPI em 12 meses", s.econ["CORE_CPI"], now) { it.pctChange(12)?.let { v -> String.format(BR, "%.1f%%", v) } }
            Econ("Núcleo do CPI no mês", s.econ["CORE_CPI"], now) { it.pctChange(1)?.let { v -> String.format(BR, "%+.1f%%", v) } }
            Econ("PCE em 12 meses", s.econ["PCE"], now) { it.pctChange(12)?.let { v -> String.format(BR, "%.1f%%", v) } }
            Econ("Núcleo do PCE em 12 meses", s.econ["CORE_PCE"], now) { it.pctChange(12)?.let { v -> String.format(BR, "%.1f%%", v) } }
            Econ("Payroll (vagas no mês)", s.econ["PAYROLLS"], now) { it.diff(1)?.let { v -> String.format(BR, "%+,.0f mil", v) } }
            Econ("Desemprego", s.econ["UNEMP"], now) { it.v(0)?.let { v -> String.format(BR, "%.1f%%", v) } }
            Econ("Salário/hora em 12 meses", s.econ["AHE"], now) { it.pctChange(12)?.let { v -> String.format(BR, "%+.1f%%", v) } }
            Footnote("BLS (CPI, payroll, desemprego, salários) e BEA (PCE), dessazonalizados, calculados do índice oficial. Entre parênteses: mês de referência.")
        }
    }
    Section("Liquidez", Modifier.fillMaxWidth()) {
        Column {
            val g = s.tga
            val gl = g?.last
            if (g == null || gl == null) KV("TGA (conta do Tesouro no Fed)", "sem dado", Q.Muted)
            else {
                KV("TGA · ${g.rows.first().first}", String.format(BR, "US$ %,.0f bi", gl / 1000))
                g.change(20)?.let { KV("Variação em ~4 semanas", String.format(BR, "US$ %+,.0f bi", it / 1000), if (it > 0) Q.Sell else Q.Buy) }
            }
            Footnote("TGA subindo retira dinheiro do sistema; caindo devolve. Fonte: Daily Treasury Statement.")
        }
    }
    Section("Correlação com o BTC", Modifier.fillMaxWidth(), minWidth = 340.dp) {
        Column {
            Row { H("Ativo", 1.2f); H("24 h", 0.6f); H("7 d", 0.6f); H("30 d", 0.6f) }
            if (s.corr.isEmpty()) T("Sem série do BTC para comparar.", Q.Muted, 12)
            s.corr.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    T(r.asset, Q.Text, 12, wrap = true, modifier = Modifier.weight(1.2f))
                    listOf(r.r24h, r.r7d, r.r30d).forEach { v -> T(v?.let { String.format(BR, "%+.2f", it) } ?: "—", corrColor(v), 12, monoFont = true, modifier = Modifier.weight(0.6f)) }
                }
            }
            Footnote("Pearson dos retornos nos horários em que os dois negociam (24 h e 7 d: barras de 1 h; 30 d: diário). Mínimo de amostras: 8 / 20 / 12. Correlação passada não garante a futura.")
        }
    }
}

@Composable private fun androidx.compose.foundation.layout.RowScope.H(t: String, w: Float) = T(t, Q.Muted, 11, modifier = Modifier.weight(w))
private fun chg(v: Double?): Color = when { v == null -> Q.Muted; v > 0 -> Q.Buy; v < 0 -> Q.Sell; else -> Q.Text }
private fun corrColor(v: Double?): Color = when { v == null -> Q.Muted; kotlin.math.abs(v) >= 0.5 -> Q.Accent; kotlin.math.abs(v) >= 0.25 -> Q.Text; else -> Q.Muted }

@Composable
private fun Econ(label: String, s: EconSeries?, now: Long, value: (EconSeries) -> String?) {
    if (s == null) { KV(label, "sem dado", Q.Muted); return }
    val v = value(s)
    val stale = s.isStale(now)
    KV("$label (${s.lastPeriod ?: "—"})", (v ?: "—") + if (stale) " · desatualizado" else "", if (stale) Q.Warn else Q.Text)
}

// ------------------------------------------------------------------ calendário

@Composable
private fun Calendar(s: MacroState) {
    var all by remember { mutableStateOf(SettingsStore.b("mc.cal.all", false)) }
    val now = s.updatedMs
    val list = s.calendar.filter { all || it.high || it.impact.equals("Medium", true) }
    Section("Calendário econômico dos EUA · esta semana", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chips("Mostrar", listOf("Alto e médio impacto", "Todos"), if (all) 1 else 0) { all = it == 1; SettingsStore.put("mc.cal.all", all) }
            if (list.isEmpty()) Empty("Sem eventos no calendário", "O calendário semanal é publicado no domingo à noite (horário de Brasília).")
            list.forEach { e -> CalRow(e, now) }
            Footnote("Horários no fuso deste aparelho. Expectativa e anterior: calendário semanal do Forex Factory (não oficial). " +
                "Resultado só aparece quando a série oficial (BLS/BEA) já traz o mês de referência; nos outros casos fica \"aguardando\".")
        }
    }
    if (s.fomc.isNotEmpty()) Section("Reuniões do FOMC", Modifier.fillMaxWidth()) {
        Column {
            s.fomc.filter { it > now - 86_400_000L }.take(6).forEach { t -> KV("Decisão", WD.format(Date(t)), if (t - now in 0..7 * 86_400_000L) Q.Accent else Q.Text) }
            Footnote("Fonte: federalreserve.gov (decisão no último dia da reunião, 14h de Nova York).")
        }
    }
}

@Composable
private fun CalRow(e: CalEvent, now: Long) {
    val past = e.timeMs <= now
    val sp = MacroEvents.surprise(e)
    val impColor = when { e.high -> Q.Sell; e.impact.equals("Medium", true) -> Q.Warn; else -> Q.Muted }
    Column(Modifier.fillMaxWidth().background(Q.Panel2).border(1.dp, if (e.high && !past && e.timeMs - now < 86_400_000L) Q.Accent else Q.Panel2).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(WD.format(Date(e.timeMs)), if (past) Q.Muted else Q.Text, 11, monoFont = true, modifier = Modifier.padding(end = 6.dp))
            Badge(when { e.high -> "Alto"; e.impact.equals("Medium", true) -> "Médio"; e.impact.equals("Holiday", true) -> "Feriado"; else -> "Baixo" }, impColor)
        }
        T(e.title, Q.Text, 12, bold = e.high, wrap = true)
        T("Expectativa ${e.forecast.ifBlank { "—" }} · anterior ${e.previous.ifBlank { "—" }} · resultado ${e.actual ?: if (past) "aguardando fonte oficial" else "—"}",
            Q.Muted, 11, wrap = true)
        if (sp != null) {
            T("${sp.label} → ${sp.dir.label}", dirColor(sp.dir), 12, bold = true, wrap = true)
            if (sp.dir != MacroDir.NEUTRAL) T(sp.chain.joinToString(" → "), Q.Muted, 11, wrap = true)
            T(sp.note, Q.Muted, 11, wrap = true)
        } else MacroEvents.profile(e.title)?.let { p ->
            if (!past && e.high) T("Se vier acima do esperado: " + p.chain.last().lowercase(), Q.Muted, 11, wrap = true)
        }
    }
}

// ------------------------------------------------------------------ notícias

@Composable
private fun News(s: MacroState) {
    var cat by remember { mutableIntStateOf(SettingsStore.i("mc.news.cat", 0)) }
    val cats = listOf<NewsCategory?>(null) + NewsCategory.entries
    val now = s.updatedMs
    val list = s.news.filter { cats.getOrNull(cat) == null || it.category == cats[cat] }
    Section("Notícias com ligação econômica ao BTC", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chips("Tema", cats.map { it?.label ?: "Todos" }, cat.coerceIn(0, cats.size - 1)) { cat = it; SettingsStore.put("mc.news.cat", it) }
            if (list.isEmpty()) Empty("Nenhuma notícia relevante agora", "Só entram manchetes das últimas 48 h com ligação econômica clara (juros, dólar, energia, comércio, conflitos, regulação).")
            list.take(60).forEach { n -> NewsRow(n, now) }
            Footnote("Classificação automática por regras de palavras-chave sobre a manchete (docs/MACRO.md). Repetições entre veículos são agrupadas; abra o link para ler a matéria completa.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsRow(n: MacroNews, now: Long) {
    val uri = LocalUriHandler.current
    var open by remember(n.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Q.Panel2).padding(6.dp)) {
        val shown = I18n.news(n.title)
        Tap({ open = !open }) { T(shown, Q.Text, 12, bold = n.intensity.weight >= MacroIntensity.HIGH.weight, wrap = true, tr = false) }
        T("${n.source} · ${n.reliability.label} · ${ageTxt(now, n.timeMs)} · ${DT.format(Date(n.timeMs))}", Q.Muted, 11, wrap = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
            Badge(n.dir.label, dirColor(n.dir))
            Badge("Intensidade " + n.intensity.label.lowercase(), if (n.intensity.weight >= MacroIntensity.HIGH.weight) Q.Accent else Q.Muted)
            Badge(n.category.label, Q.Muted)
        }
        if (open) {
            if (shown != n.title) T(L("Original") + ": " + n.title, Q.Muted, 11, wrap = true, tr = false, modifier = Modifier.padding(top = 4.dp))
            T(n.rationale, Q.Text, 11, wrap = true, modifier = Modifier.padding(top = 4.dp))
            T(n.chain.joinToString(" → "), Q.Muted, 11, wrap = true)
            T("Horizonte: ${n.horizon.label} · relevância ${(n.relevance * 100).toInt()}%", Q.Muted, 11, wrap = true)
            if (n.url.isNotBlank()) Box(Modifier.padding(top = 4.dp)) { OutlineButton("Abrir a matéria") { runCatching { uri.openUri(n.url) } } }
        } else T("Toque no título para ver a cadeia de impacto", Q.Muted, 10)
    }
}

// ------------------------------------------------------------------ ajustes e fontes

@Composable
private fun Settings(m: MacroEngine, s: MacroState) {
    var ver by remember { mutableIntStateOf(0) }
    Section("Pesos do score", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            key(ver) {
                MacroFactor.entries.forEach { f ->
                    Column(Modifier.fillMaxWidth()) {
                        NumField(f.label, MacroSettings.weight(f).toDouble(), 60.dp) { v -> if (v != null) { MacroSettings.setWeight(f, v.toInt()); m.recompute() } }
                        T(f.rule, Q.Muted, 10, wrap = true)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Restaurar pesos padrão") { MacroSettings.resetWeights(); ver++; m.recompute() }
            }
            Footnote("Peso 0 desliga o fator. O score usa só os fatores com dado; abaixo de 35% dos pesos com dado ele não é mostrado.")
        }
    }
    Section("Alertas macro", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NumField("Avisar eventos de alto impacto com (min) de antecedência", MacroSettings.leadMin.toDouble(), 60.dp) { v -> if (v != null) MacroSettings.leadMin = v.toInt() }
            Footnote("Os tipos de alerta macro ligam e desligam na aba Alertas, junto com Telegram, e-mail e notificação. Cada ocorrência é avisada uma vez só.")
        }
    }
    Section("Chave opcional", Modifier.fillMaxWidth()) {
        Column {
            var k by remember { mutableStateOf(SettingsStore.s("mc.bls", "")) }
            TextSetting("Chave da API do BLS (v2, gratuita)", k, if (compact) 220.dp else 300.dp, secret = true) { k = it; MacroSettings.blsKey = it }
            Footnote("Sem chave o BLS permite 25 consultas por dia; o app usa 2. A chave fica só neste aparelho (ou na variável de ambiente BLS_API_KEY).")
        }
    }
}

/** Última página: todas as fontes com link, e a situação de cada coleta. */
@Composable
private fun Sources(m: MacroEngine, s: MacroState) {
    val uri = LocalUriHandler.current
    Section("Fontes dos dados", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.cunhac.btcradar.engine.MacroCatalog.ALL.forEach { src ->
                Column(Modifier.fillMaxWidth().background(Q.Panel2).padding(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(src.name, Q.Text, 12, bold = true, wrap = true, modifier = Modifier.weight(1f))
                        Badge(if (src.official) "Oficial" else "Mercado / mídia", if (src.official) Q.Accent else Q.Muted)
                    }
                    T(src.provides, Q.Muted, 11, wrap = true)
                    Tap({ runCatching { uri.openUri(src.url) } }) { T(src.url, Q.Accent, 11, tr = false) }
                }
            }
            Footnote("Nenhum número é inventado: dado ausente aparece como \"sem dado\". Fontes não oficiais podem mudar sem aviso.")
        }
    }
    Section("Situação da coleta", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.padding(bottom = 4.dp)) { OutlineButton("Atualizar agora") { m.refreshNow() } }
            T(String.format(BR, "%d de %d fontes em dia", s.sources.count { it.ok }, s.sources.size), Q.Text, 12)
            s.sources.sortedBy { it.ok }.forEach { st ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    T(if (st.ok) "●" else if (st.fromCache) "◐" else "○", if (st.ok) Q.Buy else if (st.fromCache) Q.Warn else Q.Sell, 12, modifier = Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        T(st.name, Q.Text, 11, wrap = true)
                        T((if (st.lastOkMs > 0) "dado de ${DT.format(Date(st.lastOkMs))}" else "sem dado") + (st.error?.let { " · falhou: $it" } ?: "") +
                            (if (st.fromCache) " · usando cópia salva" else ""), Q.Muted, 10, wrap = true)
                    }
                }
            }
            Footnote("● em dia · ◐ fonte falhou, usando a última cópia salva · ○ sem dado. Após falha o app espera antes de tentar de novo.")
        }
    }
}

@Composable private fun key(v: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(v) { content() }

// ------------------------------------------------------------------ mais dados

private fun fedOutcome(name: String): String = when {
    name.startsWith("Cut >25") -> String.format(BR, "Corte maior que %.2f pp", 0.25)
    name.startsWith("Cut 25") -> String.format(BR, "Corte de %.2f pp", 0.25)
    name.startsWith("Hike >25") -> String.format(BR, "Alta maior que %.2f pp", 0.25)
    name.startsWith("Hike 25") -> String.format(BR, "Alta de %.2f pp", 0.25)
    name.contains("maintain", true) || name.contains("hold", true) -> "Juros mantidos"
    else -> name
}

private fun usd(v: Double): String = when {
    kotlin.math.abs(v) >= 1e12 -> String.format(BR, "US$ %,.2f tri", v / 1e12)
    kotlin.math.abs(v) >= 1e9 -> String.format(BR, "US$ %,.1f bi", v / 1e9)
    else -> String.format(BR, "US$ %,.0f mi", v / 1e6)
}

@Composable
private fun MoreData(s: MacroState) {
    val now = s.updatedMs
    Section("Expectativa de juros do Fed", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val next = s.fedOdds.firstOrNull()
            if (next == null) T("Sem probabilidades da Kalshi agora.", Q.Muted, 12, wrap = true)
            else {
                T("Próxima reunião (${WD.format(Date(next.closeMs))}): probabilidades negociadas", Q.Text, 12, bold = true, wrap = true)
                next.outcomes.forEach { (name, p) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        T(fedOutcome(name), Q.Text, 12, wrap = true, modifier = Modifier.weight(1f))
                        Box(Modifier.width(80.dp).height(8.dp).background(Q.Line)) {
                            Box(Modifier.fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).height(8.dp).background(if (name.startsWith("Cut")) Q.Buy else if (name.startsWith("Hike")) Q.Sell else Q.Muted))
                        }
                        T(String.format(BR, "%.0f%%", p * 100), Q.Text, 12, monoFont = true, modifier = Modifier.width(48.dp).padding(start = 6.dp))
                    }
                }
            }
            if (s.futures.isNotEmpty()) {
                T("Taxa implícita nos futuros de Fed funds (CBOT)", Q.Text, 12, bold = true, wrap = true, modifier = Modifier.padding(top = 6.dp))
                val effr = s.fed?.effr
                s.futures.forEach { (month, r) ->
                    KV(month, String.format(BR, "%.2f%%", r) + (effr?.let { String.format(BR, " (%+.0f bp)", (r - it) * 100) } ?: ""),
                        if (effr == null) Q.Text else if (r < effr - 0.05) Q.Buy else if (r > effr + 0.05) Q.Sell else Q.Text)
                }
            }
            Footnote("Kalshi: mercado regulado de previsões (preço do contrato = probabilidade). Futuros: taxa implícita = 100 − preço; entre parênteses, " +
                "diferença para a taxa efetiva atual. Valores negativos = cortes esperados.")
        }
    }
    Section("Juros reais, inflação implícita e financiamento", Modifier.fillMaxWidth()) {
        Column {
            val real = s.real10y.firstOrNull()
            if (real == null) KV("Juro real 10 anos (TIPS)", "sem dado", Q.Muted)
            else {
                KV("Juro real 10 anos (TIPS) · ${real.first}", String.format(BR, "%.2f%%", real.second) + (s.real10yChange(5)?.let { String.format(BR, " (%+.0f bp 5d)", it * 100) } ?: ""))
                val nom = s.curve?.last10y
                if (nom != null) KV("Inflação implícita 10 anos (breakeven)", String.format(BR, "%.2f%%", nom - real.second))
            }
            s.sofr?.let { KV("SOFR · ${it.first}", String.format(BR, "%.2f%%", it.second)) }
            s.rrp.firstOrNull()?.let { KV("Reverse repo overnight · ${it.first}", String.format(BR, "US$ %,.1f bi", it.second / 1000)) }
            Footnote("Juro real alto ou subindo costuma pesar sobre o BTC (custo de oportunidade). Breakeven = Treasury 10 anos − juro real.")
        }
    }
    Section("Balanço do Fed e liquidez", Modifier.fillMaxWidth()) {
        Column {
            val b = s.fedBalance
            if (b == null) KV("Balanço do Fed (H.4.1)", "sem dado", Q.Muted)
            else {
                KV("Ativos totais do Fed · semana até ${b.week}", String.format(BR, "US$ %,.2f tri", b.totalAssets / 1e6))
                KV("Variação na semana", String.format(BR, "US$ %+,.1f bi", b.weekChange / 1000), if (b.weekChange >= 0) Q.Buy else Q.Sell)
                b.yearChange?.let { KV("Variação em 12 meses", String.format(BR, "US$ %+,.0f bi", it / 1000), if (it >= 0) Q.Buy else Q.Sell) }
                b.reserves?.let { KV("Reservas dos bancos no Fed", String.format(BR, "US$ %,.2f tri", it / 1e6)) }
                val tga = s.tga?.last
                val rrp = s.rrp.firstOrNull()?.second
                if (tga != null) KV("Liquidez líquida estimada (ativos − TGA − RRP)", String.format(BR, "US$ %,.2f tri", (b.totalAssets - tga - (rrp ?: 0.0)) / 1e6))
            }
            Footnote("Liquidez líquida é uma estimativa comum no mercado, não um número oficial. Balanço crescendo e TGA caindo = mais dinheiro no sistema.")
        }
    }
    Section("Mercado cripto", Modifier.fillMaxWidth()) {
        Column {
            s.fearGreed?.let { g ->
                val c = when { g.value <= 25 -> Q.Sell; g.value >= 75 -> Q.Buy; else -> Q.Text }
                KV("Fear & Greed", "${g.value} · ${fngLabel(g.label)}", c)
                KV("Há 7 dias / há 30 dias", "${g.weekAgo ?: "—"} / ${g.monthAgo ?: "—"}")
            } ?: KV("Fear & Greed", "sem dado", Q.Muted)
            s.cryptoGlobal?.let { g ->
                KV("Valor de mercado cripto", usd(g.totalCapUsd) + (g.cap24hPct?.let { String.format(BR, " (%+.1f%% 24 h)", it) } ?: ""), chg(g.cap24hPct))
                KV("Dominância do BTC", String.format(BR, "%.1f%%", g.btcDominance))
                g.ethDominance?.let { KV("Dominância do ETH", String.format(BR, "%.1f%%", it)) }
                g.volumeUsd?.let { KV("Volume 24 h", usd(it)) }
            }
            s.stablecoins.firstOrNull()?.let { (_, v) ->
                KV("Stablecoins em circulação", usd(v))
                KV("Variação 7 d / 30 d", listOf(s.stablePct(7), s.stablePct(30)).joinToString(" / ") { it?.let { x -> String.format(BR, "%+.1f%%", x) } ?: "—" }, chg(s.stablePct(30)))
            }
            s.network?.let { n ->
                KV("Hashrate da rede", String.format(BR, "%,.0f EH/s", n.hashrateEh) + (n.hashrate30dChangePct?.let { String.format(BR, " (%+.1f%% 30 d)", it) } ?: ""))
            }
            s.fees?.let { (f, h, o) -> KV("Taxas (rápida / 30 min / 1 h)", "$f / $h / $o sat/vB") }
            Footnote("Fear & Greed é sentimento, não entra no score (evita contar duas vezes o que o order flow já mostra). Stablecoins entram no fator Liquidez.")
        }
    }
    Section("Mais mercados", Modifier.fillMaxWidth(), minWidth = 360.dp) {
        Column {
            Row { H("Ativo", 1.2f); H("Último", 0.8f); H("Dia", 0.6f); H("5 pregões", 0.7f) }
            com.cunhac.btcradar.engine.MacroSources.EXTRA_MARKETS.groupBy { it[3] }.forEach { (group, items) ->
                T(L(group).uppercase(BR), Q.Muted, 10, bold = true, tr = false, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                items.forEach { item ->
                    val k = item[0]; val ms = s.markets[k]
                    val y = k in setOf("IRX", "FVX", "TYX")
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(item[1], Q.Text, 12, wrap = true, modifier = Modifier.weight(1.2f))
                        T(ms?.last?.let { f2(it, if (y) 3 else if (it < 20) 4 else 2) + if (y) "%" else "" } ?: "—", Q.Text, 12, monoFont = true, modifier = Modifier.weight(0.8f))
                        val d = if (y) ms?.dayChange?.let { it * 100 } else ms?.dayPct
                        T(if (d == null) "—" else if (y) String.format(BR, "%+.0f bp", d) else pct(d), chg(d), 12, monoFont = true, modifier = Modifier.weight(0.6f))
                        val w = if (y) ms?.changeOverSessions(5)?.let { it * 100 } else ms?.pctOverSessions(5)
                        T(if (w == null) "—" else if (y) String.format(BR, "%+.0f bp", w) else pct(w), chg(w), 12, monoFont = true, modifier = Modifier.weight(0.7f))
                    }
                }
            }
            Footnote("Yahoo Finance, barras de 1 h. Dólar subindo contra moedas emergentes e cobre caindo costumam indicar aversão a risco.")
        }
    }
    Section("Mundo e dívida", Modifier.fillMaxWidth()) {
        Column {
            s.ecb?.let { KV("BCE · taxa de depósito · ${it.first}", String.format(BR, "%.2f%%", it.second)) } ?: KV("BCE · taxa de depósito", "sem dado", Q.Muted)
            s.debt.firstOrNull()?.let { (d, v) ->
                KV("Dívida pública dos EUA · $d", usd(v))
                s.debt.getOrNull(minOf(20, s.debt.size - 1))?.takeIf { it.first != d }?.let { (d0, v0) -> KV("Variação desde $d0", usd(v - v0)) }
            }
            Footnote("Juros do BCE abaixo dos do Fed sustentam o dólar; dívida crescendo rápido pressiona a oferta de Treasuries e os yields.")
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = now
}

private fun fngLabel(s: String) = when (s.lowercase()) {
    "extreme fear" -> "Medo extremo"; "fear" -> "Medo"; "neutral" -> "Neutro"; "greed" -> "Ganância"; "extreme greed" -> "Ganância extrema"; else -> s
}

