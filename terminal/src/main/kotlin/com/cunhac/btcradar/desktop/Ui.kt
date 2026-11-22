package com.cunhac.btcradar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.cunhac.btcradar.engine.ConnState
import com.cunhac.btcradar.engine.WallStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Preferências de visualização (salvas entre sessões). Ler estes valores numa tela faz ela redesenhar ao mudar. */
object Look {
    var dark by mutableStateOf(SettingsStore.b("ui.dark", true)); private set
    var colorblind by mutableStateOf(SettingsStore.b("ui.cb", false)); private set
    var footnotes by mutableStateOf(SettingsStore.b("ui.notes", true)); private set
    var compact by mutableStateOf(SettingsStore.b("ui.compact", false)); private set
    fun useDark(v: Boolean) { dark = v; SettingsStore.put("ui.dark", v) }
    fun useColorblind(v: Boolean) { colorblind = v; SettingsStore.put("ui.cb", v) }
    fun useFootnotes(v: Boolean) { footnotes = v; SettingsStore.put("ui.notes", v) }
    fun useCompact(v: Boolean) { compact = v; SettingsStore.put("ui.compact", v) }
}

/**
 * Paleta com papéis fixos: fundo, superfície, superfície elevada, linha, tinta, tinta suave
 * e UM acento (laranja do Bitcoin). Compra/venda têm variante para daltonismo (azul/laranja).
 * Contraste de texto ≥ 4,5:1 nos dois temas.
 */
object Q {
    private fun c(dark: Long, light: Long) = Color(if (Look.dark) dark else light)
    val Bg get() = c(0xFF000000, 0xFFF4F5F7)
    val Panel get() = c(0xFF101317, 0xFFFFFFFF)
    val Panel2 get() = c(0xFF171B21, 0xFFECEEF2)
    val Line get() = c(0xFF262B33, 0xFFD5DAE1)
    val Text get() = c(0xFFE6E8EA, 0xFF14171C)
    val Muted get() = c(0xFF8A919E, 0xFF555D6B)
    val Buy get() = if (Look.colorblind) c(0xFF3A9BFF, 0xFF1A64C8) else c(0xFF16C784, 0xFF07875A)
    val Sell get() = if (Look.colorblind) c(0xFFFF9F1C, 0xFFB35E00) else c(0xFFEA3943, 0xFFC4202B)
    /** LONG e SHORT usam as cores de compra e venda em TODO o app (inclui a opção para daltonismo). */
    val LiqLong get() = Buy
    val LiqShort get() = Sell
    val Accent get() = c(0xFFF7931A, 0xFFB86200)
    val Warn get() = c(0xFFE0B341, 0xFF8A6500)
    /** Escala de calor (Magnet Zones): do vermelho apagado ao vermelho vivo. */
    val HeatLow get() = c(0xFF3A0C12, 0xFFFBE1E4)
    val HeatHigh get() = c(0xFFFF3B4E, 0xFFC4202B)
    /** Fundo de dicas flutuantes (tooltip). */
    val Tip get() = c(0xF0101317, 0xF5FFFFFF)
}

/** Tamanho da interface (Ctrl + / Ctrl - / Ctrl 0), salvo entre sessões. */
object UiScale {
    var scale by mutableFloatStateOf(SettingsStore.d("ui.scale", 1.0).toFloat().coerceIn(0.7f, 1.8f))
        private set
    fun set(v: Float) { scale = ((v * 100).roundToInt() / 100f).coerceIn(0.7f, 1.8f); SettingsStore.put("ui.scale", scale.toDouble()) }
    fun inc() = set((scale + 0.1f).coerceAtMost(1.8f))
    fun dec() = set((scale - 0.1f).coerceAtLeast(0.7f))
    fun reset() = set(1.0f)
}

val BR: Locale = Locale.forLanguageTag("pt-BR")
val mono: TextStyle get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Q.Text)
fun btc(v: Double, d: Int = 2) = String.format(BR, "%,.${d}f BTC", v)
fun num(v: Double, d: Int = 2) = String.format(BR, "%,.${d}f", v)
/** Quantidade de livro: valores menores que 0,001 BTC aparecem como <0,001 em vez de 0,000. */
fun qty(v: Double) = if (v > 0 && v < 0.001) "<0,001" else num(v, 3)
fun signed(v: Double, d: Int = 2) = String.format(BR, "%+.${d}f", v)
fun px(v: Double?) = if (v == null) "—" else String.format(BR, "%,.1f", v)
fun pct(v: Double?, d: Int = 2) = if (v == null) "—" else String.format(BR, "%+.${d}f%%", v)
fun usdM(v: Double) = String.format(BR, "US$ %,.2f mi", v / 1e6)
private val hms = SimpleDateFormat("HH:mm:ss")
fun hhmmss(ms: Long) = hms.format(Date(ms))
fun ago(ms: Long) = when { ms < 0 -> "nunca"; ms < 1000 -> "${ms} ms"; ms < 60_000 -> "${ms / 1000} s"; else -> "${ms / 60_000} min" }

/** Rótulos em português para a tela (os nomes internos do código não mudam). */
fun WallStatus.pt() = when (this) {
    WallStatus.NEW -> "Nova"; WallStatus.ACTIVE -> "Ativa"; WallStatus.INCREASED -> "Aumentou"; WallStatus.REDUCED -> "Reduziu"
    WallStatus.DISAPPEARED -> "Saiu"; WallStatus.POSSIBLY_EXECUTED -> "Talvez executada"; WallStatus.EXECUTION_CORROBORATED -> "Executada (confirmada)"
}
fun wallStatusPt(name: String) = WallStatus.entries.firstOrNull { it.name == name }?.pt() ?: name
fun ConnState.pt() = when (this) {
    ConnState.CONNECTING -> "Conectando"; ConnState.SUBSCRIBED -> "Inscrito"; ConnState.LIVE -> "Ao vivo"
    ConnState.RESYNCING -> "Ressincronizando"; ConnState.DOWN -> "Fora do ar"
}

/** Motivo curto para cartões; o detalhe completo fica na aba STATUS. */
fun shortReason(note: String) = when {
    note.contains("451") -> "BLOQUEADO NA REGIÃO (HTTP 451)"
    note.contains("snapshot", true) && note.contains("indispon") -> "SNAPSHOT INDISPONÍVEL"
    note.contains("sequência") -> "RECONSTRUINDO (quebra de sequência)"
    note.contains("aguardando") -> "AGUARDANDO SNAPSHOT"
    else -> note.take(42).uppercase()
}

@Composable
fun T(text: String, color: Color = Q.Text, size: Int = 12, bold: Boolean = false, monoFont: Boolean = false,
      modifier: Modifier = Modifier, wrap: Boolean = false, align: TextAlign? = null) =
    Text(text, color = color, fontSize = size.sp, lineHeight = (size + 4).sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        fontFamily = if (monoFont) FontFamily.Monospace else FontFamily.Default, modifier = modifier,
        maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, softWrap = wrap, textAlign = align)

// ---------------------------------------------------------------------
// Layout adaptável: o mesmo código serve ao PC (painéis lado a lado) e ao celular
// (painéis empilhados numa coluna com rolagem; tabelas largas rolam para o lado).
// ---------------------------------------------------------------------

/** true em telas estreitas (celular em pé). */
val LocalCompact = staticCompositionLocalOf { false }
val compact: Boolean @Composable get() = LocalCompact.current

/** Um painel de [Panes]: peso no PC e altura fixa no celular (null = altura do conteúdo). */
class Pane(val weight: Float, val mobileHeight: Dp? = null, val content: @Composable (Modifier) -> Unit)

/** Painéis lado a lado no PC; empilhados no celular. */
@Composable
fun Panes(modifier: Modifier = Modifier, vararg panes: Pane) {
    if (compact) Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        panes.forEach { p -> p.content(if (p.mobileHeight != null) Modifier.fillMaxWidth().height(p.mobileHeight) else Modifier.fillMaxWidth()) }
    } else Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        panes.forEach { p -> p.content(Modifier.weight(p.weight).fillMaxHeight()) }
    }
}

/** Ocupa o resto da coluna no PC; no celular usa altura fixa (a tela inteira rola). */
@Composable
fun ColumnScope.grow(weight: Float = 1f, mobile: Dp? = null): Modifier =
    if (compact) (if (mobile != null) Modifier.height(mobile) else Modifier) else Modifier.weight(weight)

/**
 * Painel com título. [minWidth]: largura mínima do conteúdo (tabelas); em telas menores
 * o conteúdo rola para o lado em vez de espremer as colunas.
 */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, minWidth: Dp? = null, content: @Composable () -> Unit) {
    Column(modifier.background(Q.Panel).border(1.dp, Q.Line).padding(if (Look.compact) 6.dp else 10.dp)) {
        T(title.uppercase(), Q.Muted, 11, bold = true, wrap = compact)
        if (minWidth == null) Box(Modifier.padding(top = 6.dp)) { content() }
        else BoxWithConstraints(Modifier.padding(top = 6.dp)) {
            val visible = maxWidth
            if (visible >= minWidth) content()
            else Column {
                T("◂ ▸ deslize para o lado para ver todas as colunas", Q.Accent, 10, modifier = Modifier.padding(bottom = 4.dp))
                CompositionLocalProvider(LocalViewport provides visible) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) { Box(Modifier.width(minWidth)) { content() } }
                }
            }
        }
    }
}

/** Linha de controles que quebra linha quando não cabe (zoom alto ou celular). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Controls(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.background(Q.Panel).padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** Largura visível quando a tabela rola para o lado (o estado vazio fica dentro da área visível). */
val LocalViewport = staticCompositionLocalOf<Dp?> { null }

/** Estado vazio: diz o que está acontecendo e o que esperar. */
@Composable
fun Empty(title: String, hint: String? = null) {
    val vp = LocalViewport.current
    Column((if (vp != null) Modifier.width(vp - 4.dp) else Modifier.fillMaxWidth()).padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T(title, Q.Text, 13, wrap = true, align = TextAlign.Center)
        if (hint != null) T(hint, Q.Muted, 11, wrap = true, align = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Badge(text: String, color: Color) =
    Text(text, color = color, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 1.dp))

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) { if (Look.footnotes) T(text, Q.Muted, 11, wrap = true, modifier = modifier) }

/**
 * Área de toque: no celular tem pelo menos 48 × 48 dp (padrão do Android) em volta do visual,
 * sem mudar o desenho do botão; no PC é o próprio visual.
 */
@Composable
fun Tap(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (compact) Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) { content() }
    else Box(Modifier.clickable(enabled = enabled, onClick = onClick)) { content() }
}

/** Botão com contorno (ações como Atualizar, Tentar de novo). */
@Composable
fun OutlineButton(label: String, color: Color = Q.Accent, border: Color = color, enabled: Boolean = true, size: Int = 12, onClick: () -> Unit) =
    Tap(onClick, enabled) {
        Text(label, color = color, fontSize = size.sp, lineHeight = (size + 4).sp, maxLines = 1,
            modifier = Modifier.border(1.dp, border).padding(horizontal = if (compact) 14.dp else 10.dp, vertical = if (compact) 9.dp else 5.dp))
    }

@Composable
private fun ChipItem(s: String, on: Boolean, onClick: () -> Unit) = Tap(onClick) {
    Text(s, color = if (on) Q.Accent else Q.Muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
        modifier = Modifier.background(if (on) Q.Panel2 else Q.Panel).border(1.dp, if (on) Q.Accent else Q.Line)
            .padding(horizontal = if (compact) 11.dp else 8.dp, vertical = if (compact) 8.dp else 4.dp))
}

/** Grupo de opções; quebra linha sozinho quando não cabe (celular). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(label: String?, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = compact
    FlowRow(horizontalArrangement = Arrangement.spacedBy(if (c) 2.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(if (c) 0.dp else 4.dp)) {
        if (label != null) {
            if (c) Box(Modifier.heightIn(min = 48.dp).padding(end = 4.dp), contentAlignment = Alignment.CenterStart) { T(label, Q.Muted, 11) }
            else T(label, Q.Muted, 11, modifier = Modifier.padding(end = 2.dp, top = 4.dp))
        }
        options.forEachIndexed { i, s -> ChipItem(s, i == selected) { onSelect(i) } }
    }
}

@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) = ChipItem((if (on) "■ " else "□ ") + label, on) { onChange(!on) }

/** Campo numérico; aplica o valor quando o texto vira um número válido (aceita vírgula). */
@Composable
fun NumField(label: String, value: Double?, width: Dp = 90.dp, onValue: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString().replace(".", ",") } ?: "") }
    Row(Modifier.then(if (compact) Modifier.heightIn(min = 48.dp) else Modifier), verticalAlignment = Alignment.CenterVertically) {
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
            modifier = Modifier.width(width).background(Q.Panel2).border(1.dp, Q.Line).padding(horizontal = 6.dp, vertical = if (compact) 11.dp else 4.dp),
        )
    }
}

/** Linha rótulo/valor; o valor nunca vaza do painel (corta com reticências). */
@Composable
fun KV(k: String, v: String, color: Color = Q.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        T(k, Q.Muted, 11)
        Spacer(Modifier.width(12.dp))
        T(v, color, 12, monoFont = true, modifier = Modifier.weight(1f), align = TextAlign.End)
    }
}
