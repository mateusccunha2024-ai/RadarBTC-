package com.cunhac.btcradar.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Aprender sem parar" (atualização 17): um toque liga e o Jarvis fica aprendendo até você desligar.
 * Continua ligado depois de fechar e abrir o app. Cada rodada: pesquisa manchetes (Google News) sobre o BTC, a sua lista,
 * as perguntas que ele não soube e as habilidades ligadas; depois procura termos novos nas manchetes e aprende o que
 * são (Wikipedia, só assuntos de mercado). Entre uma rodada e outra espera [intervalMin] minutos para não sobrecarregar
 * o aparelho nem as fontes. No celular, só roda no Wi-Fi.
 */
object JarvisLearner {
    enum class Phase { OFF, RESEARCH, CURIOSITY, WAITING, WAITING_WIFI }

    var on by mutableStateOf(SettingsStore.b("jv.learn.on", false)); private set
    var phase by mutableStateOf(Phase.OFF); private set
    var cycles by mutableIntStateOf(SettingsStore.i("jv.learn.cycles", 0)); private set
    /** Itens novos (manchetes + conceitos) desde que foi ligado. */
    var learnedTotal by mutableIntStateOf(SettingsStore.i("jv.learn.total", 0)); private set
    var lastResult by mutableStateOf(SettingsStore.s("jv.learn.lastres", "")); private set
    var nextAtMs by mutableLongStateOf(0L); private set
    var startedMs by mutableLongStateOf(SettingsStore.s("jv.learn.since", "0").toLongOrNull() ?: 0L); private set
    val intervalMin = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Liga ou desliga (fica guardado: ao abrir o app de novo, continua como você deixou). */
    fun turn(v: Boolean) {
        on = v
        SettingsStore.put("jv.learn.on", v)
        if (v) {
            if (startedMs == 0L) { startedMs = System.currentTimeMillis(); SettingsStore.put("jv.learn.since", startedMs.toString()) }
            start()
        } else {
            job?.cancel(); job = null; phase = Phase.OFF; nextAtMs = 0L
            startedMs = 0L; SettingsStore.put("jv.learn.since", "0")
            runCatching { FileLog.info("jarvis: aprender sem parar desligado depois de $cycles rodadas") }
        }
    }

    /** Na abertura do app: se estava ligado, volta a aprender (menos em modo seguro). */
    fun resumeIfOn() {
        if (!on) return
        runCatching { JarvisCrash.check() }
        if (!JarvisCrash.safeMode) start()
    }

    private fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            runCatching { FileLog.info("jarvis: aprender sem parar ligado") }
            delay(5_000) // deixa o app terminar de abrir
            while (isActive && on) {
                if (!JarvisNative.unmetered()) { phase = Phase.WAITING_WIFI; nextAtMs = 0L; delay(60_000); continue }
                var added = 0
                try {
                    phase = Phase.RESEARCH
                    // Resumos pela IA só de vez em quando (a cada 6 rodadas, ~2 h): a IA pesa no processador.
                    while (JarvisResearch.running || JarvisCuriosity.running) delay(2_000)
                    added += runCatching { JarvisResearch.run(force = true, withSummaries = cycles % 6 == 0) }.getOrDefault(0)
                    phase = Phase.CURIOSITY
                    val learned = runCatching { JarvisCuriosity.learn(max = 4) }.getOrDefault(emptyList())
                    added += learned.size
                    cycles++; learnedTotal += added
                    lastResult = (if (learned.isEmpty()) "" else "aprendeu " + learned.joinToString(", ") + "; ") + "+$added itens"
                    SettingsStore.put("jv.learn.cycles", cycles); SettingsStore.put("jv.learn.total", learnedTotal); SettingsStore.put("jv.learn.lastres", lastResult)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                    lastResult = "erro na rodada: ${e.message ?: e.javaClass.simpleName}"
                }
                phase = Phase.WAITING
                nextAtMs = System.currentTimeMillis() + intervalMin * 60_000L
                while (isActive && on && System.currentTimeMillis() < nextAtMs) delay(5_000)
            }
            phase = Phase.OFF
        }
    }

    fun phaseText(): String = when (phase) {
        Phase.OFF -> if (on) "ligando…" else "desligado"
        Phase.RESEARCH -> "pesquisando manchetes…"
        Phase.CURIOSITY -> "aprendendo termos novos…"
        Phase.WAITING -> "próxima rodada em " + ((nextAtMs - System.currentTimeMillis()).coerceAtLeast(0) / 60_000 + 1) + " min"
        Phase.WAITING_WIFI -> "esperando Wi-Fi"
    }
}
