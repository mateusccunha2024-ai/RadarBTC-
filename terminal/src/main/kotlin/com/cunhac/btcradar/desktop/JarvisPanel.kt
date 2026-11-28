package com.cunhac.btcradar.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.engine.TradeMarket
import com.cunhac.btcradar.engine.TraderSkill
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Botão da barra do topo que abre/fecha o assistente. */
@Composable
fun JarvisButton(open: Boolean, short: Boolean = false, onClick: () -> Unit) = Tap(onClick) {
    Row(Modifier.background(if (open) Q.Accent else Q.Panel2).border(1.dp, Q.Accent)
        .padding(horizontal = if (compact) 12.dp else 10.dp, vertical = if (compact) 8.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (open) Q.Bg else Q.Accent))
        Spacer(Modifier.width(6.dp))
        T(if (short) "Jarvis" else "Jarvis · IA", if (open) Q.Bg else Q.Accent, 12, bold = true, tr = false)
    }
}

private val SUGGESTIONS = listOf(
    "Como está o BTC agora?", "Plano de trade BTC", "Time Machine TSLA", "Scanner de ações", "Resumo macro de hoje",
    "Onde estão as paredes?", "Liquidações da última hora", "A bolsa está aberta?",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JarvisPanel(ctx: AppContext, onClose: () -> Unit, modifier: Modifier = Modifier) {
    // Telas do painel: conversa, mapa do cérebro (habilidades) e configurações
    var view by remember { mutableStateOf(JarvisUi.initialView) }
    val settings = view == JarvisView.SETTINGS
    androidx.compose.runtime.DisposableEffect(Unit) {
        JarvisCrash.panelOpened()
        onDispose { JarvisCrash.panelClosed() }
    }
    LaunchedEffect(Unit) { Jarvis.prepare() }
    Column(modifier.background(Q.Panel).border(1.dp, Q.Line)) {
        // Cabeçalho
        Row(Modifier.fillMaxWidth().background(Q.Bg).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (Jarvis.listening) Q.Sell else if (Jarvis.busy || Jarvis.speaking) Q.Warn else Q.Accent))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                T("JARVIS", Q.Text, 14, bold = true, tr = false)
                T(brainLabel(), brainColor(), 11)
            }
            if (Jarvis.speaking) { MiniButton("■ Parar voz") { Jarvis.stopVoice() }; Spacer(Modifier.width(4.dp)) }
            MiniButton(if (JarvisSettings.voiceOn) "Voz: ligada" else "Voz: desligada", on = JarvisSettings.voiceOn) {
                JarvisSettings.useVoiceOn(!JarvisSettings.voiceOn); if (!JarvisSettings.voiceOn) Jarvis.stopVoice()
            }
            Spacer(Modifier.width(4.dp))
            MiniButton("Mapa", on = view == JarvisView.MAP) { view = if (view == JarvisView.MAP) JarvisView.CHAT else JarvisView.MAP }
            Spacer(Modifier.width(4.dp))
            MiniButton("⚙", on = settings) { view = if (settings) JarvisView.CHAT else JarvisView.SETTINGS }
            Spacer(Modifier.width(4.dp))
            MiniButton("✕") { onClose() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
        BrainErrorBar()
        if (settings) androidx.compose.runtime.CompositionLocalProvider(LocalJarvisCtx provides ctx) { JarvisSettingsView(Modifier.weight(1f).fillMaxWidth()) }
        else if (view == JarvisView.MAP) androidx.compose.runtime.CompositionLocalProvider(LocalJarvisCtx provides ctx) {
            JarvisBrainMap(Modifier.weight(1f).fillMaxWidth()) { view = it }
        }
        else {
            val list = rememberLazyListState()
            val n = Jarvis.messages.size
            LaunchedEffect(n, Jarvis.busy) { if (n > 0) list.animateScrollToItem(n - 1 + (if (Jarvis.busy) 1 else 0)) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                if (n == 0) item { Welcome(ctx) }
                itemsIndexed(Jarvis.messages) { i, m -> Message(ctx, m, Jarvis.messages.getOrNull(i - 1)?.takeIf { it.user }?.text ?: "") }
                if (Jarvis.busy) item { T("Buscando os dados e pensando…", Q.Muted, 12, modifier = Modifier.padding(4.dp)) }
                item { Spacer(Modifier.height(4.dp)) }
            }
            Jarvis.notice?.let { msg ->
                Row(Modifier.fillMaxWidth().background(Q.Panel2).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(msg, Q.Warn, 11, wrap = true, modifier = Modifier.weight(1f))
                    MiniButton("OK") { Jarvis.notice = null }
                }
            }
            InputRow(ctx)
        }
    }
}

enum class JarvisView { CHAT, MAP, SETTINGS }

/** Tela em que o painel abre (as capturas de tela automáticas abrem direto no mapa). */
object JarvisUi { var initialView = JarvisView.CHAT }

/** IA com erro: motivo inteiro e o botão "Tentar de novo" sempre à vista (antes só existia no cartão de boas-vindas). */
@Composable
private fun BrainErrorBar() {
    val err = Jarvis.brainError ?: return
    if (Jarvis.brain != BrainState.ERROR) return
    Column(Modifier.fillMaxWidth().background(Q.Panel2).padding(horizontal = 10.dp, vertical = 6.dp)) {
        T(err, Q.Sell, 11, wrap = true, tr = false)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniButton("Tentar de novo", on = true) { Jarvis.retryCrashed() }
            if (JarvisSettings.llm != JarvisModels.LLM_LIGHT.id) MiniButton("Usar a IA leve") { JarvisSettings.useLlm(JarvisModels.LLM_LIGHT.id); Jarvis.retryCrashed() }
        }
    }
}

@Composable
private fun brainLabel(): String = when (Jarvis.brain) {
    BrainState.READY -> L("IA local pronta") + " · " + (JarvisSettings.llmItem?.label ?: "")
    BrainState.IDLE -> L("IA local baixada: carrega na primeira pergunta")
    BrainState.LOADING -> L("Carregando a IA local…")
    BrainState.NOT_INSTALLED -> L("IA local não baixada: respondo com os dados do app")
    BrainState.ERROR -> L("IA local com erro: respondo com os dados do app")
    BrainState.UNSUPPORTED -> L("Aparelho sem suporte à IA local: respondo com os dados do app")
    BrainState.OFF -> L("IA local desligada: respondo com os dados do app")
}

@Composable
private fun brainColor() = when (Jarvis.brain) { BrainState.READY, BrainState.IDLE -> Q.Buy; BrainState.LOADING -> Q.Warn; BrainState.ERROR -> Q.Sell; else -> Q.Muted }

@Composable
internal fun MiniButton(label: String, on: Boolean = false, color: Color = Q.Text, onClick: () -> Unit) = Tap(onClick) {
    T(label, if (on) Q.Accent else color, 11, modifier = Modifier.background(if (on) Q.Panel2 else Q.Panel).border(1.dp, if (on) Q.Accent else Q.Line)
        .padding(horizontal = if (compact) 10.dp else 7.dp, vertical = if (compact) 7.dp else 3.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(ctx: AppContext) {
    Column(Modifier.fillMaxWidth().padding(4.dp)) {
        T("Pergunte qualquer coisa do app. Eu leio os dados ao vivo (livro, fluxo, liquidações, macro, ETFs, notícias) e respondo em texto e voz.", Q.Text, 13, wrap = true)
        Spacer(Modifier.height(6.dp))
        T("Para trades: entrada no rompimento da abertura de 15 min, stop, alvo, Time Machine com 10 mil simulações, tamanho e risco. Nunca envio ordens.", Q.Muted, 12, wrap = true)
        if (JarvisCrash.safeMode || JarvisCrash.last != null) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().border(1.dp, Q.Warn).padding(10.dp)) {
                T("O app fechou da última vez", Q.Warn, 13, bold = true)
                JarvisCrash.last?.let { T(L("Motivo") + ": " + it, Q.Text, 12, wrap = true, tr = false) }
                if (JarvisCrash.safeMode) T("Estava com o Jarvis aberto. Para não repetir, desta vez nada roda sozinho ao abrir (pesquisa e sincronização ficam para depois). A IA, a voz e o microfone só carregam quando você usar, e quem fechar o app fica desligado.", Q.Muted, 11, wrap = true)
                Spacer(Modifier.height(6.dp))
                OutlineButton("Entendi") { JarvisCrash.dismiss() }
            }
        }
        if (Jarvis.crashedList.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().border(1.dp, Q.Sell).padding(10.dp)) {
                T("A IA local caiu neste aparelho (o app continuou aberto)", Q.Sell, 13, bold = true)
                T(L("O Jarvis desligou estes modelos até você tentar de novo") + ": " + Jarvis.crashedList.joinToString(", ") + ". " +
                    L("Enquanto isso ele responde com os dados do app."), Q.Text, 12, wrap = true, tr = false)
                Spacer(Modifier.height(6.dp))
                OutlineButton("Tentar de novo") { Jarvis.retryCrashed() }
            }
        }
        val missing = listOfNotNull(JarvisSettings.llmItem, JarvisModels.VOICE, JarvisSettings.micItem).filter { !JarvisModels.installed(it) }
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            SetupCard(missing)
        }
        Spacer(Modifier.height(10.dp))
        T("Experimente", Q.Muted, 11, bold = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SUGGESTIONS.forEach { s -> MiniButton(s) { Jarvis.ask(ctx, L(s)) } }
        }
    }
}

/** Primeiro uso: baixar os modelos que rodam no aparelho. */
@Composable
private fun SetupCard(missing: List<JarvisModels.Item>) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().border(1.dp, Q.Accent).padding(10.dp)) {
        T("Ativar IA e voz no aparelho", Q.Accent, 13, bold = true)
        T("Baixa uma vez os modelos abertos; depois tudo roda sem internet e sem conta.", Q.Muted, 11, wrap = true)
        Spacer(Modifier.height(6.dp))
        missing.forEach { ModelRow(it) }
        val total = missing.sumOf { it.sizeMb }
        Spacer(Modifier.height(6.dp))
        if (missing.none { JarvisModels.progress.containsKey(it.id) })
            OutlineButton("Baixar tudo (~${sizeText(total)})") {
                scope.launch { safely { for (i in missing) if (!JarvisModels.download(i)) break; Jarvis.prepare() } }
            }
    }
}

private fun sizeText(mb: Int) = if (mb >= 1000) String.format(BR, "%.1f GB", mb / 1024.0) else "$mb MB"

@Composable
private fun ModelRow(i: JarvisModels.Item) {
    val scope = rememberCoroutineScope()
    val p = JarvisModels.progress[i.id]
    val ok = JarvisModels.installed(i)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(i.label, Q.Text, 12, bold = true, wrap = true)
                T(L(i.note) + " · " + sizeText(i.sizeMb), Q.Muted, 11, wrap = true, tr = false)
            }
            when {
                p != null -> T(String.format(BR, "%.0f%%", p * 100), Q.Warn, 12, monoFont = true)
                ok -> MiniButton("Apagar", color = Q.Muted) { JarvisModels.delete(i); Jarvis.prepare() }
                else -> MiniButton("Baixar", on = true) { scope.launch { safely { JarvisModels.download(i); Jarvis.prepare() } } }
            }
        }
        if (p != null) Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp).background(Q.Panel2)) {
            Box(Modifier.fillMaxWidth(p.coerceIn(0.01f, 1f)).height(4.dp).background(Q.Accent))
        }
        JarvisModels.errors[i.id]?.let { T(L("Falhou") + ": $it", Q.Sell, 11, wrap = true, tr = false) }
    }
}

@Composable
private fun Message(ctx: AppContext, m: JarvisMsg, question: String = "") {
    if (m.user) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            T(m.text, Q.Text, 13, wrap = true, tr = false, modifier = Modifier.widthIn(max = 320.dp).background(Q.Panel2).border(1.dp, Q.Line).padding(8.dp))
        }
        return
    }
    var showFacts by remember { mutableStateOf(false) }
    var rated by remember { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxWidth().border(1.dp, Q.Line).padding(8.dp)) {
        T(m.text, Q.Text, 13, wrap = true, tr = false)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T(L(m.engine) + (if (m.facts.isNotEmpty()) " · ${m.facts.size} " + L("dados usados") else ""), Q.Muted, 10, tr = false,
                modifier = if (m.facts.isNotEmpty()) Modifier.clickable { showFacts = !showFacts } else Modifier)
            Spacer(Modifier.weight(1f))
            if (Jarvis.voiceReady) T("▶ " + L("ouvir"), Q.Accent, 10, tr = false, modifier = Modifier.clickable { Jarvis.say(m.text) }.padding(horizontal = 4.dp))
            // Avaliação: 👍 vira exemplo de treino; 👎 vira pesquisa do dia seguinte.
            if (rated == null && question.isNotBlank()) {
                T("👍", Q.Muted, 12, tr = false, modifier = Modifier.clickable { rated = true; JarvisFeedback.record(question, m.text, m.engine, true, m.facts) }.padding(horizontal = 6.dp))
                T("👎", Q.Muted, 12, tr = false, modifier = Modifier.clickable { rated = false; JarvisFeedback.record(question, m.text, m.engine, false, m.facts) }.padding(horizontal = 6.dp))
            } else if (rated != null) T(if (rated == true) L("obrigado") else L("vou pesquisar isso"), Q.Muted, 10, tr = false, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (showFacts) Column(Modifier.padding(top = 4.dp)) {
            m.facts.forEach { f -> T("• " + L(f.text) + "  [" + f.source + "]", Q.Muted, 11, wrap = true, tr = false) }
        }
        m.card?.let { Spacer(Modifier.height(6.dp)); TradeCardView(ctx, it) }
        m.scan?.let { Spacer(Modifier.height(6.dp)); ScanView(ctx, it) }
    }
}

// ------------------------------------------------------------------ cartão do plano de trade

@Composable
private fun TradeCardView(ctx: AppContext, c: TradeCard) {
    val clip = LocalClipboardManager.current
    var confirm by remember { mutableStateOf(false) }
    var recorded by remember { mutableStateOf(JarvisTrade.watching(c.symbol)) }
    Column(Modifier.fillMaxWidth().background(Q.Bg).border(1.dp, Q.Line).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("${c.symbol}" + (c.name?.let { " · $it" } ?: ""), Q.Text, 14, bold = true, tr = false, modifier = Modifier.weight(1f))
            if (c.hypothetical) Badge("HIPOTÉTICO", Q.Warn)
        }
        T(c.marketNote, Q.Muted, 11, wrap = true)
        Spacer(Modifier.height(4.dp))
        Step("1 · Entrada (abertura de 15 min)")
        KV("Máxima / mínima da abertura", "${px(c.entry.orHigh)} / ${px(c.entry.orLow)}")
        KV("Último preço", px(c.last))
        KV("Situação", entryText(c))
        c.refusal?.let { T(it, Q.Sell, 11, wrap = true) }
        // O trade de hoje já acabou: aviso em destaque (o resto do cartão fica como registro)
        c.progress?.takeIf { it.status != TraderSkill.ProgressStatus.OPEN }?.let { pr ->
            val stopped = pr.status == TraderSkill.ProgressStatus.STOPPED
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, if (stopped) Q.Sell else Q.Buy).padding(8.dp)) {
                T(if (stopped) "SETUP ENCERRADO: JÁ BATEU NO STOP" else "SETUP ENCERRADO: JÁ CHEGOU AO ALVO", if (stopped) Q.Sell else Q.Buy, 12, bold = true)
                T(L(if (stopped) "O preço tocou o stop depois da entrada. Não há entrada agora; espere o próximo dia." else
                    "O movimento já aconteceu depois da entrada. Entrar agora seria correr atrás do preço.") +
                    (pr.price?.let { " (" + px(it) + ")" } ?: ""), Q.Text, 11, wrap = true, tr = false)
            }
        }
        c.plan?.let { p ->
            Step("2 · Saída (stop no meio da abertura, alvo 2R)")
            KV(if (c.hypothetical) "Entrada (se romper)" else "Entrada", px(p.entry))
            KV("Stop", px(p.stop), Q.Sell)
            KV("Alvo", px(p.target), Q.Buy)
            KV("Risco por unidade", num(p.risk, 2))
        }
        c.mc?.let { mc ->
            Step("3 · Time Machine · 10.000 replays")
            OutcomeBar(mc)
            val (l, _) = TraderSkill.lean(mc)
            T(Jarvis.leanText(l), Q.Text, 12, bold = true, tr = false, wrap = true, modifier = Modifier.padding(top = 4.dp))
            KV("Fechamento mediano (5% – 95%)", "${px(mc.medianClose)} (${px(mc.p5Close)} – ${px(mc.p95Close)})")
            KV("R médio por replay", String.format(BR, "%+.3f", mc.avgR))
            KV("Sessões usadas no sorteio", "${mc.sessionsUsed}")
            c.plan?.let { FanChart(mc, it) }
        }
        c.history?.let { h ->
            Step("Histórico da mesma regra")
            if (h.triggered == 0) T(L("Nenhum gatilho nas") + " ${h.sessions} " + L("sessões passadas."), Q.Muted, 11, tr = false)
            else {
                KV("Sessões / gatilhos", "${h.sessions} / ${h.triggered}")
                KV("Alvo · stop · fechamento", "${h.target} · ${h.stop} · ${h.close}")
                h.avgR?.let { KV("R médio", String.format(BR, "%+.2f", it)) }
            }
        }
        c.sizing?.let { s ->
            Step("4 · Tamanho (suas regras)")
            KV("Quantidade", TraderSkill.fmtQty(s.qty, c.market) + " " + (if (c.market == TradeMarket.BTC) "BTC" else "ações"))
            KV("Custo", "US$ " + num(s.cost))
            KV("Perda máxima no stop", "US$ " + num(s.maxLoss), Q.Sell)
            KV("Retorno / risco", num(s.rewardRisk, 2))
            KV("Limite que decidiu", s.boundBy)
            s.refusal?.let { T(it, Q.Sell, 11, wrap = true) }
        }
        c.risk?.let { r ->
            Step("5 · Risco")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(if (r.pass) "PASSA" else "BLOQUEADO", if (r.pass) Q.Buy else Q.Sell)
            }
            r.checks.forEach { ck ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    T(if (ck.pass) "✓" else "✕", if (ck.pass) Q.Buy else Q.Sell, 12, bold = true, tr = false, modifier = Modifier.width(18.dp))
                    T(L(ck.name), Q.Text, 11, wrap = true, modifier = Modifier.weight(1f))
                    T(ck.detail, Q.Muted, 11, monoFont = true, tr = false)
                }
            }
        }
        c.ticket?.let { tk ->
            Step("6 · Boleta (só leitura, conta paper)")
            Box(Modifier.fillMaxWidth().background(Q.Panel2).horizontalScroll(rememberScrollState()).padding(6.dp)) {
                T(tk, Q.Text, 11, monoFont = true, tr = false)
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniButton("Copiar boleta") { clip.setText(AnnotatedString(tk)); Jarvis.notice = L("Boleta copiada.") }
                val canRecord = c.risk?.pass == true && !c.hypothetical
                if (recorded) T("● " + L("Paper trade registrado; alertas de stop, alvo e zerar ligados."), Q.Buy, 11, wrap = true, tr = false)
                else if (canRecord && !confirm) MiniButton("Registrar paper trade…", on = true) { confirm = true }
            }
            if (confirm && !recorded) Column(Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, Q.Warn).padding(8.dp)) {
                T(L("Confirma registrar um trade de TESTE") + ": ${TraderSkill.fmtQty(c.sizing?.qty ?: 0.0, c.market)} ${c.symbol}, " + L("perda máxima") +
                    " US$ ${num(c.sizing?.maxLoss ?: 0.0)}? " + L("Nenhuma ordem será enviada a corretora."), Q.Text, 12, wrap = true, tr = false)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniButton("Sim, registrar", on = true) {
                        PaperJournal.record(c.market, c.sizing?.maxLoss ?: 0.0)
                        c.plan?.let { JarvisTrade.watch(ctx, it) }
                        recorded = true; confirm = false
                    }
                    MiniButton("Cancelar") { confirm = false }
                }
            }
        }
        if (c.news.isNotEmpty()) {
            Step("Notícias")
            c.news.forEach { (t, s) -> T("• " + I18n.news(t) + "  [$s]", Q.Muted, 11, wrap = true, tr = false) }
        }
        Spacer(Modifier.height(6.dp))
        T(L("Fonte") + ": " + L(c.dataSource) + ". " + L("Simulação feita com os candles do próprio ativo, não é previsão. Day trade dá prejuízo à maioria das pessoas."),
            Q.Muted, 10, wrap = true, tr = false)
    }
}

private fun entryText(c: TradeCard) = when (c.entry.status) {
    TraderSkill.EntryStatus.TRIGGERED -> "Rompeu: fechou acima da máxima da abertura"
    TraderSkill.EntryStatus.WAITING -> "Aguardando o fechamento acima da máxima"
    TraderSkill.EntryStatus.BUILDING_RANGE -> "Formando a abertura"
    TraderSkill.EntryStatus.EXPIRED -> "Sem gatilho dentro da janela de entradas"
    TraderSkill.EntryStatus.NO_DATA -> "Sem pregão hoje"
}

@Composable
private fun Step(title: String) {
    T(L(title).uppercase(BR), Q.Accent, 11, bold = true, tr = false, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

/** Alvo primeiro / stop primeiro / nenhum: barra empilhada com rótulos (nunca só cor). */
@Composable
private fun OutcomeBar(mc: TraderSkill.MonteCarlo) {
    val parts = listOf(Triple(L("Alvo primeiro"), mc.pTarget, Q.Buy), Triple(L("Stop primeiro"), mc.pStop, Q.Sell), Triple(L("Nenhum até zerar"), mc.pNeither, Q.Muted))
    Row(Modifier.fillMaxWidth().height(14.dp)) {
        parts.forEachIndexed { i, (_, p, c) ->
            if (p > 0) Box(Modifier.weight(max(p, 0.001).toFloat()).fillMaxHeight().padding(start = if (i > 0) 2.dp else 0.dp).background(c))
        }
    }
    // Uma linha por resultado: a legenda nunca fica cortada, nem em painel estreito.
    Column(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        parts.forEach { (l, p, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(c)); Spacer(Modifier.width(4.dp))
                T(l, Q.Text, 11, tr = false, modifier = Modifier.weight(1f))
                T(String.format(BR, "%.1f%%", p * 100), Q.Text, 11, bold = true, monoFont = true, tr = false)
            }
        }
    }
}

/** Leque do resto do dia: faixas 5–95% e 25–75% dos 2.000 caminhos, mediana, entrada, stop e alvo. */
@Composable
private fun FanChart(mc: TraderSkill.MonteCarlo, p: TraderSkill.Plan) {
    val tm = rememberTextMeasurer()
    val muted = Q.Muted; val acc = Q.Accent; val buy = Q.Buy; val sell = Q.Sell; val line = Q.Line; val txt = Q.Text
    Canvas(Modifier.fillMaxWidth().padding(top = 6.dp).height(150.dp)) {
        val b = mc.bands
        if (b.size < 2) return@Canvas
        val lo = min(b.minOf { it[0] }, p.stop) ; val hi = max(b.maxOf { it[4] }, p.target)
        val pad = (hi - lo) * 0.06
        val y0 = lo - pad; val y1 = hi + pad
        val labelW = 64.dp.toPx()
        val w = size.width - labelW; val h = size.height
        fun x(i: Int) = i * w / (b.size - 1)
        fun y(v: Double) = (h * (1 - (v - y0) / (y1 - y0))).toFloat()
        fun band(a: Int, c: Int, color: Color) {
            val path = Path()
            b.forEachIndexed { i, q -> if (i == 0) path.moveTo(x(i), y(q[a])) else path.lineTo(x(i), y(q[a])) }
            for (i in b.indices.reversed()) path.lineTo(x(i), y(b[i][c]))
            path.close(); drawPath(path, color)
        }
        band(0, 4, acc.copy(alpha = 0.14f))
        band(1, 3, acc.copy(alpha = 0.28f))
        val med = Path(); b.forEachIndexed { i, q -> if (i == 0) med.moveTo(x(i), y(q[2])) else med.lineTo(x(i), y(q[2])) }
        drawPath(med, acc, style = Stroke(2.dp.toPx()))
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
        // Rótulos à direita, afastados uns dos outros (antes ficavam um em cima do outro quando os preços eram próximos)
        val lines = listOf(Triple(p.target, buy, L("alvo")), Triple(p.entry, txt, L("entrada")), Triple(p.stop, sell, L("stop")))
        val rowH = 22.dp.toPx()
        val ys = lines.map { (y(it.first) - rowH / 2).coerceIn(0f, h - rowH) }.toMutableList()
        for (i in 1 until ys.size) if (ys[i] < ys[i - 1] + rowH) ys[i] = ys[i - 1] + rowH
        val over = ys.last() - (h - rowH)
        if (over > 0) for (i in ys.indices) ys[i] = (ys[i] - over).coerceAtLeast(0f)
        lines.forEachIndexed { i, (v, c, l) ->
            drawLine(c, Offset(0f, y(v)), Offset(w, y(v)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawText(tm, l, Offset(w + 4.dp.toPx(), ys[i]), TextStyle(color = c, fontSize = 9.sp))
            drawText(tm, px(v), Offset(w + 4.dp.toPx(), ys[i] + 10.dp.toPx()), TextStyle(color = c, fontSize = 9.sp))
        }
        drawLine(line, Offset(0f, h - 1), Offset(w, h - 1))
        drawText(tm, L("agora"), Offset(0f, h - 13.dp.toPx()), TextStyle(color = muted, fontSize = 9.sp))
        drawText(tm, L("zerar"), Offset(w - 30.dp.toPx(), h - 13.dp.toPx()), TextStyle(color = muted, fontSize = 9.sp))
    }
    T(L("Faixa clara: 90% dos caminhos; escura: 50%; linha: mediana."), Q.Muted, 10, wrap = true, tr = false)
}

// ------------------------------------------------------------------ scanner

@Composable
private fun ScanView(ctx: AppContext, rows: List<ScanRow>) {
    Column(Modifier.fillMaxWidth().background(Q.Bg).border(1.dp, Q.Line).padding(6.dp)) {
        T(L("Scanner · abertura de 15 min e setups do diário"), Q.Accent, 11, bold = true, tr = false)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable { Jarvis.ask(ctx, L("Plano de trade") + " ${r.symbol}") }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                T(r.symbol, Q.Text, 12, bold = true, monoFont = true, tr = false, modifier = Modifier.width(64.dp))   // cabe GOOGL
                T(px(r.last), Q.Text, 11, monoFont = true, tr = false, modifier = Modifier.width(76.dp))
                Column(Modifier.weight(1f)) {
                    T(L(r.status), when (r.status) { "ROMPEU" -> Q.Buy; "AGUARDANDO" -> Q.Warn; "ERRO" -> Q.Sell; else -> Q.Muted }, 11, bold = true, tr = false)
                    T(L(r.detail) + if (r.setups.isNotEmpty()) " · " + r.setups.joinToString(", ") { Jarvis.setupLabel(it) } else "", Q.Muted, 10, wrap = true, tr = false)
                }
            }
        }
        T(L("Toque numa ação para montar o plano completo. Dados do Yahoo Finance (podem atrasar)."), Q.Muted, 10, wrap = true, tr = false)
    }
}

// ------------------------------------------------------------------ entrada de texto e microfone

@Composable
private fun InputRow(ctx: AppContext) {
    var text by remember { mutableStateOf("") }
    fun send() { val t = text; text = ""; Jarvis.ask(ctx, t) }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
    Row(Modifier.fillMaxWidth().background(Q.Bg).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).background(Q.Panel2).border(1.dp, if (Jarvis.listening) Q.Sell else Q.Line)
            .padding(horizontal = 8.dp, vertical = if (compact) 12.dp else 7.dp)) {
            if (text.isEmpty()) T(if (Jarvis.listening) "Ouvindo… toque em ■ para enviar" else "Pergunte ao Jarvis…", Q.Muted, 13)
            BasicTextField(text, { text = it }, singleLine = true, textStyle = TextStyle(color = Q.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(Q.Accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }, onDone = { send() }), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(6.dp))
        if (JarvisNative.micSupported) {
            Tap({ Jarvis.toggleMic(ctx) }) {
                T(if (Jarvis.listening) "■" else "🎤", if (Jarvis.listening) Q.Bg else Q.Text, 14, bold = true, tr = false,
                    modifier = Modifier.background(if (Jarvis.listening) Q.Sell else Q.Panel2).border(1.dp, if (Jarvis.listening) Q.Sell else Q.Line)
                        .padding(horizontal = 12.dp, vertical = if (compact) 10.dp else 6.dp))
            }
            Spacer(Modifier.width(6.dp))
        }
        Tap({ send() }, enabled = text.isNotBlank() && !Jarvis.busy) {
            T("Enviar", if (text.isNotBlank() && !Jarvis.busy) Q.Bg else Q.Muted, 13, bold = true,
                modifier = Modifier.background(if (text.isNotBlank() && !Jarvis.busy) Q.Accent else Q.Panel2).border(1.dp, Q.Accent)
                    .padding(horizontal = 12.dp, vertical = if (compact) 10.dp else 6.dp))
        }
    }
}

// ------------------------------------------------------------------ configurações

@Composable
private fun JarvisSettingsView(modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
        Section("Cérebro (IA no aparelho)") {
            Column {
                val opts = listOf("off", JarvisModels.LLM_LIGHT.id, JarvisModels.LLM_STD.id) + (if (Platform.isMobile) emptyList() else listOf(JarvisModels.LLM_PRO.id))
                Chips(null, listOf("Desligada", "Leve 0,5B", "Padrão 1,5B") + (if (Platform.isMobile) emptyList() else listOf("Forte 7B")), opts.indexOf(JarvisSettings.llm).coerceAtLeast(0)) {
                    JarvisSettings.useLlm(opts[it]); Jarvis.prepare()
                }
                if (!JarvisNative.llmSupported) T("Este aparelho não roda a IA local (precisa de Android 9+ e 64 bits).", Q.Warn, 11, wrap = true)
                Jarvis.brainError?.takeIf { Jarvis.brain == BrainState.ERROR }?.let {
                    T(L("Erro") + ": $it", Q.Sell, 11, wrap = true, tr = false)
                    Spacer(Modifier.height(4.dp))
                    MiniButton("Tentar de novo", on = true) { Jarvis.retryCrashed() }
                }
                if (!Platform.isMobile) T(L("Biblioteca da IA") + ": " + L(JarvisNative.libraryLabel()), Q.Muted, 11, tr = false)
                ModelRow(JarvisModels.LLM_LIGHT); ModelRow(JarvisModels.LLM_STD); if (!Platform.isMobile) ModelRow(JarvisModels.LLM_PRO)
                Footnote("A IA recebe só os dados que o app já mostra e é instruída a não inventar números. Sem a IA, o assistente responde com os próprios dados do app.")
            }
        }
        Spacer(Modifier.height(8.dp))
        IntelligenceSection()
        Spacer(Modifier.height(8.dp))
        Section("Voz própria") {
            Column {
                ModelRow(JarvisModels.VOICE)
                val voices = JarvisVoices.ALL.filter { it.app == I18n.lang.code || it.app == "en" }
                Chips(null, voices.map { it.label }, voices.indexOfFirst { it.sid == JarvisSettings.voice.sid }.coerceAtLeast(0)) {
                    JarvisSettings.useVoice(voices[it].sid); Jarvis.prepare()
                }
                val speeds = listOf(0.9f, 1.0f, 1.1f, 1.25f)
                Chips("Velocidade", speeds.map { String.format(BR, "%.2fx", it) }, speeds.indexOfFirst { it == JarvisSettings.speed }.coerceAtLeast(1)) {
                    JarvisSettings.useSpeed(speeds[it])
                }
                if (Jarvis.voiceReady) MiniButton("▶ Testar a voz") { Jarvis.say(L("Olá. Sou o Jarvis, do Radar BTC. Pergunte o que quiser sobre o mercado.")) }
                Footnote("Voz neural Kokoro, gerada no aparelho (licença Apache 2.0). Nenhum serviço pago ou externo é usado.")
            }
        }
        Spacer(Modifier.height(8.dp))
        Section("Microfone") {
            Column {
                val opts = listOf(JarvisModels.MIC_TINY.id, JarvisModels.MIC_BASE.id)
                Chips(null, listOf("Leve (tiny)", "Melhor (base)"), opts.indexOf(JarvisSettings.mic).coerceAtLeast(0)) { JarvisSettings.useMic(opts[it]); Jarvis.prepare() }
                ModelRow(JarvisSettings.micItem)
                Footnote("Toque no microfone, fale e toque de novo para enviar. O áudio é transcrito no aparelho (Whisper) e não é gravado.")
            }
        }
        Spacer(Modifier.height(8.dp))
        Section("Regras de risco (paper trading)") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val r = JarvisSettings.rules
                NumField("Conta (US$)", r.budget, 110.dp) { v -> v?.takeIf { it > 0 }?.let { JarvisSettings.setRule("budget", it) } }
                NumField("Perda máxima por trade (US$)", r.maxLossPerTrade, 90.dp) { v -> v?.takeIf { it > 0 }?.let { JarvisSettings.setRule("maxloss", it) } }
                NumField("Perda máxima no dia (US$)", r.maxDailyLoss, 90.dp) { v -> v?.takeIf { it > 0 }?.let { JarvisSettings.setRule("daily", it) } }
                NumField("Trades por dia", r.maxTradesPerDay.toDouble(), 60.dp) { v -> v?.takeIf { it >= 1 }?.let { JarvisSettings.setRule("trades", it) } }
                NumField("Retorno/risco mínimo", r.minRewardRisk, 60.dp) { v -> v?.takeIf { it > 0 }?.let { JarvisSettings.setRule("minrr", it) } }
                NumField("Reserva de caixa (%)", r.cashBufferPct, 60.dp) { v -> v?.takeIf { it in 0.0..90.0 }?.let { JarvisSettings.setRule("buffer", it) } }
                NumField("Teto da posição (%)", r.maxPositionPct, 60.dp) { v -> v?.takeIf { it in 1.0..100.0 }?.let { JarvisSettings.setRule("maxpos", it) } }
                NumField("Ordem máxima (US$)", r.maxOrderUsd, 90.dp) { v -> v?.takeIf { it > 0 }?.let { JarvisSettings.setRule("maxorder", it) } }
                Footnote("O assistente nunca envia ordens. A boleta é só para leitura e o registro de paper trade serve para os limites diários.")
            }
        }
        Spacer(Modifier.height(8.dp))
        Section("Lista do scanner (ações dos EUA)") {
            Column {
                var w by remember { mutableStateOf(JarvisSettings.watchlist) }
                BasicTextField(w, { w = it.uppercase(); JarvisSettings.useWatchlist(w) }, textStyle = TextStyle(color = Q.Text, fontSize = 12.sp, fontFamily = mono.fontFamily),
                    cursorBrush = SolidColor(Q.Accent), modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).background(Q.Panel2).border(1.dp, Q.Line).padding(6.dp))
                T(L("Separe os tickers por vírgula. Até 25.") + " (${JarvisSettings.symbols().size})", Q.Muted, 11, tr = false)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlineButton("Limpar conversa", color = Q.Muted) { Jarvis.clear() }
        Spacer(Modifier.height(8.dp))
        T(L("Créditos: fluxo de trade inspirado no day-trader-skill (MIT, Jake Schincariol). IA: Qwen2.5 (Apache 2.0) via llama.cpp. Voz: Kokoro-82M (Apache 2.0) e Whisper (MIT) via sherpa-onnx."),
            Q.Muted, 10, wrap = true, tr = false)
    }
}

// ------------------------------------------------------------------ aprender sem parar

/** Botão grande liga/desliga do aprendizado contínuo, com o que está fazendo agora. Usado no ⚙ e no mapa do cérebro. */
@Composable
internal fun LearnSwitch() {
    // Atualiza a contagem regressiva sem depender de outros eventos
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(JarvisLearner.on) { while (JarvisLearner.on) { kotlinx.coroutines.delay(15_000); tick++ } }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val on = JarvisLearner.on
    Column(Modifier.fillMaxWidth().border(1.dp, if (on) Q.Buy else Q.Line).background(if (on) Q.Panel2 else Q.Panel).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (on) Q.Buy else Q.Muted))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                T(if (on) "APRENDENDO SEM PARAR" else "APRENDER SEM PARAR: DESLIGADO", if (on) Q.Buy else Q.Text, 12, bold = true)
                T(L(JarvisLearner.phaseText()) + (if (on && JarvisLearner.cycles > 0) " · ${JarvisLearner.cycles} " + L("rodadas") + " · +${JarvisLearner.learnedTotal} " + L("itens") else ""),
                    Q.Muted, 11, wrap = true, tr = false)
            }
            OutlineButton(if (on) "Desligar" else "Ligar", color = if (on) Q.Sell else Q.Buy) { JarvisLearner.setOn(!on) }
        }
        if (JarvisLearner.lastResult.isNotBlank()) T(L("Última rodada") + ": " + JarvisLearner.lastResult, Q.Text, 11, wrap = true, tr = false, modifier = Modifier.padding(top = 4.dp))
    }
}

// ------------------------------------------------------------------ inteligência que cresce sozinha

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntelligenceSection() {
    val scope = rememberCoroutineScope()
    val ctx = LocalJarvisCtx.current
    Section("Inteligência (aprende sozinho)") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Prova
            T("Prova", Q.Accent, 12, bold = true)
            val last = JarvisEval.last
            val hist = JarvisEval.history
            if (last != null) {
                KV("Nota (0–100)", "${last.score}")
                KV("Roteamento certo", "${last.routing} / ${last.total}")
                KV("Perguntas com dados", "${last.answered} / ${last.total}")
                if (last.llmAnswers > 0) KV("Respostas da IA com números conferidos", "${last.grounded} / ${last.llmAnswers}")
                KV("Tempo médio por resposta", String.format(BR, "%.1f s", last.avgMs / 1000.0))
            } else if (hist.isNotEmpty()) KV("Última nota", "${hist.last()}")
            if (hist.size >= 2) T(L("Evolução das notas") + ": " + hist.takeLast(10).joinToString(" → "), Q.Muted, 11, wrap = true, tr = false)
            if (ctx != null) OutlineButton(if (JarvisEval.running) "Rodando a prova…" else "Rodar a prova", enabled = !JarvisEval.running) { scope.launch { safely { JarvisEval.run(ctx) } } }
            Footnote("Perguntas fixas que medem se o Jarvis entende o pedido, acha os dados e não inventa números. Toda mudança tem de manter ou subir a nota.")

            // Memória e pesquisa diária
            T("Memória e pesquisa diária", Q.Accent, 12, bold = true, modifier = Modifier.padding(top = 6.dp))
            KV("Conhecimentos guardados", "${JarvisMemory.size} (${JarvisMemory.confirmed} " + L("confirmados") + ")")
            KV("Última pesquisa", if (JarvisResearch.lastMs == 0L) "—" else java.text.SimpleDateFormat("dd/MM HH:mm", BR).format(java.util.Date(JarvisResearch.lastMs)) + " · +" + JarvisResearch.lastAdded)
            Toggle("Pesquisar na internet todo dia (só no Wi-Fi)", JarvisResearch.enabled) { JarvisResearch.useEnabled(it) }
            OutlineButton(if (JarvisResearch.running) "Pesquisando…" else "Pesquisar agora", enabled = !JarvisResearch.running) { scope.launch { safely { JarvisResearch.run(force = true) } } }
            Footnote("Busca manchetes recentes (Google News) sobre o BTC, sua lista, as perguntas que ele não soube e as habilidades ligadas. Guarda com fonte e data; algo só conta como confirmado com 2 fontes. Nada do que vem da internet vira instrução para o Jarvis.")

            // Curiosidade: aprender sem parar (liga e desliga; continua ligado depois de reabrir o app)
            T("Aprende coisas novas", Q.Accent, 12, bold = true, modifier = Modifier.padding(top = 6.dp))
            LearnSwitch()
            KV("Última vez", if (JarvisCuriosity.lastMs == 0L) "—" else java.text.SimpleDateFormat("dd/MM HH:mm", BR).format(java.util.Date(JarvisCuriosity.lastMs)))
            if (JarvisCuriosity.recent.isNotEmpty()) T(L("Aprendeu recentemente") + ": " + JarvisCuriosity.recent.joinToString(", "), Q.Text, 11, wrap = true, tr = false)
            Footnote("Ligado, o Jarvis faz uma rodada a cada 20 minutos: pesquisa manchetes e, quando um nome ou termo novo de mercado aparece em 3 ou mais manchetes de 2 ou mais fontes, lê o resumo na Wikipedia e guarda com o link. Músicas, filmes e esporte ficam de fora. Só para quando você desligar.")

            // Placar
            T("Placar das previsões", Q.Accent, 12, bold = true, modifier = Modifier.padding(top = 6.dp))
            ScoreBook.version
            val st = ScoreBook.stats()
            if (st.triggered == 0) T("Ainda sem planos conferidos. Cada plano de trade é conferido no dia seguinte.", Q.Muted, 11, wrap = true)
            else {
                KV("Planos conferidos", "${st.triggered}")
                KV("Alvo · stop · nenhum", "${st.target} · ${st.stop} · ${st.close}")
                st.predictedTarget?.let { KV("Alvo previsto × real", "${num(it * 100, 0)}% × ${num(st.target * 100.0 / st.triggered, 0)}%") }
                st.brier?.let { KV("Calibração (Brier, menor é melhor)", num(it, 3)) }
            }

            // Habilidades
            T("Habilidades", Q.Accent, 12, bold = true, modifier = Modifier.padding(top = 6.dp))
            if (JarvisSkills.list.isEmpty()) T("Nenhuma ainda. Quando você perguntar 3 vezes sobre algo que ele não sabe, ele propõe uma habilidade nova para você aprovar.", Q.Muted, 11, wrap = true)
            JarvisSkills.list.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(r.name, Q.Text, 12, bold = true, wrap = true, tr = false)
                        T(L(if (r.origin == "nuvem") "do cérebro na nuvem" else "proposta pelo uso") + " · " + r.keywords.joinToString(", "), Q.Muted, 10, wrap = true, tr = false)
                    }
                    MiniButton(if (r.enabled) "Desligar" else "Aprovar", on = !r.enabled) { JarvisSkills.setEnabled(r.id, !r.enabled) }
                    Spacer(Modifier.width(4.dp))
                    MiniButton("✕", color = Q.Muted) { JarvisSkills.remove(r.id) }
                }
            }
            Footnote("Habilidade é só uma receita de pesquisa (palavras e buscas), nunca um programa. Só funciona depois que você aprova.")

            // Cérebro na nuvem
            T("Cérebro na nuvem", Q.Accent, 12, bold = true, modifier = Modifier.padding(top = 6.dp))
            KV("Situação", when (CloudBrain.state) {
                CloudBrain.State.ON -> "conectado"; CloudBrain.State.CONNECTING -> "sincronizando…"
                CloudBrain.State.ERROR -> "erro"; CloudBrain.State.OFF -> if (CloudBrain.configured) "pronto" else "não configurado"
            })
            CloudBrain.error?.let { T(it, Q.Sell, 10, wrap = true, tr = false) }
            if (CloudBrain.lastSyncMs > 0) KV("Última sincronização", java.text.SimpleDateFormat("dd/MM HH:mm", BR).format(java.util.Date(CloudBrain.lastSyncMs)))
            if (CloudBrain.configured) OutlineButton("Sincronizar agora") { scope.launch { safely { CloudBrain.sync() } } }
            Footnote("Guarda a memória, o placar e as avaliações deste aparelho (cada aparelho só vê os próprios dados) e traz habilidades aprovadas. Usa só a chave pública do projeto; a chave secreta nunca sai do servidor.")

            // Treino
            T("Treino", Q.Accent, 12, bold = true, modifier = Modifier.padding(top = 6.dp))
            KV("Respostas avaliadas", "👍 ${JarvisFeedback.good} · 👎 ${JarvisFeedback.bad}")
            var exported by remember { mutableStateOf<String?>(null) }
            OutlineButton("Exportar exemplos de treino") { exported = JarvisFeedback.exportTraining()?.path ?: L("Falhou") }
            exported?.let { T(it, Q.Muted, 10, wrap = true, tr = false) }
            Footnote("As respostas com 👍 viram exemplos para o ajuste fino da IA. As com 👎 viram pesquisa do dia seguinte.")
        }
    }
}

/** Botões do painel: um erro vira aviso, nunca fecha o app. O trabalho roda fora da tela principal. */
private suspend fun safely(block: suspend () -> Unit) {
    try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { block() } }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Throwable) {
        runCatching { FileLog.info("jarvis botão: $e") }
        Jarvis.notice = L("O Jarvis teve um erro e se recuperou") + ": " + (e.message ?: e.javaClass.simpleName).take(120)
    }
}

/** Contexto do app para a seção de inteligência (rodar a prova). */
val LocalJarvisCtx = androidx.compose.runtime.staticCompositionLocalOf<AppContext?> { null }
