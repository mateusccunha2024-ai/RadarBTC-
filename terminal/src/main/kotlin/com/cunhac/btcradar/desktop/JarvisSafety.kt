package com.cunhac.btcradar.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Proteção contra o app fechar com o Jarvis aberto.
 *
 * 1. Guarda o motivo de qualquer erro que derrube o app (arquivo "ultimo-fechamento.txt"), para mostrar na próxima vez.
 * 2. Marca enquanto o painel está aberto. Se o app morrer com o painel aberto (erro, falta de memória ou travamento nativo,
 *    que não passam por nenhum tratamento Java), na próxima abertura o Jarvis entra em modo seguro: não pesquisa,
 *    não sincroniza e não carrega nada sozinho, e avisa o que aconteceu.
 */
object JarvisCrash {
    private val dir: File get() = JarvisNative.modelsDir
    private val crashFile get() = File(dir, "ultimo-fechamento.txt")
    private val openFile get() = File(dir, "painel-aberto")

    /** Resumo do último fechamento (primeira linha do erro), ou null. */
    var last by mutableStateOf<String?>(null); private set
    /** O app fechou com o painel aberto na vez anterior: esta abertura é em modo seguro. */
    var safeMode by mutableStateOf(false); private set
    private var checked = false
    @Volatile private var installed = false

    /** Chamado no início do app (Android e Windows). Não troca o comportamento padrão: só anota e repassa. */
    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { record(t.name, e) }
            if (previous != null) previous.uncaughtException(t, e) else { System.err.println(e); kotlin.system.exitProcess(2) }
        }
    }

    fun record(thread: String, e: Throwable) {
        val stack = e.stackTraceToString().lineSequence().take(60).joinToString("\n")
        val text = "${System.currentTimeMillis()}\n$thread\n${if (openFile.exists()) "painel=aberto" else "painel=fechado"}\n$stack"
        crashFile.writeText(text)
        runCatching { FileLog.info("app fechou por erro: ${stack.lineSequence().firstOrNull()}") }
    }

    /** Lê o que aconteceu da última vez (uma vez por execução). */
    fun check() {
        if (checked) return
        checked = true
        runCatching {
            if (crashFile.exists()) {
                val lines = crashFile.readLines()
                last = summary(lines)
                FileLog.info("jarvis: último fechamento: ${lines.drop(3).take(8).joinToString(" | ")}")
            }
            if (openFile.exists()) { safeMode = true; openFile.delete() }
        }
    }

    /** Primeira linha útil do erro: tipo e mensagem, sem o pacote. */
    fun summary(lines: List<String>): String? {
        val first = lines.drop(3).firstOrNull { it.isNotBlank() } ?: return null
        val type = first.substringBefore(':').substringAfterLast('.')
        val msg = first.substringAfter(':', "").trim()
        return (if (msg.isBlank()) type else "$type: $msg").take(160)
    }

    fun panelOpened() { check(); runCatching { openFile.writeText(System.currentTimeMillis().toString()) } }
    fun panelClosed() { runCatching { openFile.delete() } }

    fun dismiss() { runCatching { crashFile.delete() }; last = null; safeMode = false }
}
