package com.cunhac.btcradar.desktop

import androidx.compose.runtime.mutableStateMapOf
import com.cunhac.btcradar.data.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Utilidades de texto do assistente (fala e limpeza). */
object JarvisText {
    /** Divide em frases curtas para a voz começar logo. */
    fun sentences(text: String): List<String> {
        val clean = speakable(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in clean) {
            sb.append(ch)
            if ((ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == '\n' || ch == '。') && sb.length > 24) { out += sb.toString().trim(); sb.clear() }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.any(Char::isLetterOrDigit) }
    }

    /** Remove marcações que não fazem sentido em voz alta. */
    fun speakable(text: String) = text.replace(Regex("[*_#`>|]"), " ").replace("→", ",").replace("·", ",").replace("•", "")
        .replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.!?;:])"), "$1").trim()

    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}

/**
 * Modelos abertos baixados uma vez para o aparelho (nada roda na nuvem):
 * IA = Qwen2.5 Instruct (GGUF, Apache 2.0); voz = Kokoro-82M multi-idioma (Apache 2.0, via sherpa-onnx);
 * microfone = Whisper (MIT, via sherpa-onnx).
 */
object JarvisModels {
    enum class Kind { LLM, VOICE, MIC }

    data class Item(val id: String, val kind: Kind, val label: String, val sizeMb: Int, val files: List<Pair<String, String>> = emptyList(),
                    val tarBz2: String? = null, val dir: String, val note: String)

    private const val HF = "https://huggingface.co"
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    val LLM_LIGHT = Item("llm-0.5b", Kind.LLM, "IA leve · Qwen2.5 0.5B", 468,
        listOf("$HF/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf" to "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
        dir = "llm", note = "Rápida, boa para celular. Respostas mais simples.")
    val LLM_STD = Item("llm-1.5b", Kind.LLM, "IA padrão · Qwen2.5 1.5B", 1065,
        listOf("$HF/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf" to "qwen2.5-1.5b-instruct-q4_k_m.gguf"),
        dir = "llm", note = "Melhor qualidade; recomendada para PC e celulares com 6 GB de RAM ou mais.")
    /** Só no PC (precisa de ~6 GB de RAM livres): raciocínio bem melhor. Arquivo único do GGUF oficial reempacotado (bartowski). */
    val LLM_PRO = Item("llm-7b", Kind.LLM, "IA forte · Qwen2.5 7B (PC)", 4466,
        listOf("$HF/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf" to "Qwen2.5-7B-Instruct-Q4_K_M.gguf"),
        dir = "llm", note = "Para PC com 16 GB de RAM ou mais. Mais lenta, mas entende e escolhe ferramentas muito melhor.")
    val VOICE = Item("voice-kokoro", Kind.VOICE, "Voz neural · Kokoro (vários idiomas)", 126,
        tarBz2 = "$GH/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2", dir = "kokoro-int8-multi-lang-v1_0",
        note = "Voz própria do assistente, gerada no aparelho.")
    val MIC_TINY = Item("mic-tiny", Kind.MIC, "Microfone · Whisper tiny", 98, listOf(
        "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx" to "tiny-encoder.int8.onnx",
        "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx" to "tiny-decoder.int8.onnx",
        "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt" to "tiny-tokens.txt"),
        dir = "whisper-tiny", note = "Entende a sua fala no aparelho. Mais leve.")
    val MIC_BASE = Item("mic-base", Kind.MIC, "Microfone · Whisper base", 152, listOf(
        "$HF/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx" to "base-encoder.int8.onnx",
        "$HF/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx" to "base-decoder.int8.onnx",
        "$HF/csukuangfj/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt" to "base-tokens.txt"),
        dir = "whisper-base", note = "Entende melhor sotaques e ruído; um pouco mais pesado.")

    val ALL = listOf(LLM_LIGHT, LLM_STD, LLM_PRO, VOICE, MIC_TINY, MIC_BASE)

    /** Progresso de download por item (0..1); ausente = parado. */
    val progress = mutableStateMapOf<String, Float>()
    val errors = mutableStateMapOf<String, String>()

    fun dir(i: Item) = File(JarvisNative.modelsDir, i.dir)
    fun file(i: Item, name: String) = File(dir(i), name)
    fun installed(i: Item): Boolean = if (i.tarBz2 != null) File(dir(i), ".ok").exists()
        else i.files.all { (_, n) -> file(i, n).let { it.exists() && it.length() > 0 } }

    fun delete(i: Item) {
        if (i.tarBz2 != null) dir(i).deleteRecursively() else i.files.forEach { (_, n) -> file(i, n).delete() }
    }

    /** Baixa (retomando de onde parou) e, se for o caso, extrai. */
    suspend fun download(i: Item): Boolean = withContext(Dispatchers.IO) {
        errors.remove(i.id); progress[i.id] = 0f
        try {
            if (i.tarBz2 != null) {
                val tmp = File(JarvisNative.modelsDir, "${i.id}.tar.bz2.part")
                fetch(i.tarBz2, tmp, i.sizeMb * 1_048_576L) { progress[i.id] = (it * 0.9).toFloat() }
                extractTarBz2(tmp, JarvisNative.modelsDir)
                tmp.delete()
                File(dir(i), ".ok").writeText("ok")
            } else {
                val total = i.sizeMb * 1_048_576L
                var done = 0L
                for ((url, name) in i.files) {
                    val f = file(i, name)
                    if (f.exists() && f.length() > 0) { done += f.length(); continue }
                    f.parentFile.mkdirs()
                    val part = File(f.parentFile, "$name.part")
                    val base = done
                    fetch(url, part, total) { p -> progress[i.id] = ((base + p * total) / total).toFloat().coerceIn(0f, 1f) }
                    done += part.length()
                    if (!part.renameTo(f)) throw IOException("não foi possível salvar $name")
                }
            }
            true
        } catch (e: Exception) {
            errors[i.id] = e.message ?: e.javaClass.simpleName
            false
        } finally { progress.remove(i.id) }
    }

    private fun fetch(url: String, part: File, expected: Long, onProgress: (Double) -> Unit) {
        part.parentFile?.mkdirs()
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).header("User-Agent", "RadarBTC").apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        Net.client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} ao baixar o modelo")
            val append = r.code == 206
            val body = r.body ?: throw IOException("resposta vazia")
            val len = body.contentLength().let { if (it > 0) it + (if (append) have else 0) else expected }
            RandomAccessFile(part, "rw").use { out ->
                if (append) out.seek(have) else out.setLength(0)
                var got = if (append) have else 0L
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { inp ->
                    var last = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (got - last > 512 * 1024) { last = got; onProgress(got.toDouble() / len) }
                    }
                }
            }
        }
    }

    private fun extractTarBz2(src: File, into: File) {
        val root = into.canonicalPath
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(src.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(into, e.name)
                if (!out.canonicalPath.startsWith(root)) continue // nunca escreve fora da pasta dos modelos
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it) }
            }
        }
    }
}

/** Vozes do Kokoro (id do locutor no voices.bin da v1.0) por idioma. */
object JarvisVoices {
    data class Voice(val sid: Int, val name: String, val lang: String, val label: String, val app: String)

    val ALL = listOf(
        Voice(43, "pm_alex", "pt-br", "Alex · português (masculina)", "pt"),
        Voice(42, "pf_dora", "pt-br", "Dora · português (feminina)", "pt"),
        Voice(44, "pm_santa", "pt-br", "Santa · português (grave)", "pt"),
        Voice(26, "bm_george", "en-gb", "George · inglês britânico", "en"),
        Voice(27, "bm_lewis", "en-gb", "Lewis · inglês britânico", "en"),
        Voice(16, "am_michael", "en-us", "Michael · inglês americano", "en"),
        Voice(29, "em_alex", "es", "Alex · espanhol", "es"),
        Voice(30, "ff_siwis", "fr-fr", "Siwis · francês", "fr"),
        Voice(36, "im_nicola", "it", "Nicola · italiano", "it"),
        Voice(41, "jm_kumo", "ja", "Kumo · japonês", "ja"),
        Voice(50, "zm_yunxi", "cmn", "Yunxi · chinês", "zh"),
    )

    /** Voz padrão para o idioma do app (alemão, russo e coreano usam a voz inglesa: o Kokoro não tem esses idiomas). */
    fun default(appLang: String) = ALL.firstOrNull { it.app == appLang } ?: ALL.first { it.name == "bm_george" }
    fun byId(sid: Int) = ALL.firstOrNull { it.sid == sid }

    /** A voz única do Jarvis: masculina, calma, no idioma em que ele está falando. Alemão, russo e coreano: a Kokoro não tem. */
    fun jarvis(code: String): Voice? = when (code) {
        "pt" -> byId(43); "en" -> byId(26); "es" -> byId(29); "fr" -> byId(30); "it" -> byId(36); "ja" -> byId(41); "zh" -> byId(50)
        else -> null
    }
}
