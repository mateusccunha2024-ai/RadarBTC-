package com.cunhac.btcradar.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mapa cerebral das habilidades do Jarvis (atualização 17).
 * Cada habilidade é uma região do cérebro, como num corpo: visão = mercado ao vivo, audição = microfone, fala = voz,
 * raciocínio = IA local, memória no centro, curiosidade no alto… A cor diz o estado (verde ativa, amarelo trabalhando
 * agora, cinza desligada, vermelho com erro, tracejada = falta baixar). Tocar numa região abre a explicação e os botões.
 */

enum class SkillState(val label: String) { ACTIVE("ativa"), WORKING("trabalhando agora"), OFF("desligada"), ERROR("com erro"), MISSING("falta baixar") }

data class BrainRegion(
    val id: String, val name: String, val organ: String, val x: Float, val y: Float, val state: SkillState,
    val status: String, val explain: String, val numbers: List<Pair<String, String>> = emptyList(),
)

object JarvisBrainModel {
    /** Regiões com o estado de agora (lê os mesmos dados do painel). */
    fun regions(ctx: AppContext?): List<BrainRegion> {
        val live = runCatching { ctx?.hub?.stateFlow?.value?.liveCount() }.getOrNull() ?: 0
        val mps = runCatching { ctx?.hub?.stateFlow?.value?.msgsPerSec }.getOrNull() ?: 0.0
        val brain = Jarvis.brain
        val learner = JarvisLearner
        val eval = JarvisEval.last
        val sb = runCatching { ScoreBook.stats() }.getOrNull()
        val skillsOn = JarvisSkills.list.count { it.enabled }
        val concepts = runCatching { JarvisMemory.all().count { it.kind == "conceito" } }.getOrDefault(0)
        val voiceInstalled = runCatching { JarvisModels.installed(JarvisModels.VOICE) }.getOrDefault(false)
        val micInstalled = runCatching { JarvisModels.installed(JarvisSettings.micItem) }.getOrDefault(false)
        return listOf(
            BrainRegion("raciocinio", "Raciocínio", "IA local", 0.20f, 0.40f,
                when (brain) {
                    BrainState.READY, BrainState.IDLE -> SkillState.ACTIVE; BrainState.LOADING -> SkillState.WORKING
                    BrainState.ERROR -> SkillState.ERROR; BrainState.NOT_INSTALLED -> SkillState.MISSING; else -> SkillState.OFF
                },
                when (brain) {
                    BrainState.READY -> "pronta: " + (JarvisSettings.llmItem?.label ?: "")
                    BrainState.IDLE -> "baixada; carrega na primeira pergunta"
                    BrainState.LOADING -> "carregando…"
                    BrainState.ERROR -> Jarvis.brainError ?: "erro"
                    BrainState.NOT_INSTALLED -> "modelo não baixado (⚙ › Cérebro)"
                    BrainState.UNSUPPORTED -> "este aparelho não roda a IA local"
                    BrainState.OFF -> "desligada"
                },
                "É o modelo de linguagem que roda no próprio aparelho. Escreve respostas com as suas palavras e escolhe que dados consultar. Sem ele, o Jarvis responde direto com os dados do app.",
                listOfNotNull("Modelo" to (JarvisSettings.llmItem?.label ?: "desligada"),
                    if (Platform.isMobile) null else "Biblioteca" to JarvisNative.libraryLabel())),
            BrainRegion("visao", "Visão", "mercado ao vivo", 0.80f, 0.42f,
                if (live > 0) SkillState.ACTIVE else SkillState.ERROR,
                "$live de 3 livros ao vivo",
                "Enxerga o mercado em tempo real: preço, livro de ofertas, paredes, fluxo, liquidações, funding e open interest das corretoras.",
                listOf("Livros ao vivo" to "$live / 3", "Mensagens por segundo" to String.format(BR, "%,.0f", mps))),
            BrainRegion("memoria", "Memória", "o que já sabe", 0.52f, 0.52f,
                if (JarvisMemory.size > 0) SkillState.ACTIVE else SkillState.OFF,
                "${JarvisMemory.size} conhecimentos (${JarvisMemory.confirmed} confirmados)",
                "Guarda manchetes, resumos e conceitos com fonte e data. Algo só conta como confirmado com 2 fontes. É daqui que ele tira o que aprendeu.",
                listOf("Conhecimentos" to "${JarvisMemory.size}", "Confirmados" to "${JarvisMemory.confirmed}", "Conceitos aprendidos" to "$concepts")),
            BrainRegion("curiosidade", "Curiosidade", "aprende sem parar", 0.47f, 0.14f,
                when {
                    learner.phase == JarvisLearner.Phase.RESEARCH || learner.phase == JarvisLearner.Phase.CURIOSITY || JarvisCuriosity.running -> SkillState.WORKING
                    learner.on -> SkillState.ACTIVE
                    else -> SkillState.OFF
                },
                if (learner.on) learner.phaseText() else "desligada (toque em Ligar)",
                "Ligada, faz uma rodada a cada 20 minutos: pesquisa manchetes e aprende os termos novos de mercado que aparecem nelas (resumo da Wikipedia, com link). Só para quando você desligar.",
                listOf("Rodadas" to "${learner.cycles}", "Itens novos" to "${learner.learnedTotal}",
                    "Aprendeu por último" to (JarvisCuriosity.recent.take(3).joinToString(", ").ifBlank { "—" }))),
            BrainRegion("pesquisa", "Pesquisa", "notícias do dia", 0.70f, 0.20f,
                when { JarvisResearch.running -> SkillState.WORKING; JarvisResearch.enabled -> SkillState.ACTIVE; else -> SkillState.OFF },
                if (JarvisResearch.lastMs == 0L) "ainda não pesquisou" else "última: " + java.text.SimpleDateFormat("dd/MM HH:mm", BR).format(java.util.Date(JarvisResearch.lastMs)),
                "Busca manchetes recentes (Google News) sobre o BTC, a sua lista de ações e as perguntas que ele não soube responder.",
                listOf("Pesquisa diária" to if (JarvisResearch.enabled) "ligada" else "desligada", "Itens na última" to "+${JarvisResearch.lastAdded}")),
            BrainRegion("conceitos", "Linguagem", "conceitos e glossário", 0.28f, 0.18f, SkillState.ACTIVE,
                "${JarvisChat.GLOSSARY.size} conceitos de mercado + $concepts aprendidos",
                "Explica termos como funding, open interest, liquidação, CVD e Time Machine em linguagem simples, junto com o valor de agora. Funciona mesmo sem a IA.",
                listOf("Glossário" to "${JarvisChat.GLOSSARY.size}", "Aprendidos" to "$concepts")),
            BrainRegion("trade", "Instinto de trade", "plano e Time Machine", 0.78f, 0.80f, SkillState.ACTIVE,
                if (sb == null || sb.triggered == 0) "sem planos conferidos ainda" else "${sb.triggered} planos conferidos",
                "Monta o plano completo: entrada no rompimento da abertura, stop, alvo, 10 mil simulações, tamanho e checagem de risco. Bloqueia quando o stop já foi atingido. Nunca envia ordens.",
                listOfNotNull("Perda máxima por trade" to "US$ " + num(JarvisSettings.rules.maxLossPerTrade),
                    sb?.takeIf { it.triggered > 0 }?.let { "Alvo · stop · nenhum" to "${it.target} · ${it.stop} · ${it.close}" })),
            BrainRegion("fala", "Fala", "voz", 0.24f, 0.70f,
                when {
                    !JarvisSettings.voiceOn -> SkillState.OFF; Jarvis.speaking -> SkillState.WORKING
                    JarvisSettings.studio && JarvisVoiceStudio.status == JarvisVoiceStudio.Status.OFFLINE -> if (voiceInstalled) SkillState.ACTIVE else SkillState.ERROR
                    JarvisSettings.studio -> SkillState.ACTIVE
                    !voiceInstalled -> SkillState.MISSING; else -> SkillState.ACTIVE
                },
                when {
                    !JarvisSettings.voiceOn -> "voz desligada"
                    JarvisSettings.studio && JarvisVoiceStudio.status == JarvisVoiceStudio.Status.OFFLINE -> "VoiceStudio fechado" + if (voiceInstalled) "; usando a Kokoro" else ""
                    JarvisSettings.studio -> "voz Jarvis (VoiceStudio)"
                    !voiceInstalled -> "voz não baixada (⚙ › Voz)"; else -> JarvisSettings.voice.label
                },
                "Lê as respostas em voz alta. Pode usar a voz Kokoro, que roda no aparelho, ou a voz no estilo Jarvis do VoiceStudio aberto no PC. Fala só o começo da resposta; os detalhes ficam na tela.",
                listOf("Motor" to if (JarvisSettings.studio) "VoiceStudio · " + JarvisVoiceStudio.status.label else "Kokoro", "Voz" to if (JarvisSettings.studio) "Jarvis (mordomo britânico)" else JarvisSettings.voice.label)),
            BrainRegion("audicao", "Audição", "microfone", 0.42f, 0.80f,
                when { !micInstalled -> SkillState.MISSING; Jarvis.listening -> SkillState.WORKING; Jarvis.micReady -> SkillState.ACTIVE; else -> SkillState.OFF },
                when { !micInstalled -> "microfone não baixado (⚙ › Microfone)"; Jarvis.listening -> "ouvindo…"; else -> "pronto" },
                "Transcreve o que você fala, no próprio aparelho. Ignora silêncio e ruído para não enviar frases que ninguém disse.",
                listOf("Modelo" to JarvisSettings.micItem.label)),
            BrainRegion("habilidades", "Habilidades", "receitas aprovadas", 0.62f, 0.68f,
                if (skillsOn > 0) SkillState.ACTIVE else SkillState.OFF,
                "$skillsOn ligadas de ${JarvisSkills.list.size}",
                "Receitas de pesquisa que surgem do seu uso (por exemplo, sobre opções ou mineração). Só funcionam depois que você aprova.",
                listOf("Ligadas" to "$skillsOn", "Total" to "${JarvisSkills.list.size}")),
            BrainRegion("prova", "Autoavaliação", "prova de 40 perguntas", 0.10f, 0.62f,
                if (JarvisEval.running) SkillState.WORKING else if (eval != null || JarvisEval.history.isNotEmpty()) SkillState.ACTIVE else SkillState.OFF,
                eval?.let { "nota ${it.score}" } ?: JarvisEval.history.lastOrNull()?.let { "nota $it" } ?: "ainda não fez a prova",
                "Uma prova fixa que mede se ele entende o pedido, acha os dados e não inventa números. Serve para conferir cada melhoria.",
                listOfNotNull(eval?.let { "Roteamento certo" to "${it.routing} / ${it.total}" }, eval?.let { "Perguntas com dados" to "${it.answered} / ${it.total}" })),
            BrainRegion("nuvem", "Nuvem", "cérebro na nuvem", 0.93f, 0.10f,
                when (CloudBrain.state) {
                    CloudBrain.State.ON -> SkillState.ACTIVE; CloudBrain.State.CONNECTING -> SkillState.WORKING
                    CloudBrain.State.ERROR -> SkillState.ERROR; CloudBrain.State.OFF -> if (CloudBrain.configured) SkillState.ACTIVE else SkillState.OFF
                },
                when (CloudBrain.state) {
                    CloudBrain.State.ON -> "conectado"; CloudBrain.State.CONNECTING -> "sincronizando…"
                    CloudBrain.State.ERROR -> CloudBrain.error ?: "erro"; CloudBrain.State.OFF -> if (CloudBrain.configured) "pronto" else "não configurado"
                },
                "Guarda uma cópia da memória, do placar e das avaliações deste aparelho e traz habilidades do catálogo.",
                emptyList()),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JarvisBrainMap(modifier: Modifier, onOpen: (JarvisView) -> Unit) {
    val ctx = LocalJarvisCtx.current
    val scope = rememberCoroutineScope()
    // Atualiza o mapa a cada 2 s (estados que não são de Compose: livros, contagens)
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val regions = JarvisBrainModel.regions(ctx)
    var selected by remember { mutableStateOf("curiosidade") }
    val sel = regions.firstOrNull { it.id == selected } ?: regions.first()

    Column(modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
        val active = regions.count { it.state == SkillState.ACTIVE || it.state == SkillState.WORKING }
        T("MAPA DO CÉREBRO", Q.Accent, 12, bold = true)
        T(L("Cada região é uma habilidade do Jarvis. Toque numa região para ver o que ela faz.") + " " +
            "$active " + L("de") + " ${regions.size} " + L("ativas agora") + ".", Q.Muted, 11, wrap = true, tr = false)
        Spacer(Modifier.height(6.dp))
        BrainCanvas(regions, selected) { selected = it }
        Legend()
        Spacer(Modifier.height(8.dp))
        // Detalhe da região escolhida
        Column(Modifier.fillMaxWidth().border(1.dp, stateColor(sel.state)).background(Q.Panel2).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(stateColor(sel.state)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    T(L(sel.name) + " · " + L(sel.organ), Q.Text, 14, bold = true, tr = false)
                    T(L(sel.state.label).uppercase(BR) + " — " + L(sel.status), stateColor(sel.state), 11, wrap = true, tr = false)
                }
            }
            T(sel.explain, Q.Text, 12, wrap = true, modifier = Modifier.padding(top = 6.dp))
            sel.numbers.forEach { (k, v) -> KV(k, v) }
            Spacer(Modifier.height(6.dp))
            when (sel.id) {
                "curiosidade" -> LearnSwitch()
                "raciocinio" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sel.state == SkillState.ERROR) MiniButton("Tentar de novo", on = true) { Jarvis.retryCrashed() }
                    MiniButton("Escolher o modelo") { onOpen(JarvisView.SETTINGS) }
                }
                "pesquisa" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniButton(if (JarvisResearch.running) "Pesquisando…" else "Pesquisar agora", on = true) {
                        if (!JarvisResearch.running) scope.launch { runCatching { JarvisResearch.run(force = true) } }
                    }
                    MiniButton(if (JarvisResearch.enabled) "Desligar a diária" else "Ligar a diária") { JarvisResearch.useEnabled(!JarvisResearch.enabled) }
                }
                "fala" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniButton(if (JarvisSettings.voiceOn) "Desligar a voz" else "Ligar a voz", on = !JarvisSettings.voiceOn) {
                        JarvisSettings.useVoiceOn(!JarvisSettings.voiceOn); if (!JarvisSettings.voiceOn) Jarvis.stopVoice()
                    }
                    if (sel.state == SkillState.MISSING) MiniButton("Baixar em ⚙") { onOpen(JarvisView.SETTINGS) }
                }
                "audicao", "habilidades", "nuvem" -> MiniButton("Abrir em ⚙") { onOpen(JarvisView.SETTINGS) }
                "prova" -> if (ctx != null) MiniButton(if (JarvisEval.running) "Rodando a prova…" else "Rodar a prova", on = true) {
                    if (!JarvisEval.running) scope.launch { runCatching { JarvisEval.run(ctx) } }
                }
                "trade", "visao", "memoria", "conceitos" -> MiniButton("Perguntar ao Jarvis") { onOpen(JarvisView.CHAT) }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Lista de todas as regiões (fácil de ler no celular e para quem prefere texto)
        T("TODAS AS HABILIDADES", Q.Accent, 11, bold = true)
        regions.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable { selected = r.id }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(r.state)))
                Spacer(Modifier.width(8.dp))
                T(L(r.name), if (r.id == selected) Q.Accent else Q.Text, 12, bold = true, tr = false, modifier = Modifier.width(140.dp))
                T(L(r.status), Q.Muted, 11, tr = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun stateColor(s: SkillState): Color = when (s) {
    SkillState.ACTIVE -> Q.Buy; SkillState.WORKING -> Q.Warn; SkillState.OFF -> Q.Muted; SkillState.ERROR -> Q.Sell; SkillState.MISSING -> Q.Muted
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(SkillState.ACTIVE, SkillState.WORKING, SkillState.OFF, SkillState.ERROR, SkillState.MISSING).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (s == SkillState.MISSING) Q.Panel else stateColor(s)).border(1.dp, stateColor(s), CircleShape))
                Spacer(Modifier.width(4.dp))
                T(L(s.label), Q.Muted, 10, tr = false)
            }
        }
    }
}

/** O desenho: silhueta de cérebro de lado (frente à esquerda), regiões ligadas à memória no centro. */
@Composable
private fun BrainCanvas(regions: List<BrainRegion>, selected: String, onSelect: (String) -> Unit) {
    val tm = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition().animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse))
    val colors = regions.associate { it.id to stateColor(it.state) }
    val line = Q.Line; val muted = Q.Muted; val text = Q.Text; val acc = Q.Accent; val panel = Q.Panel2; val fold = Q.Muted.copy(alpha = 0.25f)
    var boxW by remember { mutableStateOf(1f) }; var boxH by remember { mutableStateOf(1f) }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1.45f).heightIn(max = 420.dp)
            .pointerInput(regions.map { it.id }) {
                detectTapGestures { p ->
                    val near = regions.minByOrNull { r -> val dx = r.x * boxW - p.x; val dy = r.y * boxH - p.y; dx * dx + dy * dy }
                    if (near != null) onSelect(near.id)
                }
            }
    ) {
        boxW = size.width; boxH = size.height
        val w = size.width; val h = size.height
        fun P(x: Float, y: Float) = Offset(x * w, y * h)
        // Silhueta do cérebro (cérebro + cerebelo + tronco)
        val brain = Path().apply {
            moveTo(w * 0.08f, h * 0.55f)
            cubicTo(w * 0.02f, h * 0.30f, w * 0.18f, h * 0.06f, w * 0.40f, h * 0.06f)
            cubicTo(w * 0.55f, h * 0.00f, w * 0.80f, h * 0.06f, w * 0.88f, h * 0.30f)
            cubicTo(w * 0.96f, h * 0.48f, w * 0.92f, h * 0.62f, w * 0.84f, h * 0.66f)
            cubicTo(w * 0.70f, h * 0.70f, w * 0.60f, h * 0.70f, w * 0.52f, h * 0.88f)
            cubicTo(w * 0.40f, h * 0.94f, w * 0.20f, h * 0.88f, w * 0.12f, h * 0.74f)
            close()
        }
        drawPath(brain, panel)
        drawPath(brain, line, style = Stroke(2.dp.toPx()))
        val cereb = Path().apply {
            moveTo(w * 0.62f, h * 0.70f)
            cubicTo(w * 0.70f, h * 0.66f, w * 0.90f, h * 0.68f, w * 0.90f, h * 0.82f)
            cubicTo(w * 0.88f, h * 0.94f, w * 0.68f, h * 0.96f, w * 0.62f, h * 0.86f)
            close()
        }
        drawPath(cereb, panel); drawPath(cereb, line, style = Stroke(2.dp.toPx()))
        drawLine(line, P(0.56f, 0.86f), P(0.60f, 0.99f), strokeWidth = 6.dp.toPx())
        // Dobras (só decoração)
        listOf(
            listOf(0.18f to 0.30f, 0.30f to 0.24f, 0.38f to 0.34f), listOf(0.42f to 0.10f, 0.48f to 0.30f, 0.44f to 0.44f),
            listOf(0.60f to 0.14f, 0.66f to 0.34f, 0.74f to 0.38f), listOf(0.16f to 0.58f, 0.30f to 0.52f, 0.40f to 0.62f),
            listOf(0.56f to 0.40f, 0.70f to 0.52f, 0.84f to 0.50f), listOf(0.66f to 0.78f, 0.76f to 0.74f, 0.86f to 0.80f),
        ).forEach { pts ->
            val f = Path(); f.moveTo(pts[0].first * w, pts[0].second * h)
            f.quadraticBezierTo(pts[1].first * w, pts[1].second * h, pts[2].first * w, pts[2].second * h)
            drawPath(f, fold, style = Stroke(1.5.dp.toPx()))
        }
        // Ligações: tudo passa pela memória
        val hub = regions.firstOrNull { it.id == "memoria" }
        val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
        if (hub != null) regions.filter { it.id != hub.id }.forEach { r ->
            val c = colors[r.id] ?: muted
            val on = r.state == SkillState.ACTIVE || r.state == SkillState.WORKING
            drawLine(if (on) c.copy(alpha = 0.45f) else line, P(hub.x, hub.y), P(r.x, r.y), strokeWidth = (if (on) 2 else 1).dp.toPx(),
                pathEffect = if (on) null else dash)
        }
        // Regiões
        val rad = (minOf(w, h) * 0.055f).coerceIn(10.dp.toPx(), 22.dp.toPx())
        regions.forEach { r ->
            val c = colors[r.id] ?: muted
            val center = P(r.x, r.y)
            val a = if (r.state == SkillState.WORKING) pulse else 1f
            if (r.state == SkillState.WORKING) drawCircle(c.copy(alpha = 0.25f * a), rad * (1.4f + 0.4f * a), center)
            if (r.state == SkillState.MISSING) drawCircle(c, rad, center, style = Stroke(2.dp.toPx(), pathEffect = dash))
            else { drawCircle(c.copy(alpha = 0.30f * a + 0.15f), rad, center); drawCircle(c, rad, center, style = Stroke(2.dp.toPx())) }
            if (r.id == selected) drawCircle(acc, rad + 5.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            // Nome embaixo (centralizado e sem sair da área)
            val style = TextStyle(color = if (r.id == selected) acc else text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            val m = tm.measure(L(r.name), style)
            val x = (center.x - m.size.width / 2f).coerceIn(0f, w - m.size.width)
            val y = (center.y + rad + 3.dp.toPx()).coerceAtMost(h - m.size.height)
            drawText(m, topLeft = Offset(x, y))
        }
    }
}
