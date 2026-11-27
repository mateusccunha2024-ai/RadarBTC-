package com.cunhac.btcradar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.engine.CORE_VENUES
import com.cunhac.btcradar.engine.ConnState
import com.cunhac.btcradar.engine.Hub
import com.cunhac.btcradar.engine.LIQ_VENUES
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.Venue
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContext(val hub: Hub, val db: SqliteHistory, val map: MapModel,
                 val heat: HeatLoader = HeatLoader(CoroutineScope(SupervisorJob() + Dispatchers.Default)),
                 val alerts: AlertEngine = AlertEngine(hub, heat)) {
    /** Fluxos dos ETFs spot de BTC: só carrega quando a aba é aberta. */
    val etf: EtfModel by lazy { EtfModel(CoroutineScope(SupervisorJob() + Dispatchers.IO)) }
}

val TABS = listOf(
    "ORDERFLOW", "FOOTPRINT", "LIQUIDITY WALLS", "LIQUIDITY HISTORY", "MAGNET ZONES", "LONG & SHORT FLOW",
    "MARKET FLOW CORRELATION", "LARGE TRADES", "LIQUIDATION RADAR", "ETFs", "ALERTAS", "STATUS",
)

/** Nome de cada aba na tela, em português (o id interno não muda: continua salvo nas preferências). */
val TAB_LABELS = mapOf(
    "ORDERFLOW" to "Livro de ofertas", "FOOTPRINT" to "Footprint", "LIQUIDITY WALLS" to "Paredes",
    "LIQUIDITY HISTORY" to "Histórico de paredes", "MAGNET ZONES" to "Zonas ímã", "LONG & SHORT FLOW" to "Long/short",
    "MARKET FLOW CORRELATION" to "Correlação", "LARGE TRADES" to "Grandes negócios",
    "LIQUIDATION RADAR" to "Radar", "ETFs" to "ETFs", "ALERTAS" to "Alertas", "STATUS" to "Status",
)
fun tabLabel(id: String) = TAB_LABELS[id] ?: id

/** Abas agrupadas por assunto (barra do PC e lista do celular). */
val TAB_GROUPS = listOf(
    "Livro" to listOf("ORDERFLOW", "FOOTPRINT", "LIQUIDITY WALLS", "LIQUIDITY HISTORY"),
    "Fluxo" to listOf("LONG & SHORT FLOW", "MARKET FLOW CORRELATION", "LARGE TRADES"),
    "Liquidações" to listOf("LIQUIDATION RADAR", "MAGNET ZONES"),
    "Mais" to listOf("ETFs", "ALERTAS", "STATUS"),
)

/** Abas que o usuário escondeu no menu de visualização (STATUS nunca some). */
object TabPrefs {
    var hidden by mutableStateOf(SettingsStore.s("ui.hidden", "").split('|').filter { it.isNotBlank() }.toSet()); private set
    fun toggle(name: String) {
        if (name == "STATUS") return
        hidden = if (name in hidden) hidden - name else hidden + name
        SettingsStore.put("ui.hidden", hidden.joinToString("|"))
    }
    val visible: List<String> get() = TABS.filter { it !in hidden }
}

@Composable
fun Terminal(ctx: AppContext, initialTab: Int? = null, showSettings: Boolean = false, showTabs: Boolean = false) {
    val base = LocalDensity.current
    // Zoom da interface: multiplica a densidade (tudo cresce junto, sem quebrar o layout).
    CompositionLocalProvider(LocalDensity provides Density(base.density * UiScale.scale, base.fontScale)) {
        // Abaixo de 760 dp (celular em pé, já contando o zoom) o layout vira coluna única.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalCompact provides (maxWidth < 760.dp)) { TerminalBody(ctx, initialTab, showSettings, showTabs) }
        }
    }
}

@Composable
private fun TerminalBody(ctx: AppContext, initialTab: Int?, showSettings: Boolean, showTabs: Boolean) {
    val s by ctx.hub.stateFlow.collectAsState()
    var tabName by remember { mutableStateOf(initialTab?.let { TABS.getOrNull(it) } ?: SettingsStore.s("tab.name", TABS[0])) }
    var settingsOpen by remember { mutableStateOf(showSettings) }
    var tabsOpen by remember { mutableStateOf(showTabs) }
    fun pick(t: String) { tabName = t; SettingsStore.put("tab.name", t); tabsOpen = false }
    val tabs = TabPrefs.visible
    val current = if (tabName in tabs) tabName else tabs.first()
    Box(Modifier.fillMaxSize().background(Q.Bg)) {
        Column(Modifier.fillMaxSize()) {
            if (compact) CompactTopBar(s, onSettings = { settingsOpen = !settingsOpen }) else TopBar(s, onSettings = { settingsOpen = !settingsOpen })
            if (compact) TabPickerBar(current, orderedTabs(tabs)) { tabsOpen = true }
            else Row(Modifier.fillMaxWidth().background(Q.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TAB_GROUPS.forEachIndexed { gi, (group, ids) ->
                    val shown = ids.filter { it in tabs }
                    if (shown.isEmpty()) return@forEachIndexed
                    if (gi > 0) Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(20.dp).background(Q.Line))
                    T(group.uppercase(), Q.Muted, 10, bold = true, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
                    shown.forEach { t ->
                        val on = t == current
                        Text(tabLabel(t), color = if (on) Q.Text else Q.Muted, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .background(if (on) Q.Panel2 else Q.Panel)
                                .drawBehind { if (on) drawRect(Q.Accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                                .clickable { pick(t) }
                                .padding(horizontal = 11.dp, vertical = 10.dp))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
            val scroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxWidth().then(if (compact) Modifier.verticalScroll(scroll) else Modifier).padding(if (Look.compact) 4.dp else 8.dp)) {
                // As abas só leem o hub: trocar de aba não reinicia nenhuma conexão.
                when (current) {
                    "ORDERFLOW" -> OrderflowTab(ctx, s)
                    "FOOTPRINT" -> FootprintTab(ctx, s)
                    "LIQUIDITY WALLS" -> WallsTab(ctx, s)
                    "LIQUIDITY HISTORY" -> LiquidityHistoryTab(ctx, s)
                    "MAGNET ZONES" -> MagnetTab(ctx.map)
                    "LONG & SHORT FLOW" -> FlowTab(ctx, s)
                    "MARKET FLOW CORRELATION" -> CorrelationTab(ctx, s)
                    "LARGE TRADES" -> LargeTradesTab(ctx, s)
                    "LIQUIDATION RADAR" -> LiqTab(ctx, s)
                    "ETFs" -> EtfTab(ctx)
                    "ALERTAS" -> AlertsTab(ctx, s)
                    else -> StatusTab(ctx, s)
                }
            }
        }
        if (tabsOpen && compact) {
            Box(Modifier.fillMaxSize().background(Q.Bg.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, null) { tabsOpen = false })
            TabSheet(current, orderedTabs(tabs), onPick = { pick(it) }, onClose = { tabsOpen = false })
        }
        if (settingsOpen) {
            // Véu: clicar fora fecha o menu.
            Box(Modifier.fillMaxSize().background(Q.Bg.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, null) { settingsOpen = false })
            Box(Modifier.fillMaxSize().padding(top = 44.dp, end = if (compact) 0.dp else 10.dp), contentAlignment = Alignment.TopEnd) {
                ViewSettingsPanel(ctx) { settingsOpen = false }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, on: Boolean = false, onClick: () -> Unit) = Tap(onClick) {
    Text(label, color = if (on) Q.Accent else Q.Text, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
        modifier = Modifier.background(if (on) Q.Panel2 else Q.Bg).border(1.dp, if (on) Q.Accent else Q.Line)
            .padding(horizontal = if (compact) 12.dp else 9.dp, vertical = if (compact) 8.dp else 3.dp))
}

/** Abas visíveis na ordem dos grupos. */
private fun orderedTabs(tabs: List<String>) = TAB_GROUPS.flatMap { it.second }.filter { it in tabs } + tabs.filter { t -> TAB_GROUPS.none { t in it.second } }

/** O que cada aba mostra (lista de abas do celular). */
private val TAB_HINTS = mapOf(
    "ORDERFLOW" to "Livro de ofertas consolidado e por corretora",
    "FOOTPRINT" to "Compra × venda agressora por faixa de preço",
    "LIQUIDITY WALLS" to "Paredes grandes no livro agora",
    "LIQUIDITY HISTORY" to "Onde as paredes estiveram e como terminaram",
    "MAGNET ZONES" to "Mapa estimado de zonas de liquidação",
    "LONG & SHORT FLOW" to "Fluxo agressor, CVD e sinal",
    "MARKET FLOW CORRELATION" to "Preço, CVD, OI e liquidações por minuto",
    "LARGE TRADES" to "Maiores execuções e sequências",
    "LIQUIDATION RADAR" to "Heatmap e liquidações reais",
    "ETFs" to "Fluxo diário dos ETFs spot de BTC",
    "ALERTAS" to "Avisos no celular, Telegram e e-mail",
    "STATUS" to "Conexões, livros e log",
)

/** Celular: a aba atual vira um botão que abre a lista de todas as abas. */
@Composable
private fun TabPickerBar(current: String, tabs: List<String>, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Q.Panel).clickable(onClick = onOpen).heightIn(min = 52.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(22.dp).background(Q.Accent))
        Spacer(Modifier.width(10.dp))
        T(tabLabel(current), Q.Text, 15, bold = true, modifier = Modifier.weight(1f))
        T("${tabs.indexOf(current) + 1} de ${tabs.size}", Q.Muted, 11, modifier = Modifier.padding(end = 10.dp))
        Text("Abas ▾", color = Q.Accent, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1,
            modifier = Modifier.border(1.dp, Q.Accent).padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun TabSheet(current: String, tabs: List<String>, onPick: (String) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).background(Q.Panel).border(1.dp, Q.Line)
        .clickable(remember { MutableInteractionSource() }, null) {}
        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Escolher aba", Q.Text, 15, bold = true, modifier = Modifier.weight(1f))
            SmallButton("Fechar ✕", onClick = onClose)
        }
        Spacer(Modifier.height(6.dp))
        tabs.forEach { t ->
            val firstOf = TAB_GROUPS.firstOrNull { (_, ids) -> ids.firstOrNull { it in tabs } == t }?.first
            if (firstOf != null) T(firstOf.uppercase(), Q.Muted, 11, bold = true, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            val on = t == current
            Row(Modifier.fillMaxWidth().background(if (on) Q.Panel2 else Q.Panel).clickable { onPick(t) }.heightIn(min = 56.dp)
                .drawBehind { if (on) drawRect(Q.Accent, Offset(0f, 0f), Size(3.dp.toPx(), size.height)) }
                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(tabLabel(t), if (on) Q.Accent else Q.Text, 14, bold = true)
                    TAB_HINTS[t]?.let { T(it, Q.Muted, 11) }
                }
                if (on) T("atual", Q.Accent, 11)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
        }
        Footnote("Para esconder abas desta lista, use ⚙ → Abas visíveis.", Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TopBar(s: TerminalState, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Q.Bg).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T("Radar BTC", Q.Text, 14, bold = true)
        T("  Orderflow V8", Q.Muted, 11)
        Spacer(Modifier.width(20.dp))
        // O preço é o dado mais importante do app: maior que qualquer título.
        T(px(s.price), Q.Text, 24, bold = true, monoFont = true)
        Spacer(Modifier.width(20.dp))
        Row(Modifier.weight(1f).clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
        val live = s.liveCount()
        T("$live/3 livros ao vivo", if (live == 3) Q.Buy else if (live > 0) Q.Warn else Q.Sell, 12, bold = true)
        Spacer(Modifier.width(10.dp))
        CORE_VENUES.forEach { v ->
            val ok = s.books[v]?.valid == true && s.statuses.any { it.venue == v && it.state == ConnState.LIVE }
            val down = s.statuses.filter { it.venue == v }.let { l -> l.isNotEmpty() && l.all { it.state == ConnState.DOWN } }
            T("● ${v.label}", if (ok) Q.Buy else if (down) Q.Sell else Q.Warn, 12, modifier = Modifier.padding(end = 8.dp))
        }
        T("Liq.:", Q.Muted, 11, modifier = Modifier.padding(start = 4.dp, end = 4.dp))
        LIQ_VENUES.filter { !it.core }.forEach { v -> T("● ${v.label}", venueDot(s, v), 11, modifier = Modifier.padding(end = 6.dp)) }
        // Contadores detalhados (trades, liquidações) ficam na aba Status; aqui só o ritmo de mensagens.
        T(String.format(BR, "%,.0f msg/s", s.msgsPerSec), Q.Muted, 11,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp), align = TextAlign.End)
        }
        SmallButton("Notas", on = Look.footnotes) { Look.useFootnotes(!Look.footnotes) }
        Spacer(Modifier.width(8.dp))
        SmallButton("A−") { UiScale.dec() }
        T("${(UiScale.scale * 100).roundToInt()}%", Q.Text, 11, monoFont = true, modifier = Modifier.padding(horizontal = 6.dp))
        SmallButton("A+") { UiScale.inc() }
        Spacer(Modifier.width(8.dp))
        SmallButton(if (Look.dark) "☾ Escuro" else "☀ Claro") { Look.useDark(!Look.dark) }
        Spacer(Modifier.width(8.dp))
        SmallButton("⚙ Visualização", onClick = onSettings)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
}

/** Barra do celular: nome, preço, fontes e o menu; detalhes numa segunda linha que rola para o lado. */
@Composable
private fun CompactTopBar(s: TerminalState, onSettings: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Telas muito estreitas (ou zoom alto): some o nome do app para o preço e os botões caberem.
    val roomy = maxWidth >= 380.dp
    Column(Modifier.fillMaxWidth().background(Q.Bg).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (roomy) { T("Radar BTC", Q.Text, 14, bold = true); Spacer(Modifier.width(10.dp)) }
            T(px(s.price), Q.Text, 18, bold = true, monoFont = true)
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            val live = s.liveCount()
            T("Livros $live/3", if (live == 3) Q.Buy else if (live > 0) Q.Warn else Q.Sell, 12, bold = true, modifier = Modifier.padding(end = 4.dp))
            SmallButton(if (Look.dark) "☾" else "☀") { Look.useDark(!Look.dark) }
            Spacer(Modifier.width(6.dp))
            SmallButton("⚙", onClick = onSettings)
        }
        // Duas linhas curtas em vez de uma linha cortada: livros de ofertas e fontes extras de liquidação.
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Livros:", Q.Muted, 11, modifier = Modifier.padding(end = 4.dp))
            CORE_VENUES.forEach { v ->
                val ok = s.books[v]?.valid == true && s.statuses.any { it.venue == v && it.state == ConnState.LIVE }
                T("● ${v.label}", if (ok) Q.Buy else Q.Warn, 11, modifier = Modifier.padding(end = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            T(String.format(BR, "%,.0f msg/s", s.msgsPerSec), Q.Muted, 11)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Liquidações:", Q.Muted, 11, modifier = Modifier.padding(end = 4.dp))
            LIQ_VENUES.filter { !it.core }.forEach { v -> T("● ${v.label}", venueDot(s, v), 11, modifier = Modifier.padding(end = 6.dp)) }
        }
    }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
}

private fun venueDot(s: TerminalState, v: Venue) = s.statuses.filter { it.venue == v }.let { l ->
    when { l.any { it.state == ConnState.LIVE } -> Q.Buy; l.isNotEmpty() && l.all { it.state == ConnState.DOWN } -> Q.Sell; else -> Q.Warn }
}

private val REFRESH = listOf(500L, 1_000L, 1_500L, 3_000L)
private val SCALES = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)

/** Menu de configurações de visualização: tema, cores, tamanho, densidade, atualização e abas. */
@Composable
private fun ViewSettingsPanel(ctx: AppContext, onClose: () -> Unit) {
    var refresh by remember { mutableStateOf(ctx.hub.refreshMs) }
    Column((if (compact) Modifier.fillMaxWidth() else Modifier.width(440.dp)).fillMaxHeight(0.92f).background(Q.Panel).border(1.dp, Q.Line)
        .clickable(remember { MutableInteractionSource() }, null) {}  // não fecha ao clicar dentro
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(if (compact) "Visualização" else "Configurações de visualização", Q.Text, 15, bold = true, modifier = Modifier.weight(1f))
            SmallButton("Fechar ✕", onClick = onClose)
        }
        SettingGroup("Tema") {
            Chips(null, listOf("Escuro", "Claro"), if (Look.dark) 0 else 1) { Look.useDark(it == 0) }
        }
        SettingGroup("Cores de compra e venda") {
            Chips(null, listOf("Verde / vermelho", "Azul / laranja"), if (Look.colorblind) 1 else 0) { Look.useColorblind(it == 1) }
            Footnote("Azul / laranja é a opção para daltonismo (vermelho e verde se confundem).")
        }
        SettingGroup("Tamanho da interface · ${(UiScale.scale * 100).roundToInt()}%") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("A− diminuir") { UiScale.dec() }
                SmallButton("A+ aumentar") { UiScale.inc() }
                SmallButton("Restaurar 100%") { UiScale.reset() }
            }
            Spacer(Modifier.height(6.dp))
            Chips(null, SCALES.map { "${(it * 100).roundToInt()}%" }, SCALES.indexOfFirst { kotlin.math.abs(it - UiScale.scale) < 0.01f }) { UiScale.set(SCALES[it]) }
            if (!Platform.isMobile) Footnote("Atalhos: Ctrl + e Ctrl − ajustam, Ctrl 0 volta a 100%.")
        }
        SettingGroup("Densidade") {
            Chips(null, listOf("Confortável", "Compacta"), if (Look.compact) 1 else 0) { Look.useCompact(it == 1) }
            Footnote("Compacta reduz margens e espaçamento entre linhas das tabelas para caber mais dados.")
        }
        SettingGroup("Atualização da tela") {
            Chips(null, REFRESH.map { if (it < 1000) "$it ms" else String.format(BR, "%.1f s", it / 1000.0) }, REFRESH.indexOf(refresh)) {
                refresh = REFRESH[it]; ctx.hub.refreshMs = refresh; SettingsStore.put("ui.refresh", refresh.toInt())
            }
            Footnote("Os dados chegam em tempo real de qualquer forma; isto muda só a frequência de redesenho (menor = mais CPU).")
        }
        SettingGroup("Textos explicativos") {
            Toggle("Mostrar notas de rodapé", Look.footnotes) { Look.useFootnotes(it) }
            if (!compact) T("Também pelo botão Notas na barra do topo.", Q.Muted, 11, modifier = Modifier.padding(top = 4.dp))
        }
        SettingGroup("Abas visíveis") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TABS.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { t -> Box(Modifier.width(if (compact) 168.dp else 196.dp)) { Toggle(tabLabel(t), t !in TabPrefs.hidden) { TabPrefs.toggle(t) } } }
                    }
                }
            }
            Footnote("Status fica sempre visível. Esconder uma aba não para a coleta de dados dela.")
        }
    }
}

@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        T(title.uppercase(), Q.Muted, 11, bold = true, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}
