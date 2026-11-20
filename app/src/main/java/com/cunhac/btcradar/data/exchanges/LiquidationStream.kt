package com.cunhac.btcradar.data.exchanges

import com.cunhac.btcradar.data.model.Exchange
import com.cunhac.btcradar.data.model.Liquidation
import com.cunhac.btcradar.data.model.Side
import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.data.net.arr
import com.cunhac.btcradar.data.net.dbl
import com.cunhac.btcradar.data.net.lng
import com.cunhac.btcradar.data.net.o
import com.cunhac.btcradar.data.net.obj
import com.cunhac.btcradar.data.net.str
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import kotlin.math.min

/**
 * Liquidações em tempo real das três maiores corretoras de derivativos,
 * unificadas num único fluxo.
 *
 * Observação: a Binance só envia a liquidação mais recente a cada 1s por símbolo,
 * então o volume dela aparece subestimado em momentos de cascata.
 */
object LiquidationStream {

    fun all(): Flow<Liquidation> = merge(binance(), bybit(), okx())

    private fun binance() = socket(
        url = "wss://fstream.binance.com/market/ws/btcusdt@forceOrder",
    ) { text ->
        val o = Net.parse(text).o.obj("o") ?: return@socket emptyList()
        if (o.str("s") != "BTCUSDT") return@socket emptyList()
        val price = o.dbl("ap").takeIf { it > 0 } ?: o.dbl("p")
        listOf(
            Liquidation(
                exchange = Exchange.BINANCE,
                side = if (o.str("S") == "SELL") Side.LONG else Side.SHORT, // ordem de venda forçada = long liquidado
                price = price,
                qtyBtc = o.dbl("z"),
                timeMs = o.lng("T"),
            )
        )
    }

    private fun bybit() = socket(
        url = "wss://stream.bybit.com/v5/public/linear",
        subscribe = """{"op":"subscribe","args":["allLiquidation.BTCUSDT"]}""",
        keepAlive = """{"op":"ping"}""",
    ) { text ->
        val j = Net.parse(text).o
        if (!j.str("topic").startsWith("allLiquidation")) return@socket emptyList()
        j.arr("data").map {
            val d = it.o
            Liquidation(
                exchange = Exchange.BYBIT,
                side = if (d.str("S") == "Buy") Side.LONG else Side.SHORT, // na Bybit "S" é o lado da posição
                price = d.dbl("p"),
                qtyBtc = d.dbl("v"),
                timeMs = d.lng("T"),
            )
        }
    }

    private fun okx() = socket(
        url = "wss://ws.okx.com:443/ws/v5/public",
        subscribe = """{"op":"subscribe","args":[{"channel":"liquidation-orders","instType":"SWAP"}]}""",
        keepAlive = "ping",
    ) { text ->
        if (text == "pong") return@socket emptyList()
        Net.parse(text).o.arr("data")
            .map { it.o }
            .filter { it.str("instId") == "BTC-USDT-SWAP" }
            .flatMap { inst ->
                inst.arr("details").map {
                    val d = it.o
                    val side = when (d.str("posSide")) {
                        "long" -> Side.LONG
                        "short" -> Side.SHORT
                        else -> if (d.str("side") == "sell") Side.LONG else Side.SHORT
                    }
                    Liquidation(Exchange.OKX, side, d.dbl("bkPx"), d.dbl("sz") * Okx.CONTRACT_BTC, d.lng("ts"))
                }
            }
    }

    /** WebSocket genérico com ping de aplicação e reconexão automática. */
    private fun socket(
        url: String,
        subscribe: String? = null,
        keepAlive: String? = null,
        parse: (String) -> List<Liquidation>,
    ): Flow<Liquidation> = callbackFlow {
        val ws = Net.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                subscribe?.let { webSocket.send(it) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { parse(text) }.getOrNull()?.forEach { if (it.qtyBtc > 0) trySend(it) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("Conexão encerrada ($code)"))
            }
        })
        val pinger = keepAlive?.let { msg ->
            launch { while (isActive) { delay(20_000); ws.send(msg) } }
        }
        awaitClose {
            pinger?.cancel()
            ws.cancel()
        }
    }.retryWhen { _, attempt ->
        delay(min(30_000L, 2_000L * (attempt + 1)))
        true
    }
}
