package com.cunhac.btcradar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.ui.theme.C
import java.util.Locale
import kotlin.math.abs

fun fmtUsd(v: Double): String {
    val a = abs(v)
    val s = when {
        a >= 1e9 -> String.format(Locale.US, "%.2fB", a / 1e9)
        a >= 1e6 -> String.format(Locale.US, "%.1fM", a / 1e6)
        a >= 1e3 -> String.format(Locale.US, "%.0fK", a / 1e3)
        else -> String.format(Locale.US, "%.0f", a)
    }
    return (if (v < 0) "-$" else "$") + s
}

fun fmtPrice(v: Double): String = String.format(Locale.US, "%,.0f", v)
fun fmtPct(v: Double, digits: Int = 2): String = String.format(Locale.US, "%+.${digits}f%%", v)

/** Fluxo de ETF em milhões → texto. */
fun fmtMillions(m: Double): String = fmtUsd(m * 1e6)

@Composable
fun Label(text: String) = Text(text.uppercase(), color = C.Muted, fontSize = 11.sp, letterSpacing = 1.sp)

@Composable
fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color = C.Text) {
    Column {
        Label(label)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, s ->
            val on = i == selected
            Text(
                s,
                color = if (on) C.Bg else C.Muted,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) C.Text else C.Surface)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun LoadingOrError(loading: Boolean, error: String?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(color = C.Accent)
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
                Text("Tentar de novo", color = C.Accent, modifier = Modifier.clickable { onRetry() })
            }
        }
    }
}
