package com.cunhac.btcradar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.engine.ConnState
import com.cunhac.btcradar.engine.Hub
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.Venue
import kotlin.math.roundToInt

class AppContext(val hub: Hub, val db: SqliteHistory, val map: MapModel)

val TABS = listOf("ORDERFLOW", "LIQUIDITY WALLS", "MAGNET ZONES", "LONG & SHORT FLOW", "LARGE TRADES", "LIQUIDATION RADAR", "STATUS")

@Composable
fun Terminal(ctx: AppContext, initialTab: Int? = null) {
    val base = LocalDensity.current
    // Zoom da interface: multiplica a densidade (tudo cresce junto, sem quebrar o layout).
    CompositionLocalProvider(LocalDensity provides Density(base.density * UiScale.scale, base.fontScale)) {
        TerminalBody(ctx, initialTab)
    }
}

@Composable
private fun TerminalBody(ctx: AppContext, initialTab: Int?) {
    val s by ctx.hub.stateFlow.collectAsState()
    var tab by remember { mutableIntStateOf(initialTab ?: SettingsStore.i("tab", 0).coerceIn(0, TABS.size - 1)) }
    Column(Modifier.fillMaxSize().background(Q.Bg)) {
        TopBar(s)
        Row(Modifier.fillMaxWidth().background(Q.Panel).padding(horizontal = 8.dp)) {
            TABS.forEachIndexed { i, t ->
                val on = i == tab
                Text(t, color = if (on) Q.Text else Q.Muted, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier
                        .background(if (on) Q.Panel2 else Q.Panel)
                        .drawBehind { if (on) drawRect(Q.Accent, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                        .clickable { tab = i; SettingsStore.put("tab", i) }
                        .padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            // As abas só leem o hub: trocar de aba não reinicia nenhuma conexão.
            when (tab) {
                0 -> OrderflowTab(ctx, s)
                1 -> WallsTab(ctx, s)
                2 -> MagnetTab(ctx.map)
                3 -> FlowTab(ctx, s)
                4 -> LargeTradesTab(ctx, s)
                5 -> LiqTab(ctx, s)
                else -> StatusTab(ctx, s)
            }
        }
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) =
    Text(label, color = Q.Text, fontSize = 12.sp, lineHeight = 16.sp,
        modifier = Modifier.border(1.dp, Q.Line).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp))

@Composable
private fun TopBar(s: TerminalState) {
    Row(Modifier.fillMaxWidth().background(Q.Bg).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T("BTC ORDERFLOW TERMINAL", Q.Text, 14, bold = true)
        T("  V8 · Radar BTC", Q.Muted, 11)
        Spacer(Modifier.width(24.dp))
        T(px(s.price), Q.Text, 18, bold = true, monoFont = true)
        Spacer(Modifier.width(24.dp))
        val live = s.liveCount()
        T("$live/3 FONTES AO VIVO", if (live == 3) Q.Buy else if (live > 0) Q.Warn else Q.Sell, 12, bold = true)
        Spacer(Modifier.width(12.dp))
        Venue.entries.forEach { v ->
            val ok = s.books[v]?.valid == true && s.statuses.any { it.venue == v && it.state == ConnState.LIVE }
            val down = s.statuses.filter { it.venue == v }.let { l -> l.isNotEmpty() && l.all { it.state == ConnState.DOWN } }
            T("● ${v.label}", if (ok) Q.Buy else if (down) Q.Sell else Q.Warn, 12, modifier = Modifier.padding(end = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        T(String.format(BR, "%,.0f msg/s · %,d trades · %,d liquidações", s.msgsPerSec, s.tradesTotal, s.liqTotal), Q.Muted, 11)
        Spacer(Modifier.width(16.dp))
        T("Tamanho", Q.Muted, 11, modifier = Modifier.padding(end = 6.dp))
        ZoomButton("A−") { UiScale.dec() }
        T("${(UiScale.scale * 100).roundToInt()}%", Q.Text, 11, monoFont = true, modifier = Modifier.padding(horizontal = 6.dp))
        ZoomButton("A+") { UiScale.inc() }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
}
