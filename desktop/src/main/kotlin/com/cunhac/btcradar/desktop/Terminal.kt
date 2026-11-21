package com.cunhac.btcradar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.engine.CORE_VENUES
import com.cunhac.btcradar.engine.ConnState
import com.cunhac.btcradar.engine.Hub
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.Venue
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContext(val hub: Hub, val db: SqliteHistory, val map: MapModel,
                 val heat: HeatLoader = HeatLoader(CoroutineScope(SupervisorJob() + Dispatchers.Default)),
                 val alerts: AlertEngine = AlertEngine(hub, heat))

val TABS = listOf(
    "ORDERFLOW", "FOOTPRINT", "LIQUIDITY WALLS", "LIQUIDITY HISTORY", "MAGNET ZONES", "LONG & SHORT FLOW",
    "MARKET FLOW CORRELATION", "LARGE TRADES", "LIQUIDATION RADAR", "ALERTAS", "STATUS",
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
fun Terminal(ctx: AppContext, initialTab: Int? = null, showSettings: Boolean = false) {
    val base = LocalDensity.current
    // Zoom da interface: multiplica a densidade (tudo cresce junto, sem quebrar o layout).
    CompositionLocalProvider(LocalDensity provides Density(base.density * UiScale.scale, base.fontScale)) {
        TerminalBody(ctx, initialTab, showSettings)
    }
}

@Composable
private fun TerminalBody(ctx: AppContext, initialTab: Int?, showSettings: Boolean) {
    val s by ctx.hub.stateFlow.collectAsState()
    var tabName by remember { mutableStateOf(initialTab?.let { TABS.getOrNull(it) } ?: SettingsStore.s("tab.name", TABS[0])) }
    var settingsOpen by remember { mutableStateOf(showSettings) }
    val tabs = TabPrefs.visible
    val current = if (tabName in tabs) tabName else tabs.first()
    Box(Modifier.fillMaxSize().background(Q.Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(s, onSettings = { settingsOpen = !settingsOpen })
            Row(Modifier.fillMaxWidth().background(Q.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                tabs.forEach { t ->
                    val on = t == current
                    Text(t, color = if (on) Q.Text else Q.Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
                        modifier = Modifier
                            .background(if (on) Q.Panel2 else Q.Panel)
                            .drawBehind { if (on) drawRect(Q.Accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                            .clickable { tabName = t; SettingsStore.put("tab.name", t) }
                            .padding(horizontal = 13.dp, vertical = 10.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
            Box(Modifier.weight(1f).fillMaxWidth().padding(if (Look.compact) 4.dp else 8.dp)) {
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
                    "ALERTAS" -> AlertsTab(ctx, s)
                    else -> StatusTab(ctx, s)
                }
            }
        }
        if (settingsOpen) {
            // Véu: clicar fora fecha o menu.
            Box(Modifier.fillMaxSize().background(Q.Bg.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, null) { settingsOpen = false })
            Box(Modifier.fillMaxSize().padding(top = 44.dp, end = 10.dp), contentAlignment = Alignment.TopEnd) {
                ViewSettingsPanel(ctx) { settingsOpen = false }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, on: Boolean = false, onClick: () -> Unit) =
    Text(label, color = if (on) Q.Accent else Q.Text, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
        modifier = Modifier.background(if (on) Q.Panel2 else Q.Bg).border(1.dp, if (on) Q.Accent else Q.Line)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 3.dp))

@Composable
private fun TopBar(s: TerminalState, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Q.Bg).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T("BTC ORDERFLOW TERMINAL", Q.Text, 14, bold = true)
        T("  V8 · Radar BTC", Q.Muted, 11)
        Spacer(Modifier.width(20.dp))
        T(px(s.price), Q.Text, 18, bold = true, monoFont = true)
        Spacer(Modifier.width(20.dp))
        val live = s.liveCount()
        T("$live/3 LIVROS AO VIVO", if (live == 3) Q.Buy else if (live > 0) Q.Warn else Q.Sell, 12, bold = true)
        Spacer(Modifier.width(10.dp))
        CORE_VENUES.forEach { v ->
            val ok = s.books[v]?.valid == true && s.statuses.any { it.venue == v && it.state == ConnState.LIVE }
            val down = s.statuses.filter { it.venue == v }.let { l -> l.isNotEmpty() && l.all { it.state == ConnState.DOWN } }
            T("● ${v.label}", if (ok) Q.Buy else if (down) Q.Sell else Q.Warn, 12, modifier = Modifier.padding(end = 8.dp))
        }
        T("Liq.:", Q.Muted, 11, modifier = Modifier.padding(start = 4.dp, end = 4.dp))
        Venue.entries.filter { !it.core }.forEach { v -> T("● ${v.label}", venueDot(s, v), 11, modifier = Modifier.padding(end = 6.dp)) }
        T(String.format(BR, "%,.0f msg/s · %,d trades · %,d liquidações", s.msgsPerSec, s.tradesTotal, s.liqTotal), Q.Muted, 11,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp), align = TextAlign.End)
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

private fun venueDot(s: TerminalState, v: Venue) = s.statuses.filter { it.venue == v }.let { l ->
    when { l.any { it.state == ConnState.LIVE } -> Q.Buy; l.isNotEmpty() && l.all { it.state == ConnState.DOWN } -> Q.Sell; else -> Q.Warn }
}

private val REFRESH = listOf(500L, 1_000L, 1_500L, 3_000L)
private val SCALES = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f)

/** Menu de configurações de visualização: tema, cores, tamanho, densidade, atualização e abas. */
@Composable
private fun ViewSettingsPanel(ctx: AppContext, onClose: () -> Unit) {
    var refresh by remember { mutableStateOf(ctx.hub.refreshMs) }
    Column(Modifier.width(430.dp).fillMaxHeight(0.92f).background(Q.Panel).border(1.dp, Q.Line)
        .clickable(remember { MutableInteractionSource() }, null) {}  // não fecha ao clicar dentro
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Configurações de visualização", Q.Text, 15, bold = true, modifier = Modifier.weight(1f))
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
            Footnote("Atalhos: Ctrl + e Ctrl − ajustam, Ctrl 0 volta a 100%.")
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
        }
        SettingGroup("Abas visíveis") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TABS.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { t -> Box(Modifier.width(190.dp)) { Toggle(t, t !in TabPrefs.hidden) { TabPrefs.toggle(t) } } }
                    }
                }
            }
            Footnote("STATUS fica sempre visível. Esconder uma aba não para a coleta de dados dela.")
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
