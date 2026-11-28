package com.cunhac.btcradar.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Teste de ponta a ponta do Jarvis no aparelho (só na versão de teste, usado pelo emulador do CI):
 * baixa a IA leve, a voz e o microfone leve, faz perguntas, fala e carrega o microfone, e escreve cada passo no log
 * ("selftest: ..."). Se o app fechar em algum passo, o log mostra qual foi.
 */
object JarvisSelfTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(ctx: AppContext) = scope.launch {
        fun log(s: String) = runCatching { FileLog.info("selftest: $s") }
        try {
            log("inicio (memória livre ${JarvisNative.freeRamMb()} MB)")
            JarvisSettings.useLlm(JarvisModels.LLM_LIGHT.id); JarvisSettings.useMic(JarvisModels.MIC_TINY.id)
            for (i in listOf(JarvisModels.LLM_LIGHT, JarvisModels.VOICE, JarvisModels.MIC_TINY)) {
                val t0 = System.currentTimeMillis()
                val ok = JarvisModels.installed(i) || JarvisModels.download(i)
                log("download ${i.id}: ${if (ok) "ok" else "falhou " + JarvisModels.errors[i.id]} em ${(System.currentTimeMillis() - t0) / 1000} s")
            }
            Jarvis.prepare().join()
            log("prepare: cérebro=${Jarvis.brain} voz=${Jarvis.voiceReady} mic=${Jarvis.micReady} desligados=${Jarvis.crashedList}")
            delay(8_000) // dá tempo para os dados ao vivo chegarem
            for (q in listOf("Como está o BTC agora?", "Plano de trade BTC", "Onde estão as paredes?")) {
                val n = Jarvis.messages.size
                Jarvis.ask(ctx, q)
                var w = 0
                while ((Jarvis.busy || Jarvis.messages.size < n + 2) && w < 240) { delay(1_000); w++ }
                val r = Jarvis.messages.lastOrNull()
                log("pergunta '$q': ${w}s motor=${r?.engine} cérebro=${Jarvis.brain} erro=${Jarvis.brainError} resposta=${r?.text?.take(160)?.replace('\n', ' ')}")
            }
            val v = Jarvis.ensureVoice(); log("voz carregou: $v")
            if (v) log("voz falou: " + runCatching { JarvisNative.speak("Teste de voz do Jarvis.", JarvisSettings.voice.sid, 1f); "ok" }.getOrElse { it.toString() })
            log("microfone carregou: ${Jarvis.ensureMic()}")
            log("fim ok (memória livre ${JarvisNative.freeRamMb()} MB)")
        } catch (e: Throwable) {
            log("fim com erro: $e")
        }
    }
}
