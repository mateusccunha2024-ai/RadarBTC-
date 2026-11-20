package com.cunhac.btcradar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF0B0E11)
    val Surface = Color(0xFF14181F)
    val Line = Color(0xFF222833)
    val Text = Color(0xFFE6E8EA)
    val Muted = Color(0xFF7A8291)
    val Up = Color(0xFF16C784)      // compra / short liquidado / entrada no ETF
    val Down = Color(0xFFEA3943)    // venda / long liquidado / saída do ETF
    val Accent = Color(0xFFF7931A)  // laranja Bitcoin, só para destaque
}

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = C.Text)

@Composable
fun RadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.Bg, surface = C.Surface, primary = C.Accent,
            onBackground = C.Text, onSurface = C.Text,
        ),
        content = content,
    )
}
