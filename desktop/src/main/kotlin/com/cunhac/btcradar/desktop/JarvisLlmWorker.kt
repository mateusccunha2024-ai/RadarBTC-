package com.cunhac.btcradar.desktop

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Mensagens entre o app e o processo da IA: lista de textos (tamanho + UTF-8). */
internal object LlmWire {
    fun write(o: DataOutputStream, vararg parts: String) {
        o.writeInt(parts.size)
        for (p in parts) { val b = p.toByteArray(Charsets.UTF_8); o.writeInt(b.size); o.write(b) }
        o.flush()
    }

    fun read(i: DataInputStream): List<String> {
        val n = i.readInt()
        if (n !in 0..64) throw IOException("mensagem inválida")
        return List(n) {
            val len = i.readInt()
            if (len !in 0..(16 shl 20)) throw IOException("mensagem inválida")
            val b = ByteArray(len); i.readFully(b); String(b, Charsets.UTF_8)
        }
    }
}

/**
 * A IA local (llama.cpp) roda num processo separado do app.
 * Um erro dentro da biblioteca nativa (processador sem as instruções que ela usa, modelo que a biblioteca não aguenta,
 * falta de memória) derrubava o app inteiro sem deixar mensagem. Agora só este processo morre: o app continua aberto,
 * responde com os dados e mostra o motivo.
 */
object JarvisLlmWorker {
    const val ARG = "--jarvis-llm"

    /** Versões da biblioteca da IA, da mais rápida para a mais compatível: "" (AVX2, a do pacote), "avx", "sse". */
    val VARIANTS = listOf("", "avx", "sse")

    /** Pasta da biblioteca compatível (PC sem AVX2) que vem no instalador, ou null se não existir. */
    fun compatDir(variant: String): File? {
        if (variant.isEmpty()) return null
        val roots = listOfNotNull(System.getProperty("compose.application.resources.dir"), System.getProperty("jarvis.compat.dir"))
        return roots.map { File(it, "jarvis-compat/$variant") }.firstOrNull { File(it, "jllama.dll").isFile || File(it, "libjllama.so").isFile }
    }

    /** Usa a biblioteca compatível: carrega as dependências na ordem e aponta o carregador do llama para a pasta. */
    private fun useVariant(variant: String) {
        val dir = compatDir(variant) ?: return
        val dlls = dir.listFiles()?.filter { it.name.endsWith(".dll") && !it.name.startsWith("jllama") }.orEmpty()
        val order = listOf("ggml-base", "ggml-cpu", "ggml", "llama")
        for (f in dlls.sortedBy { d -> order.indexOfFirst { d.name.startsWith(it) }.let { if (it < 0) 99 else it } })
            runCatching { System.load(f.absolutePath) }
        System.setProperty("de.kherud.llama.lib.path", dir.absolutePath)
        System.err.println("jarvis: biblioteca compatível '$variant' em $dir")
    }

    /** Lado do processo da IA: conecta no app, carrega o modelo e responde. Sai quando o app fecha a conexão. */
    fun serve(args: Array<String>) {
        val port = args.getOrNull(1)?.toIntOrNull() ?: return
        val token = args.getOrNull(2) ?: return
        args.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { useVariant(it) }
        var model: LlamaModel? = null
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.tcpNoDelay = true
            val i = DataInputStream(BufferedInputStream(s.getInputStream()))
            val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            LlmWire.write(o, "hello", token, ProcessHandle.current().pid().toString())
            while (true) {
                val m = try { LlmWire.read(i) } catch (e: EOFException) { break } catch (e: IOException) { break }
                val cmd = m.firstOrNull() ?: break
                if (cmd == "quit") break
                if (cmd == "crash") Runtime.getRuntime().halt(3) // só o teste usa: simula a IA morrendo no meio
                val reply = runCatching {
                    when (cmd) {
                        "load" -> {
                            runCatching { model?.close() }; model = null
                            val t = m[3].toInt()
                            model = LlamaModel(ModelParameters().setModel(m[1]).setCtxSize(m[2].toInt()).setThreads(t).setThreadsBatch(t).setGpuLayers(0))
                            listOf("ok")
                        }
                        "complete" -> {
                            val mm = model ?: error("modelo não carregado")
                            listOf("ok", mm.complete(InferenceParameters(m[1]).setNPredict(m[2].toInt()).setTemperature(m[3].toFloat()).setTopP(0.9f)
                                .setRepeatPenalty(1.1f).setStopStrings(*m.drop(4).toTypedArray())))
                        }
                        "ping" -> listOf("ok")
                        else -> listOf("err", "comando desconhecido: $cmd")
                    }
                }.getOrElse { listOf("err", it.message ?: it.javaClass.simpleName) }
                LlmWire.write(o, *reply.toTypedArray())
            }
        }
        runCatching { model?.close() }
    }

    /** Fora do app instalado (testes e desenvolvimento) o processo é iniciado por esta classe. */
    @JvmStatic fun main(args: Array<String>) {
        runCatching { serve(args) }
        kotlin.system.exitProcess(0)
    }
}

/** Lado do app: inicia o processo da IA, conversa com ele e explica o motivo se ele cair. */
internal object LlmProcess {
    private var proc: Process? = null
    private var sock: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var pid = -1L
    private var startedMs = 0L
    /** Motivo da última queda (o Jarvis lê uma vez e mostra). */
    @Volatile var crash: String? = null
    /** A última queda foi por instrução que o processador não tem (dá para tentar a biblioteca compatível). */
    @Volatile var lastIllegal = false
    /** Versão da biblioteca em uso no processo aberto ("" = padrão). */
    @Volatile var variant = ""; private set

    val alive: Boolean get() = sock != null && proc?.isAlive == true

    /** Pasta onde o processo roda: o Java deixa ali o relatório (hs_err) se a parte nativa morrer. */
    var dir: () -> File = { JarvisNative.modelsDir }

    @Synchronized fun start(variant: String = ""): String? {
        stop()
        this.variant = variant
        lastIllegal = false
        val d = dir()
        runCatching { d.listFiles()?.filter { it.name.startsWith("hs_err_pid") }?.forEach { it.delete() } }
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            server.soTimeout = 90_000
            val token = UUID.randomUUID().toString()
            val cmd = launcher() + listOf(JarvisLlmWorker.ARG, server.localPort.toString(), token, variant)
            startedMs = System.currentTimeMillis()
            val p = ProcessBuilder(cmd).directory(d).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(File(d, "ia-processo.log"))).start()
            proc = p
            val s = try { server.accept() } catch (e: SocketTimeoutException) {
                p.destroyForcibly()
                return "a IA não iniciou em 90 s"
            }
            s.tcpNoDelay = true
            val i = DataInputStream(BufferedInputStream(s.getInputStream()))
            val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            s.soTimeout = 30_000
            val hello = runCatching { LlmWire.read(i) }.getOrNull()
            if (hello == null || hello.getOrNull(0) != "hello" || hello.getOrNull(1) != token) {
                runCatching { s.close() }; p.destroyForcibly()
                return "a IA não respondeu ao iniciar"
            }
            pid = hello.getOrNull(2)?.toLongOrNull() ?: p.pid()
            sock = s; input = i; output = o
            return null
        } catch (e: IOException) {
            stop()
            return "a IA não iniciou: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { server.close() }
        }
    }

    /** App instalado: o próprio RadarBTC.exe abre o processo da IA (o runtime do instalador não traz java.exe). */
    private fun launcher(): List<String> {
        System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }?.let { return listOf(it) }
        val win = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val java = File(System.getProperty("java.home"), "bin/" + if (win) "java.exe" else "java")
        // Classpath: o do processo atual + os jars do worker, do llama e do Kotlin (nos testes o Gradle não põe tudo em java.class.path).
        val parts = LinkedHashSet<String>()
        System.getProperty("java.class.path").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.forEach { parts += it }
        for (c in listOf(JarvisLlmWorker::class.java, LlamaModel::class.java, Unit::class.java))
            runCatching { parts += File(c.protectionDomain.codeSource.location.toURI()).path }
        return listOf(java.path, "-cp", parts.joinToString(File.pathSeparator), JarvisLlmWorker::class.java.name)
    }

    /** Envia um pedido e espera a resposta. Se o processo cair (ou demorar demais), lança IOException com o motivo. */
    @Synchronized fun call(timeoutMs: Int, vararg parts: String): List<String> {
        val s = sock ?: throw IOException(crash ?: "a IA não está aberta")
        try {
            s.soTimeout = timeoutMs
            LlmWire.write(output!!, *parts)
            return LlmWire.read(input!!)
        } catch (e: IOException) {
            val timedOut = e is SocketTimeoutException
            val why = died(timedOut)
            throw IOException(why)
        }
    }

    /** O processo morreu parado (entre perguntas): registra o motivo. */
    @Synchronized fun checkLost() { if (sock != null && proc?.isAlive == false) died(false) }

    /** Simula a queda (teste). */
    @Synchronized fun crashForTest() { runCatching { LlmWire.write(output!!, "crash") } }

    private fun died(timedOut: Boolean): String {
        val p = proc
        if (timedOut) runCatching { p?.destroyForcibly() }
        runCatching { p?.waitFor(5, TimeUnit.SECONDS) }
        val why = describe(p, timedOut)
        crash = why
        runCatching { FileLog.info("jarvis: o processo da IA caiu (o app continua): $why") }
        closeQuietly()
        return why
    }

    private fun describe(p: Process?, timedOut: Boolean): String {
        if (timedOut) return "a IA demorou demais para responder e foi reiniciada"
        val code = runCatching { p?.exitValue() }.getOrNull()
        val d = dir()
        val report = runCatching {
            d.listFiles()?.filter { it.name.startsWith("hs_err_pid") && it.lastModified() >= startedMs - 2_000 }?.maxByOrNull { it.lastModified() }?.readText()
        }.getOrNull().orEmpty()
        val log = runCatching { File(d, "ia-processo.log").readLines().takeLast(200) }.getOrDefault(emptyList())
        val assert = log.lastOrNull { it.contains("GGML_ASSERT") || it.contains("terminate called") }?.trim()?.take(160)
        val cpu = report.lineSequence().firstOrNull { it.startsWith("CPU:") }.orEmpty().lowercase()
        val frame = report.lineSequence().dropWhile { !it.startsWith("# Problematic frame") }.drop(1).firstOrNull()?.removePrefix("#")?.trim()?.take(120)
        lastIllegal = report.contains("EXCEPTION_ILLEGAL_INSTRUCTION") || report.contains("SIGILL") || code == 0xC000001D.toInt() || code == 132
        return when {
            lastIllegal ->
                "o processador deste PC não tem as instruções que a IA local usa" + (if (cpu.isNotEmpty() && "avx2" !in cpu) " (falta AVX2)" else "")
            report.contains("Out of Memory") || report.contains("Native memory allocation") || log.any { it.contains("failed to allocate", true) } ->
                "faltou memória para este modelo"
            report.contains("EXCEPTION_ACCESS_VIOLATION") || report.contains("SIGSEGV") || code == 0xC0000005.toInt() ->
                "erro dentro da biblioteca nativa da IA" + (frame?.let { " ($it)" } ?: "")
            assert != null -> "a biblioteca da IA parou: $assert"
            else -> "a IA fechou sozinha" + (code?.let { " (código $it)" } ?: "")
        }
    }

    private fun closeQuietly() {
        runCatching { sock?.close() }
        sock = null; input = null; output = null
        proc = null
    }

    @Synchronized fun stop() {
        val s = sock
        if (s != null) runCatching { s.soTimeout = 2_000; LlmWire.write(output!!, "quit") }
        val p = proc
        closeQuietly()
        if (p != null) runCatching { if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { proc?.destroyForcibly() } })
    }
}

/**
 * Teste do app instalado (CI do Windows): `RadarBTC.exe --jarvis-llm-test <modelo.gguf> <saida.txt>`.
 * Abre o processo da IA pelo próprio executável, carrega o modelo, pede uma resposta, simula a queda e confere
 * que este processo continua vivo e recebe o motivo.
 */
object LlmProcessSelfTest {
    const val ARG = "--jarvis-llm-test"

    fun run(args: Array<String>) {
        val model = args.getOrNull(1)
        val out = File(args.getOrNull(2) ?: "jarvis-llm-test.txt")
        val lines = ArrayList<String>()
        fun log(s: String) { lines += s; runCatching { out.writeText(lines.joinToString("\n") + "\n") } }
        try {
            val d = File(System.getProperty("java.io.tmpdir"), "radarbtc-ia-test").apply { mkdirs() }
            LlmProcess.dir = { d }
            log("app-path: ${System.getProperty("jpackage.app-path")}")
            val variant = args.getOrNull(3).orEmpty()
            log("biblioteca: ${variant.ifEmpty { "padrão" }} ${JarvisLlmWorker.compatDir(variant) ?: ""}")
            if (variant.isNotEmpty() && JarvisLlmWorker.compatDir(variant) == null) { log("fim com erro: biblioteca compatível '$variant' não está no app"); return }
            log("iniciar: ${LlmProcess.start(variant) ?: "ok"}")
            log("ping: ${LlmProcess.call(30_000, "ping")}")
            if (model != null && File(model).isFile) {
                val t0 = System.currentTimeMillis()
                log("carregar: ${LlmProcess.call(300_000, "load", model, "4096", "2")} em ${(System.currentTimeMillis() - t0) / 1000} s")
                val p = "<|im_start|>system\nAnswer with one short word.<|im_end|>\n<|im_start|>user\nSay hello.<|im_end|>\n<|im_start|>assistant\n"
                log("responder: ${LlmProcess.call(300_000, "complete", p, "8", "0.1", "<|im_end|>", "<|im_start|>")}")
                // Prova de que a biblioteca certa foi carregada: o llama lista as instruções com que foi compilado.
                val lines = runCatching { File(LlmProcess.dir(), "ia-processo.log").readLines() }.getOrDefault(emptyList())
                val info = lines.firstOrNull { it.contains("system_info") }.orEmpty()
                log("cpu: ${info.substringAfter("CPU :", info).trim()}")
                lines.firstOrNull { it.contains("biblioteca compatível") }?.let { log(it.trim()) }
                if (variant.isNotEmpty() && (info.isBlank() || Regex("(AVX2|BMI2|FMA|F16C) = 1").containsMatchIn(info))) {
                    log("fim com erro: a biblioteca '$variant' não é a compatível (instruções: $info)"); return
                }
            }
            LlmProcess.crashForTest()
            val why = try { LlmProcess.call(30_000, "ping"); "NÃO CAIU" } catch (e: IOException) { e.message }
            log("queda simulada: motivo='$why' vivo=${LlmProcess.alive}")
            log("reiniciar: ${LlmProcess.start(variant) ?: "ok"} ping=${LlmProcess.call(30_000, "ping")}")
            LlmProcess.stop()
            log("fim ok")
        } catch (e: Throwable) {
            log("fim com erro: $e")
        }
    }
}
