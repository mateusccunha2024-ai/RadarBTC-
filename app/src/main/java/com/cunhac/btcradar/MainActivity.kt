package com.cunhac.btcradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.ui.etf.EtfScreen
import com.cunhac.btcradar.ui.liquidations.LiquidationsScreen
import com.cunhac.btcradar.ui.map.MapScreen
import com.cunhac.btcradar.ui.theme.C
import com.cunhac.btcradar.ui.theme.RadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RadarTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val tabs = listOf("Mapa", "Liquidações", "ETFs")

                Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> MapScreen()
                            1 -> LiquidationsScreen()
                            else -> EtfScreen()
                        }
                    }
                    HorizontalDivider(color = C.Line, thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth()) {
                        tabs.forEachIndexed { i, label ->
                            Box(
                                Modifier.weight(1f).clickable { tab = i }.padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (tab == i) C.Text else C.Muted,
                                    fontSize = 13.sp,
                                    fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
