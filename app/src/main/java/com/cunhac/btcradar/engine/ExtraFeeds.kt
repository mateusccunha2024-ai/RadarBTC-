package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.data.net.dbl
import com.cunhac.btcradar.data.net.o
import com.cunhac.btcradar.data.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Corretoras que entram só com liquidações REAIS (sem livro e sem fluxo):
 * Gate, Bitget e Deribit. Canais públicos, sem chave.
 */
class GateFeed(private val sink: FeedSink, private val scope: CoroutineScope) {
    private val v = Venue.GATE
    @Volatile private var multiplier = 0.0001
    private lateinit var conn: WsConn

    fun start() {
        scope.launch {
            runCatching {
                val m = Net.getJson("https://api.gateio.ws/api/v4/futures/usdt/contracts/BTC_USDT").o.dbl("quanto_multiplier")
                if (m > 0) multiplier = m
            }.onFailure { sink.log(LogLevel.WARN, v, "metadados do contrato indisponíveis, usando 0,0001 BTC: ${it.message}") }
        }
        conn = WsConn("wss://fx-ws.gateio.ws/v4/ws/usdt", sink.status(v, "Liquidações (futures.public_liquidates)"), scope,
            { l, t -> sink.log(l, v, t) }, ping = """{"channel":"futures.ping"}""", pingEveryMs = 8_000,
            onOpen = {
                it.send("""{"time":${System.currentTimeMillis() / 1000},"channel":"futures.public_liquidates","event":"subscribe","payload":["BTC_USDT"]}""")
            }, onText = { text ->
                val j = Net.parse(text).o
                if (j.str("channel") == "futures.public_liquidates") Parse.gateLiquidations(j, multiplier).forEach(sink::onLiq)
            })
        conn.start()
    }

    fun stop() = conn.stop()
}

class BitgetFeed(private val sink: FeedSink, private val scope: CoroutineScope) {
    private val v = Venue.BITGET
    private lateinit var conn: WsConn

    fun start() {
        conn = WsConn("wss://ws.bitget.com/v3/ws/public", sink.status(v, "Liquidações (UTA v3 liquidation)"), scope,
            { l, t -> sink.log(l, v, t) }, ping = "ping", pingEveryMs = 8_000,
            onOpen = { it.send("""{"op":"subscribe","args":[{"instType":"usdt-futures","topic":"liquidation"}]}""") },
            onText = { text ->
                if (text != "pong") {
                    val j = Net.parse(text).o
                    if (j.str("event") == "error") sink.log(LogLevel.ERROR, v, "inscrição recusada: ${j.str("msg")}")
                    else if (j["data"] != null) Parse.bitgetLiquidations(j).forEach(sink::onLiq)
                }
            })
        conn.start()
    }

    fun stop() = conn.stop()
}

class DeribitFeed(private val sink: FeedSink, private val scope: CoroutineScope) {
    private val v = Venue.DERIBIT
    private lateinit var conn: WsConn

    fun start() {
        conn = WsConn("wss://www.deribit.com/ws/api/v2", sink.status(v, "Trades com marca de liquidação"), scope,
            { l, t -> sink.log(l, v, t) }, ping = """{"jsonrpc":"2.0","id":9,"method":"public/test"}""", pingEveryMs = 20_000,
            onOpen = { it.send("""{"jsonrpc":"2.0","id":1,"method":"public/subscribe","params":{"channels":["trades.BTC-PERPETUAL.100ms"]}}""") },
            onText = { text ->
                val j = Net.parse(text).o
                if (j.str("method") == "subscription") Parse.deribitLiquidations(j).forEach(sink::onLiq)
            })
        conn.start()
    }

    fun stop() = conn.stop()
}
