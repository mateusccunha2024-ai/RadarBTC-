package com.cunhac.btcradar.desktop

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Windows: o "cérebro" e a voz do assistente rodando no próprio PC.
 * IA: llama.cpp (de.kherud:llama) com um modelo GGUF aberto. Voz: Kokoro (sherpa-onnx). Microfone: Whisper (sherpa-onnx).
 * Nenhum dado sai do computador; só o download dos modelos usa a internet.
 */
object JarvisNative {
    val modelsDir: File get() = File(AppDirs.root, "jarvis").apply { mkdirs() }
    const val llmSupported = true
    const val micSupported = true
    /** Threads para a IA: metade dos núcleos (o app continua fluido). */
    private val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)

    @Volatile private var llm: LlamaModel? = null
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var asr: OfflineRecognizer? = null
    private val llmLock = Any()

    val llmLoaded get() = llm != null
    val ttsLoaded get() = tts != null
    val asrLoaded get() = asr != null

    /** Carrega o modelo de linguagem. Devolve a mensagem de erro, ou null se deu certo. */
    fun loadLlm(path: String): String? = synchronized(llmLock) {
        runCatching {
            llm?.close(); llm = null
            llm = LlamaModel(ModelParameters().setModel(path).setCtxSize(4096).setThreads(threads).setThreadsBatch(threads).setGpuLayers(0))
            null
        }.getOrElse { (it.message ?: it.javaClass.simpleName).also { m -> FileLog.info("jarvis llm: $m") } }
    }

    fun unloadLlm() = synchronized(llmLock) { runCatching { llm?.close() }; llm = null }

    fun complete(prompt: String, maxTokens: Int, temperature: Float, stop: List<String>): String = synchronized(llmLock) {
        val m = llm ?: error("modelo não carregado")
        m.complete(InferenceParameters(prompt).setNPredict(maxTokens).setTemperature(temperature).setTopP(0.9f)
            .setRepeatPenalty(1.1f).setStopStrings(*stop.toTypedArray()))
    }

    fun loadTts(dir: File, model: String, lang: String, lexicon: String, dictDir: String?): String? = runCatching {
        tts?.release(); tts = null
        val k = OfflineTtsKokoroModelConfig.builder().setModel(File(dir, model).path).setVoices(File(dir, "voices.bin").path)
            .setTokens(File(dir, "tokens.txt").path).setDataDir(File(dir, "espeak-ng-data").path).setLexicon(lexicon).setLang(lang)
        if (dictDir != null) k.setDictDir(dictDir)
        val cfg = OfflineTtsConfig.builder().setModel(OfflineTtsModelConfig.builder().setKokoro(k.build()).setNumThreads(2).build()).build()
        tts = OfflineTts(cfg)
        null
    }.getOrElse { (it.message ?: it.javaClass.simpleName).also { m -> FileLog.info("jarvis tts: $m") } }

    @Volatile private var speaking: SourceDataLine? = null
    @Volatile private var stopFlag = false

    /** Fala o texto (bloqueia até terminar ou até [stopSpeaking]). Frase por frase, para começar logo. */
    fun speak(text: String, sid: Int, speed: Float) {
        val t = tts ?: return
        stopFlag = false
        for (part in JarvisText.sentences(text)) {
            if (stopFlag) break
            val a = runCatching { t.generate(part, sid, speed) }.getOrNull() ?: continue
            if (stopFlag) break
            play(a.samples, a.sampleRate)
        }
    }

    private fun play(samples: FloatArray, rate: Int) {
        val fmt = AudioFormat(rate.toFloat(), 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(fmt)
        speaking = line
        try {
            line.open(fmt); line.start()
            val buf = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                buf[2 * i] = (v and 0xFF).toByte(); buf[2 * i + 1] = (v shr 8 and 0xFF).toByte()
            }
            var off = 0
            while (off < buf.size && !stopFlag) { val n = minOf(8192, buf.size - off); line.write(buf, off, n); off += n }
            if (!stopFlag) line.drain()
        } finally { runCatching { line.stop(); line.close() }; speaking = null }
    }

    fun stopSpeaking() { stopFlag = true; runCatching { speaking?.stop(); speaking?.flush() } }

    fun loadAsr(encoder: File, decoder: File, tokens: File, language: String): String? = runCatching {
        asr?.release(); asr = null
        val w = OfflineWhisperModelConfig.builder().setEncoder(encoder.path).setDecoder(decoder.path).setLanguage(language).setTask("transcribe").build()
        val mc = OfflineModelConfig.builder().setWhisper(w).setTokens(tokens.path).setNumThreads(threads).setModelType("whisper").build()
        asr = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(mc)
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build()).setDecodingMethod("greedy_search").build())
        null
    }.getOrElse { (it.message ?: it.javaClass.simpleName).also { m -> FileLog.info("jarvis asr: $m") } }

    @Volatile private var mic: TargetDataLine? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var recThread: Thread? = null

    /** Começa a gravar (16 kHz, mono). Devolve erro ou null. */
    fun startRecording(): String? = runCatching {
        val fmt = AudioFormat(16000f, 16, 1, true, false)
        val line = AudioSystem.getTargetDataLine(fmt)
        line.open(fmt); line.start()
        synchronized(pcm) { pcm.reset() }
        mic = line
        recThread = Thread {
            val b = ByteArray(3200)
            while (mic === line) { val n = line.read(b, 0, b.size); if (n > 0) synchronized(pcm) { pcm.write(b, 0, n) } }
        }.apply { isDaemon = true; start() }
        null
    }.getOrElse { it.message ?: "microfone indisponível" }

    /** Para de gravar e transcreve com o Whisper local. */
    fun stopRecordingAndTranscribe(): String {
        val line = mic ?: return ""
        mic = null
        runCatching { line.stop(); line.close() }
        recThread?.join(500)
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        val r = asr ?: return ""
        if (bytes.size < 16000) return "" // menos de meio segundo
        val f = FloatArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        val st = r.createStream()
        return try { st.acceptWaveform(f, 16000); r.decode(st); r.getResult(st).text.trim() } finally { st.release() }
    }

    /** PC: conexão tratada como ilimitada (a pesquisa diária pode rodar). */
    fun unmetered() = true

    /** No Windows não há pedido de permissão em tempo de execução. */
    fun requestMic(onResult: (Boolean) -> Unit) = onResult(true)
}
