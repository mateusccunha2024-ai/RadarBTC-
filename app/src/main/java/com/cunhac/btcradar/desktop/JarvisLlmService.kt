package com.cunhac.btcradar.desktop

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A IA local (llama.cpp) roda num processo separado do app (":jarvis", ver AndroidManifest).
 * Se ela travar dentro da biblioteca nativa ou o Android a fechar por falta de memória, só este processo morre:
 * o app continua aberto, responde com os dados e mostra o motivo.
 */
class JarvisLlmService : Service() {
    private val thread = HandlerThread("jarvis-llm").apply { start() }
    private var model: Any? = null   // net.ladenthin.llama.LlamaModel

    private val messenger by lazy {
        Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) = handle(msg)
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handle(msg: Message) {
        val d = msg.data
        val out = Bundle()
        runCatching {
            when (msg.what) {
                LlmRemote.LOAD -> {
                    Llm.close(model); model = null
                    model = Llm.open(d.getString("path")!!, d.getInt("ctx", 4096), d.getInt("threads", 4))
                }
                LlmRemote.COMPLETE -> out.putString("text", Llm.complete(model ?: error("modelo não carregado"), d.getString("prompt").orEmpty(),
                    d.getInt("n", 128), d.getFloat("temp", 0.2f), d.getStringArrayList("stop") ?: arrayListOf()))
                LlmRemote.CRASH -> android.os.Process.killProcess(android.os.Process.myPid()) // só o teste usa
                else -> {}
            }
        }.onFailure { out.putString("err", it.message ?: it.javaClass.simpleName) }
        out.putInt("id", d.getInt("id"))
        val r = Message.obtain(null, msg.what).apply { data = out }
        runCatching { msg.replyTo?.send(r) }
    }

    override fun onDestroy() {
        runCatching { Llm.close(model) }; model = null
        super.onDestroy()
        // Devolve a memória da IA ao celular assim que o app solta o serviço.
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Isolado: as classes do llama só são carregadas neste processo e em aparelhos compatíveis. */
    private object Llm {
        fun open(path: String, ctx: Int, threads: Int): Any = net.ladenthin.llama.LlamaModel(
            net.ladenthin.llama.parameters.ModelParameters().setModel(path).setCtxSize(ctx).setThreads(threads).setThreadsBatch(threads))
        fun close(m: Any?) { (m as? net.ladenthin.llama.LlamaModel)?.close() }
        fun complete(m: Any, prompt: String, maxTokens: Int, temperature: Float, stop: List<String>): String =
            (m as net.ladenthin.llama.LlamaModel).complete(
                net.ladenthin.llama.parameters.InferenceParameters(prompt).withNPredict(maxTokens).withTemperature(temperature)
                    .withStopStrings(*stop.toTypedArray()))
    }
}

/** Lado do app: liga o serviço da IA, conversa com ele e explica o motivo se ele cair. */
internal object LlmRemote {
    const val LOAD = 1
    const val COMPLETE = 2
    const val CRASH = 9

    private val replyThread = HandlerThread("jarvis-llm-reply").apply { start() }
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    private val ids = AtomicInteger()
    private val reply = Messenger(Handler(replyThread.looper) { m ->
        pending.remove(m.data.getInt("id"))?.complete(m.data); true
    })

    @Volatile private var service: Messenger? = null
    @Volatile private var conn: ServiceConnection? = null
    @Volatile private var boundMs = 0L
    @Volatile private var lost = false
    /** Motivo da última queda (o Jarvis lê uma vez e mostra). */
    @Volatile var crash: String? = null

    val connected get() = service != null && !lost

    @Synchronized fun bind(): String? {
        if (connected) return null
        unbind()
        val latch = CountDownLatch(1)
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                if (conn !== this || b == null) return
                runCatching { b.linkToDeath({ lostService() }, 0) }
                lost = false
                service = Messenger(b)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { if (conn === this) lostService() }
            override fun onBindingDied(name: ComponentName?) { if (conn === this) lostService() }
        }
        conn = c
        lost = false
        boundMs = System.currentTimeMillis()
        val app = Platform.app
        val ok = runCatching { app.bindService(Intent(app, JarvisLlmService::class.java), c, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!ok || !latch.await(30, TimeUnit.SECONDS)) { unbind(); return "a IA não iniciou" }
        return null
    }

    /** O processo da IA morreu: libera quem esperava resposta. */
    private fun lostService() {
        if (lost) return
        lost = true
        service = null
        val e = IOException("lost")
        pending.values.forEach { it.completeExceptionally(e) }
        pending.clear()
    }

    /** Envia um pedido e espera a resposta. Se a IA cair (ou demorar demais), lança IOException com o motivo. */
    fun call(what: Int, timeoutMs: Long, fill: Bundle.() -> Unit): Bundle {
        val s = service ?: throw IOException(crash ?: "a IA não está aberta")
        val id = ids.incrementAndGet()
        val f = CompletableFuture<Bundle>()
        pending[id] = f
        val m = Message.obtain(null, what).apply { data = Bundle().apply { fill(); putInt("id", id) }; replyTo = reply }
        try {
            s.send(m)
            return f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(id)
            throw IOException(failed("a IA demorou demais para responder e foi reiniciada"))
        } catch (e: ExecutionException) {
            throw IOException(failed(null))
        } catch (e: RemoteException) {
            pending.remove(id)
            throw IOException(failed(null))
        }
    }

    private fun failed(fixed: String?): String {
        lostService()
        val why = fixed ?: reason()
        crash = why
        runCatching { FileLog.info("jarvis: o processo da IA caiu (o app continua): $why") }
        unbind()
        return why
    }

    /** O Android registra por que o processo ":jarvis" acabou (Android 11+). */
    private fun reason(): String {
        if (Build.VERSION.SDK_INT >= 30) {
            repeat(6) {
                val info = runCatching {
                    Platform.app.getSystemService(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(Platform.app.packageName, 0, 10)
                        .firstOrNull { it.processName.endsWith(":jarvis") && it.timestamp >= boundMs - 1_000 }
                }.getOrNull()
                if (info != null) return when (info.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "o Android fechou a IA por falta de memória"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "erro dentro da biblioteca nativa da IA"
                    ApplicationExitInfo.REASON_CRASH -> "erro na IA" + (info.description?.let { ": ${it.take(120)}" } ?: "")
                    ApplicationExitInfo.REASON_SIGNALED -> "a IA foi encerrada pelo sistema (sinal ${info.status})"
                    ApplicationExitInfo.REASON_ANR -> "a IA parou de responder"
                    else -> "a IA fechou sozinha" + (info.description?.let { " ($it)" } ?: "")
                }
                Thread.sleep(250)
            }
        }
        return "a IA fechou sozinha (possivelmente falta de memória)"
    }

    /** O processo morreu parado (entre perguntas): registra o motivo. Chamar fora da tela principal. */
    fun checkLost() { if (lost && conn != null) failed(null) }

    /** Simula a queda (teste). */
    fun crashForTest() { runCatching { service?.send(Message.obtain(null, CRASH).apply { data = Bundle(); replyTo = reply }) } }

    @Synchronized fun unbind() {
        val c = conn
        conn = null
        service = null
        if (c != null) runCatching { Platform.app.unbindService(c) }
    }
}
