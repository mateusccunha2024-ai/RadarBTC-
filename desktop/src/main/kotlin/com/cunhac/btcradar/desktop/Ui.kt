package com.cunhac.btcradar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Paleta Quant Orderflow. */
object Q {
    val Bg = Color(0xFF000000)
    val Panel = Color(0xFF101317)
    val Panel2 = Color(0xFF171B21)
    val Line = Color(0xFF262B33)
    val Text = Color(0xFFE6E8EA)
    val Muted = Color(0xFF7A8291)
    val Buy = Color(0xFF16C784)
    val Sell = Color(0xFFEA3943)
    val LiqLong = Color(0xFFFF9F1C)
    val LiqShort = Color(0xFF3A86FF)
    val Accent = Color(0xFFF7931A)
    val Warn = Color(0xFFE0B341)
}

val BR: Locale = Locale.forLanguageTag("pt-BR")
val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Q.Text)
fun btc(v: Double, d: Int = 2) = String.format(BR, "%,.${d}f BTC", v)
fun num(v: Double, d: Int = 2) = String.format(BR, "%,.${d}f", v)
fun px(v: Double?) = if (v == null) "—" else String.format(BR, "%,.1f", v)
fun pct(v: Double?, d: Int = 2) = if (v == null) "—" else String.format(BR, "%+.${d}f%%", v)
fun usdM(v: Double) = String.format(BR, "US$ %,.2f mi", v / 1e6)
private val hms = SimpleDateFormat("HH:mm:ss")
fun hhmmss(ms: Long) = hms.format(Date(ms))
fun ago(ms: Long) = when { ms < 0 -> "nunca"; ms < 1000 -> "${ms} ms"; ms < 60_000 -> "${ms / 1000} s"; else -> "${ms / 60_000} min" }

@Composable
fun T(text: String, color: Color = Q.Text, size: Int = 12, bold: Boolean = false, monoFont: Boolean = false, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        fontFamily = if (monoFont) FontFamily.Monospace else FontFamily.Default, modifier = modifier, maxLines = 1)

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.background(Q.Panel).border(1.dp, Q.Line).padding(10.dp)) {
        T(title.uppercase(), Q.Muted, 11, bold = true)
        Box(Modifier.padding(top = 6.dp)) { content() }
    }
}

@Composable
fun Chips(label: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) T(label, Q.Muted, 11, modifier = Modifier.padding(end = 2.dp))
        options.forEachIndexed { i, s ->
            val on = i == selected
            Text(s, color = if (on) Q.Bg else Q.Muted, fontSize = 11.sp,
                modifier = Modifier.background(if (on) Q.Text else Q.Panel2).clickable { onSelect(i) }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Text((if (on) "■ " else "□ ") + label, color = if (on) Q.Text else Q.Muted, fontSize = 11.sp,
        modifier = Modifier.background(Q.Panel2).clickable { onChange(!on) }.padding(horizontal = 8.dp, vertical = 4.dp))
}

/** Campo numérico; aplica o valor quando o texto vira um número válido (aceita vírgula). */
@Composable
fun NumField(label: String, value: Double?, width: Dp = 90.dp, onValue: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(label, Q.Muted, 11, modifier = Modifier.padding(end = 4.dp))
        BasicTextField(
            value = text,
            onValueChange = { t ->
                text = t
                val v = t.replace(".", "").replace(",", ".").trim()
                if (v.isEmpty()) onValue(null) else v.toDoubleOrNull()?.let(onValue)
            },
            singleLine = true,
            textStyle = mono.copy(fontSize = 12.sp),
            cursorBrush = SolidColor(Q.Accent),
            modifier = Modifier.width(width).background(Q.Panel2).border(1.dp, Q.Line).padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun KV(k: String, v: String, color: Color = Q.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        T(k, Q.Muted, 11); T(v, color, 12, monoFont = true)
    }
}
