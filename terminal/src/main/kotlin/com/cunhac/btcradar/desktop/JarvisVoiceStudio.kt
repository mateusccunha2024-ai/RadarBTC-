package com.cunhac.btcradar.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Voz do Jarvis pelo VoiceStudio (https://github.com/debpalash/voicestudio), rodando à parte no próprio PC.
 *
 * O Radar não embute o VoiceStudio (ele é AGPL-3.0 e o modelo OmniVoice é só para uso não comercial):
 * o usuário instala e abre o VoiceStudio, e o Jarvis fala pela API local compatível com a OpenAI,
 * `POST /v1/audio/speech` em http://127.0.0.1:3900, pedindo áudio PCM cru (24 kHz, 16 bits, mono).
 *
 * A voz "Jarvis" é desenhada com as etiquetas do OmniVoice (homem de meia-idade, voz grave, sotaque britânico,
 * o mordomo calmo dos filmes) e uma semente fixa, para soar igual em toda frase. Não é a voz do ator:
 * nada é clonado. Se o VoiceStudio estiver fechado ou falhar, o Jarvis volta para a voz Kokoro do aparelho.
 */
object JarvisVoiceStudio {
    const val DEFAULT_URL = "http://127.0.0.1:3900"
    /** Etiquetas válidas do OmniVoice (omnivoice/utils/voice_design.py). */
    const val JARVIS_STYLE = "male, middle-aged, low pitch, british accent"
    const val SEED = 3000
    const val RATE = 24_000

    enum class Status(val label: String) { UNKNOWN("não verificado"), CHECKING("procurando…"), ONLINE("conectado"), OFFLINE("não encontrado") }

    var status by mutableStateOf(Status.UNKNOWN); private set
    var lastError by mutableStateOf<String?>(null); private set
    /** Tempo da última frase gerada (ms); mostra se o PC dá conta. */
    var lastMs by mutableStateOf(0L); private set

    /** Só http/https; sem usuário/senha no endereço. */
    fun normalize(url: String): String? {
        val u = url.trim().trimEnd('/').ifBlank { DEFAULT_URL }
        val uri = runCatching { URI(u) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return u
    }

    fun isLoopback(url: String): Boolean = runCatching { URI(url).host }.getOrNull()?.lowercase() in setOf("127.0.0.1", "localhost", "::1", "[::1]")

    /** Corpo do pedido (puro, testado). Idioma em ISO 639-1 ("pt", "en"…). */
    fun body(text: String, lang: String, speed: Float): String = buildJsonObject {
        put("model", "omnivoice")
        put("input", text.take(4000))
        put("voice", "default")
        put("instruct", JARVIS_STYLE)
        put("language", lang.substringBefore('-').lowercase().ifBlank { "pt" })
        put("response_format", "pcm")
        put("speed", speed.coerceIn(0.25f, 4f).toDouble())
        put("seed", SEED)
    }.toString()

    /** PCM 16 bits little-endian → amostras de -1 a 1 (puro, testado). */
    fun pcmToFloat(bytes: ByteArray): FloatArray {
        val n = bytes.size / 2
        return FloatArray(n) { i ->
            val v = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
            v.toShort() / 32768f
        }
    }

    /** Procura o VoiceStudio no endereço configurado (rápido, 2 s). */
    fun probe(url: String = JarvisSettings.vsUrl): Boolean {
        val base = normalize(url) ?: run { status = Status.OFFLINE; lastError = "endereço inválido"; return false }
        status = Status.CHECKING
        val ok = listOf("/.well-known/voicestudio-speech", "/v1/audio/voices").any { path ->
            runCatching {
                val c = URI(base + path).toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 2000; c.readTimeout = 4000
                try { c.responseCode in 200..299 } finally { c.disconnect() }
            }.getOrDefault(false)
        }
        status = if (ok) Status.ONLINE else Status.OFFLINE
        lastError = if (ok) null else "o VoiceStudio não respondeu em $base"
        return ok
    }

    /** Gera uma frase. Null se falhar (motivo em [lastError]). */
    fun synth(text: String, lang: String, speed: Float, url: String = JarvisSettings.vsUrl): FloatArray? {
        val base = normalize(url) ?: run { lastError = "endereço inválido"; return null }
        val t0 = System.currentTimeMillis()
        return runCatching {
            val c = URI("$base/v1/audio/speech").toURL().openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 3000; c.readTimeout = 180_000 // CPU sem placa de vídeo pode levar bastante
            c.setRequestProperty("Content-Type", "application/json")
            try {
                c.outputStream.use { it.write(body(text, lang, speed).toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code !in 200..299) {
                    val msg = runCatching { c.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()
                    error("HTTP $code " + msg.take(160))
                }
                val out = ByteArrayOutputStream()
                c.inputStream.use { it.copyTo(out) }
                pcmToFloat(out.toByteArray()).also { if (it.isEmpty()) error("áudio vazio") }
            } finally { c.disconnect() }
        }.onSuccess { status = Status.ONLINE; lastError = null; lastMs = System.currentTimeMillis() - t0 }
            .onFailure { lastError = it.message ?: it.javaClass.simpleName; FileLog.info("jarvis voicestudio: $lastError") }
            .getOrNull()
    }

    /**
     * Fala frase por frase: gera a próxima enquanto a atual toca. Devolve false se nem a primeira frase saiu
     * (aí quem chamou usa a voz Kokoro).
     */
    fun speak(text: String, lang: String, speed: Float, url: String = JarvisSettings.vsUrl, play: (FloatArray, Int) -> Boolean): Boolean {
        val parts = JarvisText.sentences(text).filter { it.isNotBlank() }
        if (parts.isEmpty()) return true
        var current = synth(parts[0], lang, speed, url) ?: return false
        for (i in parts.indices) {
            val next = if (i + 1 < parts.size) java.util.concurrent.CompletableFuture.supplyAsync { synth(parts[i + 1], lang, speed, url) } else null
            if (!play(current, RATE)) { next?.cancel(true); return true }
            current = next?.get() ?: return true
        }
        return true
    }
}
