package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.data.net.arr
import com.cunhac.btcradar.data.net.dbl
import com.cunhac.btcradar.data.net.lng
import com.cunhac.btcradar.data.net.o
import com.cunhac.btcradar.data.net.obj
import com.cunhac.btcradar.data.net.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** O que os adaptadores precisam do hub central. */
interface FeedSink {
    fun book(v: Venue): LocalBook
    val bybitRpi: LocalBook
    fun status(v: Venue, channel: String): SourceStatus
    fun onTrade(t: TradeEvent)
    fun onLiq(l: LiqEvent)
    fun onTicker(v: Venue, update: (DerivTicker) -> DerivTicker)
    fun log(level: LogLevel, venue: Venue?, text: String)
}

// =====================================================================
// BINANCE USD-M: /public (livro diferencial) + /market (trades, mark, liquidações)
// =====================================================================
class BinanceFeed(private val sink: FeedSink, private val scope: CoroutineScope) {
    private val v = Venue.BINANCE
    private val book = sink.book(v)
    private val sync = BinanceSync()
    private val buffer = ArrayList<Parse.BinanceDepth>()
    private val lock = Any()
    private var fetching = false
    private val depthStatus = sink.status(v, "Livro (/public depth@100ms + REST 1000)")
    private var conns = emptyList<WsConn>()

    fun start() {
        val log: (LogLevel, String) -> Unit = { l, t -> sink.log(l, v, t) }
        val depth = WsConn("wss://fstream.binance.com/public/stream?streams=btcusdt@depth@100ms", depthStatus, scope, log,
            onOpen = { resync("nova conexão") }, onText = ::onDepth)
        val market = WsConn("wss://fstream.binance.com/market/stream?streams=btcusdt@aggTrade/btcusdt@markPrice@1s/btcusdt@forceOrder",
            sink.status(v, "Trades/mark/liquidações (/market)"), scope, log, onText = ::onMarket)
        conns = listOf(depth, market)
        conns.forEach { it.start() }
        // A Binance não publica open interest por WebSocket: REST a cada 15 s.
        scope.launch {
            while (isActive) {
                try {
                    val oi = Net.getJson("https://fapi.binance.com/fapi/v1/openInterest?symbol=BTCUSDT").o.dbl("openInterest")
                    sink.onTicker(v) { it.copy(oiBtc = oi) }
                } catch (e: Exception) {
                    sink.log(LogLevel.WARN, v, "open interest REST falhou: ${e.message}")
                }
                delay(15_000)
            }
        }
    }

    fun stop() = conns.forEach { it.stop() }

    private fun onDepth(text: String) {
        val (_, d) = Parse.binanceCombined(text) ?: return
        val ev = Parse.binanceDepth(d)
        var gap = false
        synchronized(lock) {
            when (sync.check(ev)) {
                BinanceSync.Action.BUFFER -> { buffer.add(ev); if (buffer.size > 10_000) buffer.removeAt(0) }
                BinanceSync.Action.DROP -> Unit
                BinanceSync.Action.APPLY -> book.apply(ev.bids, ev.asks, ev.lastU, ev.eventMs)
                BinanceSync.Action.GAP -> gap = true
            }
        }
        if (gap) resync("quebra de sequência (pu ≠ u anterior)")
    }

    private fun resync(reason: String) {
        synchronized(lock) {
            if (fetching) return
            fetching = true
            sync.reset(); buffer.clear()
            book.invalidate(reason)
        }
        sink.log(LogLevel.WARN, v, "Livro inválido, reconstruindo: $reason")
        scope.launch { loadSnapshot() }
    }

    private suspend fun loadSnapshot() {
        delay(500) // deixa eventos entrarem no buffer
        val j = try {
            Net.getJson("https://fapi.binance.com/fapi/v1/depth?symbol=BTCUSDT&limit=1000").o
        } catch (e: Exception) {
            val msg = "snapshot REST indisponível: ${e.message}"
            depthStatus.lastError = msg
            book.invalidate(msg)
            sink.log(LogLevel.ERROR, v, msg)
            delay(10_000)
            synchronized(lock) { fetching = false }
            resync("nova tentativa de snapshot")
            return
        }
        var gap = false
        synchronized(lock) {
            val id = j.lng("lastUpdateId")
            book.reset(Parse.levels(j.arr("bids")), Parse.levels(j.arr("asks")), id, 0)
            sync.onSnapshot(id)
            for (ev in buffer) {
                val a = sync.check(ev)
                if (a == BinanceSync.Action.APPLY) book.apply(ev.bids, ev.asks, ev.lastU, ev.eventMs)
                if (a == BinanceSync.Action.GAP) { gap = true; break }
            }
            buffer.clear()
            fetching = false
        }
        if (gap) resync("buffer sem continuidade com o snapshot")
        else sink.log(LogLevel.INFO, v, "Livro sincronizado (snapshot ${j.lng("lastUpdateId")})")
    }

    private fun onMarket(text: String) {
        val (stream, d) = Parse.binanceCombined(text) ?: return
        when {
            stream.endsWith("@aggTrade") -> sink.onTrade(Parse.binanceAggTrade(d))
            stream.contains("@markPrice") -> {
                val m = Parse.binanceMark(d)
                sink.onTicker(v) { it.copy(markPrice = m.markPrice, indexPrice = m.indexPrice, fundingRate = m.fundingRate, timeMs = m.timeMs) }
            }
            stream.endsWith("@forceOrder") -> Parse.binanceForceOrder(d)?.let { sink.onLiq(it) }
        }
    }
}

// =====================================================================
// BYBIT linear: uma conexão com livro 1000, RPI, trades, tickers e liquidações
// =====================================================================
class BybitFeed(private val sink: FeedSink, private val scope: CoroutineScope) {
    private val v = Venue.BYBIT
    private val book = sink.book(v)
    private val rpi = sink.bybitRpi
    private var ticker = DerivTicker()
    private lateinit var conn: WsConn
    private val topics = listOf("orderbook.1000.BTCUSDT", "orderbook.rpi.BTCUSDT", "publicTrade.BTCUSDT", "tickers.BTCUSDT", "allLiquidation.BTCUSDT")

    private fun op(op: String, args: List<String>) = """{"op":"$op","args":[${args.joinToString(",") { "\"$it\"" }}]}"""

    fun start() {
        conn = WsConn("wss://stream.bybit.com/v5/public/linear", sink.status(v, "Livro 1000 + RPI + trades + tickers + liquidações"), scope,
            { l, t -> sink.log(l, v, t) }, ping = """{"op":"ping"}""", pingEveryMs = 20_000,
            onOpen = {
                book.invalidate("aguardando snapshot"); rpi.invalidate("aguardando snapshot")
                it.send(op("subscribe", topics))
            }, onText = ::onText)
        conn.start()
    }

    fun stop() = conn.stop()

    private fun onText(text: String) {
        val j = Net.parse(text).o
        val topic = j.str("topic")
        when {
            topic.isEmpty() -> if (j.str("op") == "subscribe" && j.str("success") == "false")
                sink.log(LogLevel.ERROR, v, "inscrição recusada: ${j.str("ret_msg")}")
            topic.startsWith("orderbook.1000") -> applyBook(book, Parse.bybitBook(j), topic)
            topic.startsWith("orderbook.rpi") -> {
                val d = j.obj("data") ?: return
                applyBook(rpi, Parse.BybitBook(j.str("type") == "snapshot", d.lng("u"), d.lng("seq"),
                    Parse.bybitRpiLevels(d.arr("b")), Parse.bybitRpiLevels(d.arr("a")), j.lng("ts")), topic)
            }
            topic.startsWith("publicTrade") -> Parse.bybitTrades(j).forEach { sink.onTrade(it) }
            topic.startsWith("tickers") -> { ticker = Parse.bybitTicker(j, ticker); val t = ticker; sink.onTicker(v) { t } }
            topic.startsWith("allLiquidation") -> Parse.bybitLiquidations(j).forEach { sink.onLiq(it) }
        }
    }

    /** Snapshot (ou u=1) reconstrói; delta precisa ter u = anterior + 1 (validado com dados reais). */
    private fun applyBook(lb: LocalBook, m: Parse.BybitBook, topic: String) {
        if (m.snapshot || m.u == 1L) { lb.reset(m.bids, m.asks, m.u, m.eventMs); return }
        if (!lb.valid) return
        if (m.u != lb.lastSeq + 1) {
            lb.invalidate("quebra de sequência u ${lb.lastSeq} → ${m.u}")
            sink.log(LogLevel.WARN, v, "$topic: quebra de sequência, pedindo novo snapshot")
            conn.send(op("unsubscribe", listOf(topic)))
            conn.send(op("subscribe", listOf(topic)))
            return
        }
        lb.apply(m.bids, m.asks, m.u, m.eventMs)
    }
}

// =====================================================================
// OKX swap: livro 400 (seqId/prevSeqId), trades, OI, funding, mark, liquidações
// =====================================================================
class OkxFeed(private val sink: FeedSink, private val scope: CoroutineScope) {
    private val v = Venue.OKX
    private val book = sink.book(v)
    @Volatile var ctVal = 0.01; private set
    @Volatile var ctValSource = "padrão 0,01 BTC (metadados ainda não carregados)"; private set
    private lateinit var conn: WsConn
    private val booksArg = """{"channel":"books","instId":"BTC-USDT-SWAP"}"""
    private val args = listOf(booksArg,
        """{"channel":"trades","instId":"BTC-USDT-SWAP"}""",
        """{"channel":"open-interest","instId":"BTC-USDT-SWAP"}""",
        """{"channel":"funding-rate","instId":"BTC-USDT-SWAP"}""",
        """{"channel":"mark-price","instId":"BTC-USDT-SWAP"}""",
        """{"channel":"liquidation-orders","instType":"SWAP"}""")

    fun start() {
        scope.launch {
            try {
                val d = Net.getJson("https://www.okx.com/api/v5/public/instruments?instType=SWAP&instId=BTC-USDT-SWAP").o.arr("data").first().o
                if (d.str("ctValCcy") == "BTC" && d.dbl("ctVal") > 0) {
                    ctVal = d.dbl("ctVal")
                    ctValSource = "metadados OKX: 1 contrato = ${d.str("ctVal")} BTC"
                } else ctValSource = "metadados inesperados (${d.str("ctValCcy")}); usando 0,01 BTC"
            } catch (e: Exception) {
                sink.log(LogLevel.WARN, v, "metadados do instrumento indisponíveis: ${e.message}")
            }
        }
        conn = WsConn("wss://ws.okx.com:443/ws/v5/public", sink.status(v, "Livro 400 + trades + OI + funding + mark + liquidações"), scope,
            { l, t -> sink.log(l, v, t) }, ping = "ping", pingEveryMs = 25_000,
            onOpen = {
                book.invalidate("aguardando snapshot")
                it.send("""{"op":"subscribe","args":[${args.joinToString(",")}]}""")
            }, onText = ::onText)
        conn.start()
    }

    fun stop() = conn.stop()

    private fun onText(text: String) {
        if (text == "pong") return
        val j = Net.parse(text).o
        if (j.str("event") == "error") { sink.log(LogLevel.ERROR, v, "erro: ${j.str("msg")}"); return }
        val ch = j.obj("arg")?.str("channel") ?: return
        if (j["data"] == null) return
        when (ch) {
            "books" -> {
                val m = Parse.okxBook(j, ctVal)
                if (m.snapshot) { book.reset(m.bids, m.asks, m.seqId, m.eventMs); return }
                if (!book.valid) return
                if (m.prevSeqId != book.lastSeq) {
                    book.invalidate("quebra de sequência prevSeqId ${m.prevSeqId} ≠ ${book.lastSeq}")
                    sink.log(LogLevel.WARN, v, "books: quebra de sequência, pedindo novo snapshot")
                    conn.send("""{"op":"unsubscribe","args":[$booksArg]}""")
                    conn.send("""{"op":"subscribe","args":[$booksArg]}""")
                    return
                }
                book.apply(m.bids, m.asks, m.seqId, m.eventMs)
            }
            "trades" -> Parse.okxTrades(j, ctVal).forEach { sink.onTrade(it) }
            "open-interest" -> j.arr("data").firstOrNull()?.o?.let { d -> sink.onTicker(v) { it.copy(oiBtc = d.dbl("oiCcy"), timeMs = d.lng("ts")) } }
            "funding-rate" -> j.arr("data").firstOrNull()?.o?.let { d -> sink.onTicker(v) { it.copy(fundingRate = d.dbl("fundingRate")) } }
            "mark-price" -> j.arr("data").firstOrNull()?.o?.let { d -> sink.onTicker(v) { it.copy(markPrice = d.dbl("markPx"), timeMs = d.lng("ts")) } }
            "liquidation-orders" -> Parse.okxLiquidations(j, ctVal).forEach { sink.onLiq(it) }
        }
    }
}
