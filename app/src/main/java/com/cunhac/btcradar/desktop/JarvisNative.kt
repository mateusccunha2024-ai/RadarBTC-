package com.cunhac.btcradar.desktop

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference

/**
 * Android: IA (llama.cpp, net.ladenthin:llama-android, no processo ":jarvis"), voz (Kokoro) e microfone (Whisper) rodando no próprio celular.
 * A IA local exige Android 9 ou mais novo e processador de 64 bits; sem isso o assistente responde só com os dados do app.
 */
object JarvisNative {
    val modelsDir: File get() = File(Platform.app.filesDir, "jarvis").apply { mkdirs() }
    val llmSupported: Boolean get() = Build.VERSION.SDK_INT >= 28 && Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
    const val micSupported = true
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    @Volatile private var llmReady = false
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var asr: OfflineRecognizer? = null
    private val llmLock = Any()

    /** A IA roda no processo ":jarvis" ([JarvisLlmService]): se ela cair, o app continua aberto. */
    val llmLoaded get() = llmReady && LlmRemote.connected
    val ttsLoaded get() = tts != null
    val asrLoaded get() = asr != null

    fun loadLlm(path: String): String? = synchronized(llmLock) {
        if (!llmSupported) return "Este aparelho não roda a IA local (precisa de Android 9+ e 64 bits)."
        llmReady = false
        LlmRemote.bind()?.let { e -> LlmRemote.crash = e; FileLog.info("jarvis llm: $e"); return e }
        try {
            val r = LlmRemote.call(LlmRemote.LOAD, 300_000) { putString("path", path); putInt("ctx", 4096); putInt("threads", threads) }
            r.getString("err")?.let { e -> FileLog.info("jarvis llm: $e"); return e }
            llmReady = true
            null
        } catch (e: java.io.IOException) { e.message ?: "a IA fechou" }
    }

    fun unloadLlm() = synchronized(llmLock) { llmReady = false; runCatching { LlmRemote.unbind() }; Unit }

    fun complete(prompt: String, maxTokens: Int, temperature: Float, stop: List<String>): String = synchronized(llmLock) {
        checkLlm()
        if (!llmLoaded) error(LlmRemote.crash ?: "modelo não carregado")
        val r = try {
            LlmRemote.call(LlmRemote.COMPLETE, 300_000) {
                putString("prompt", prompt); putInt("n", maxTokens); putFloat("temp", temperature); putStringArrayList("stop", ArrayList(stop))
            }
        } catch (e: java.io.IOException) { llmReady = false; throw IllegalStateException(e.message ?: "a IA fechou") }
        r.getString("err")?.let { e ->
            // Processo da IA recriado sem o modelo (caiu e voltou): trata como queda para mostrar o motivo.
            if (e == "modelo não carregado") { llmReady = false; LlmRemote.crash = LlmRemote.crash ?: "a IA fechou e o Android a reabriu sem o modelo"; LlmRemote.unbind() }
            error(e)
        }
        r.getString("text").orEmpty()
    }

    /** Motivo, se o processo da IA caiu desde a última consulta (o Jarvis mostra e desliga o modelo). */
    /** Confere se o processo da IA morreu parado; o motivo fica para [takeCrash]. */
    fun checkLlm() { if (llmReady) runCatching { LlmRemote.checkLost() }; if (!LlmRemote.connected) llmReady = false }

    fun takeCrash(): String? = LlmRemote.crash.also { LlmRemote.crash = null }

    /** Só o teste: derruba o processo da IA para conferir que o app continua. */
    fun crashLlmForTest() { runCatching { LlmRemote.crashForTest() } }

    fun loadTts(dir: File, model: String, lang: String, lexicon: String, dictDir: String?): String? = runCatching {
        tts?.release(); tts = null
        val k = OfflineTtsKokoroModelConfig(model = File(dir, model).path, voices = File(dir, "voices.bin").path,
            tokens = File(dir, "tokens.txt").path, dataDir = File(dir, "espeak-ng-data").path, lexicon = lexicon, lang = lang,
            dictDir = dictDir ?: "")
        tts = OfflineTts(null, OfflineTtsConfig(model = OfflineTtsModelConfig(kokoro = k, numThreads = 2)))
        null
    }.getOrElse { (it.message ?: it.javaClass.simpleName).also { m -> FileLog.info("jarvis tts: $m") } }

    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopFlag = false

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
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 4).setTransferMode(AudioTrack.MODE_STATIC).build()
        track = tr
        try {
            tr.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            tr.play()
            val ms = samples.size * 1000L / rate
            val until = System.currentTimeMillis() + ms + 150
            while (!stopFlag && System.currentTimeMillis() < until) Thread.sleep(40)
        } finally { runCatching { tr.stop() }; tr.release(); track = null }
    }

    fun stopSpeaking() { stopFlag = true; runCatching { track?.stop() } }

    fun loadAsr(encoder: File, decoder: File, tokens: File, language: String): String? = runCatching {
        asr?.release(); asr = null
        val mc = OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder = encoder.path, decoder = decoder.path, language = language, task = "transcribe"),
            tokens = tokens.path, numThreads = threads, modelType = "whisper")
        asr = OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80), modelConfig = mc))
        null
    }.getOrElse { (it.message ?: it.javaClass.simpleName).also { m -> FileLog.info("jarvis asr: $m") } }

    @Volatile private var rec: AudioRecord? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var recThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startRecording(): String? {
        if (Platform.app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "Permita o microfone para falar com o assistente."
        return runCatching {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            synchronized(pcm) { pcm.reset() }
            r.startRecording(); rec = r
            recThread = Thread {
                val b = ByteArray(3200)
                while (rec === r) { val n = r.read(b, 0, b.size); if (n > 0) synchronized(pcm) { pcm.write(b, 0, n) } }
            }.apply { isDaemon = true; start() }
            null
        }.getOrElse { it.message ?: "microfone indisponível" }
    }

    fun stopRecordingAndTranscribe(): String {
        val r = rec ?: return ""
        rec = null
        runCatching { r.stop(); r.release() }
        recThread?.join(500)
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        val a = asr ?: return ""
        if (bytes.size < 16000) return ""
        val f = FloatArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        val st = a.createStream()
        return try { st.acceptWaveform(f, 16000); a.decode(st); a.getResult(st).text.trim() } finally { st.release() }
    }

    /** Pesquisa diária só no Wi-Fi (rede sem cobrança por dados). */
    /** Memória livre do celular em MB (o Android mata o app se a IA não couber). */
    fun freeRamMb(): Long = runCatching {
        val am = Platform.app.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        mi.availMem / 1_048_576L
    }.getOrDefault(-1L)

    fun unmetered(): Boolean = runCatching {
        val cm = Platform.app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }.getOrDefault(false)

    // Pedido de permissão do microfone (a MainActivity repassa o resultado).
    var activity: WeakReference<Activity>? = null
    private var pending: ((Boolean) -> Unit)? = null
    const val REQ_MIC = 77

    fun requestMic(onResult: (Boolean) -> Unit) {
        if (Platform.app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { onResult(true); return }
        val a = activity?.get() ?: run { onResult(false); return }
        pending = onResult
        a.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    fun onPermission(granted: Boolean) { pending?.invoke(granted); pending = null }
}
