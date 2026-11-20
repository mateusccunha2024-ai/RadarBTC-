package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.net.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min

/**
 * Uma conexão WebSocket por corretora/rota, com ping de aplicação e reconexão com backoff.
 * Mensagens são entregues imediatamente (thread do OkHttp); a interface agrupa depois.
 */
class WsConn(
    private val url: String,
    val status: SourceStatus,
    private val scope: CoroutineScope,
    private val log: (LogLevel, String) -> Unit,
    private val ping: String? = null,
    private val pingEveryMs: Long = 20_000,
    private val onOpen: (WsConn) -> Unit = {},
    private val onText: (String) -> Unit,
) {
    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var attempt = 0
    private var pinger: Job? = null

    fun start() {
        stopped = false
        connect()
        if (ping != null) pinger = scope.launch {
            while (isActive && !stopped) { delay(pingEveryMs); ws?.send(ping) }
        }
    }

    fun send(text: String): Boolean = ws?.send(text) ?: false

    /** Derruba a conexão atual; a reconexão automática refaz inscrições e snapshots. */
    fun restart(reason: String) {
        log(LogLevel.WARN, "${status.channel}: reiniciando conexão ($reason)")
        ws?.cancel()
    }

    fun stop() {
        stopped = true
        pinger?.cancel()
        ws?.close(1000, "encerrado")
        status.state = ConnState.DOWN
    }

    private fun connect() {
        if (stopped) return
        status.state = ConnState.CONNECTING
        ws = Net.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                status.state = ConnState.SUBSCRIBED
                log(LogLevel.INFO, "${status.channel}: conectado")
                runCatching { onOpen(this@WsConn) }.onFailure { log(LogLevel.ERROR, "${status.channel}: falha na inscrição: ${it.message}") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                status.onMessage()
                try {
                    onText(text)
                } catch (e: Exception) {
                    status.lastError = "parsing: ${e.message}"
                    log(LogLevel.ERROR, "${status.channel}: falha de parsing: ${e.message} | ${text.take(160)}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                val msg = (response?.let { "HTTP ${it.code} " } ?: "") + (t.message ?: t.javaClass.simpleName)
                status.lastError = msg
                log(LogLevel.WARN, "${status.channel}: desconectado: $msg")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                log(LogLevel.WARN, "${status.channel}: fechado pelo servidor ($code $reason)")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        status.state = ConnState.DOWN
        status.reconnects++
        val wait = min(30_000L, 1_000L shl min(attempt, 5))
        attempt++
        scope.launch { delay(wait); connect() }
    }
}
