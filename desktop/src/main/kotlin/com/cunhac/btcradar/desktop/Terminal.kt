package com.cunhac.btcradar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.cunhac.btcradar.engine.ConnState
import com.cunhac.btcradar.engine.Hub
import com.cunhac.btcradar.engine.TerminalState
import com.cunhac.btcradar.engine.Venue

class AppContext(val hub: Hub, val db: SqliteHistory, val map: MapModel)

val TABS = listOf("ORDERFLOW", "LIQUIDITY WALLS", "MAGNET ZONES", "LONG & SHORT FLOW", "LARGE TRADES", "LIQUIDATION RADAR", "STATUS")

@Composable
fun Terminal(ctx: AppContext) {
    val s by ctx.hub.stateFlow.collectAsState()
    var tab by remember { mutableIntStateOf(SettingsStore.i("tab", 0).coerceIn(0, TABS.size - 1)) }
    Column(Modifier.fillMaxSize().background(Q.Bg)) {
        TopBar(s)
        Row(Modifier.fillMaxWidth().background(Q.Panel).padding(horizontal = 8.dp)) {
            TABS.forEachIndexed { i, t ->
                val on = i == tab
                Text(t, color = if (on) Q.Accent else Q.Muted, fontSize = 12.sp,
                    modifier = Modifier.clickable { tab = i; SettingsStore.put("tab", i) }.padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            // As abas só leem o hub: trocar de aba não reinicia nenhuma conexão.
            when (tab) {
                0 -> OrderflowTab(ctx, s)
                1 -> WallsTab(ctx, s)
                2 -> MapPanel(ctx.map)
                3 -> FlowTab(ctx, s)
                4 -> LargeTradesTab(ctx, s)
                5 -> LiqTab(ctx, s)
                else -> StatusTab(ctx, s)
            }
        }
    }
}

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
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
}
