package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.data.net.arr
import com.cunhac.btcradar.data.net.dbl
import com.cunhac.btcradar.data.net.lng
import com.cunhac.btcradar.data.net.o
import com.cunhac.btcradar.data.net.obj
import com.cunhac.btcradar.data.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Funções puras de decodificação. Cada corretora tem sua semântica própria;
 * nada aqui presume que campos com o mesmo nome significam a mesma coisa.
 * Formatos validados contra mensagens reais capturadas em 2026-11-20.
 */
object Parse {

    fun levels(a: JsonArray, mult: Double = 1.0): List<Level> = a.map {
        val x = it.jsonArray
        // OKX manda [preço, tamanho, "0", nº de ordens]; Binance e Bybit só [preço, tamanho].
        Level(x[0].jsonPrimitive.content.toDouble(), x[1].jsonPrimitive.content.toDouble() * mult,
            x.getOrNull(3)?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
    }

    // ---------------- Binance USD-M ----------------

    data class BinanceDepth(
        val firstU: Long, val lastU: Long, val prevU: Long,
        val bids: List<Level>, val asks: List<Level>, val eventMs: Long,
    )

    /** Mensagem do modo combinado: {"stream":..., "data":{...}} */
    fun binanceCombined(text: String): Pair<String, JsonObject>? {
        val j = Net.parse(text).o
        val d = j.obj("data") ?: return null
        return j.str("stream") to d
    }

    fun binanceDepth(d: JsonObject) = BinanceDepth(
        firstU = d.lng("U"), lastU = d.lng("u"), prevU = d.lng("pu"),
        bids = levels(d.arr("b")), asks = levels(d.arr("a")), eventMs = d.lng("E"),
    )

    /** aggTrade: m=true significa comprador é maker, logo o agressor foi o VENDEDOR. */
    fun binanceAggTrade(d: JsonObject): TradeEvent = TradeEvent(
        venue = Venue.BINANCE,
        price = d.dbl("p"),
        qtyBtc = d.dbl("q"),
        aggressor = if (d.str("m") == "true") Aggressor.SELL else Aggressor.BUY,
        timeMs = d.lng("T"),
    )

    fun binanceMark(d: JsonObject) = DerivTicker(
        markPrice = d.dbl("p"), indexPrice = d.dbl("i"), fundingRate = d.dbl("r"), timeMs = d.lng("E"),
    )

    /** forceOrder: ordem SELL forçada = posição LONG liquidada. Quantidade z (executada) em BTC. */
    fun binanceForceOrder(d: JsonObject): LiqEvent? {
        val o = d.obj("o") ?: return null
        if (o.str("s") != "BTCUSDT") return null
        val qty = o.dbl("z").takeIf { it > 0 } ?: o.dbl("q")
        val price = o.dbl("ap").takeIf { it > 0 } ?: o.dbl("p")
        return LiqEvent(Venue.BINANCE, "BTCUSDT", price, qty, qty, "BTC",
            if (o.str("S") == "SELL") LiqSide.LONG else LiqSide.SHORT, o.lng("T"), "btcusdt@forceOrder")
    }

    // ---------------- Bybit linear ----------------

    data class BybitBook(val snapshot: Boolean, val u: Long, val seq: Long, val bids: List<Level>, val asks: List<Level>, val eventMs: Long)

    fun bybitBook(j: JsonObject): BybitBook {
        val d = j.obj("data")!!
        return BybitBook(j.str("type") == "snapshot", d.lng("u"), d.lng("seq"), levels(d.arr("b")), levels(d.arr("a")), j.lng("ts"))
    }

    /** RPI: cada nível é [preço, qtd não-RPI, qtd RPI]. Guardamos só a parte RPI para não duplicar o livro orgânico. */
    fun bybitRpiLevels(a: JsonArray): List<Level> = a.map {
        val x = it.jsonArray
        Level(x[0].jsonPrimitive.content.toDouble(), x.getOrNull(2)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0)
    }

    /** publicTrade: S = lado do taker (agressor). v em BTC. */
    fun bybitTrades(j: JsonObject): List<TradeEvent> = j.arr("data").map {
        val d = it.o
        TradeEvent(Venue.BYBIT, d.dbl("p"), d.dbl("v"), if (d.str("S") == "Buy") Aggressor.BUY else Aggressor.SELL, d.lng("T"))
    }

    /** tickers: snapshot e deltas parciais. Campos ausentes ficam nulos. openInterest em BTC. */
    fun bybitTicker(j: JsonObject, prev: DerivTicker): DerivTicker {
        val d = j.obj("data") ?: return prev
        fun opt(k: String) = d.str(k).toDoubleOrNull()
        return DerivTicker(
            markPrice = opt("markPrice") ?: prev.markPrice,
            indexPrice = opt("indexPrice") ?: prev.indexPrice,
            fundingRate = opt("fundingRate") ?: prev.fundingRate,
            oiBtc = opt("openInterest") ?: prev.oiBtc,
            timeMs = j.lng("ts").takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }

    /** allLiquidation: S=Buy significa que uma posição LONG foi liquidada (documentação Bybit v5). */
    fun bybitLiquidations(j: JsonObject): List<LiqEvent> = j.arr("data").map {
        val d = it.o
        LiqEvent(Venue.BYBIT, d.str("s"), d.dbl("p"), d.dbl("v"), d.dbl("v"), "BTC",
            if (d.str("S") == "Buy") LiqSide.LONG else LiqSide.SHORT, d.lng("T"), "allLiquidation.BTCUSDT")
    }

    // ---------------- OKX swap ----------------

    data class OkxBook(val snapshot: Boolean, val seqId: Long, val prevSeqId: Long, val bids: List<Level>, val asks: List<Level>, val eventMs: Long)

    /** books: tamanhos em CONTRATOS; convertidos com ctVal (BTC por contrato) vindo dos metadados. */
    fun okxBook(j: JsonObject, ctVal: Double): OkxBook {
        val d = j.arr("data").first().o
        return OkxBook(j.str("action") == "snapshot", d.lng("seqId"), d.str("prevSeqId").toLongOrNull() ?: -1,
            levels(d.arr("bids"), ctVal), levels(d.arr("asks"), ctVal), d.lng("ts"))
    }

    /** trades: side = lado do taker. sz em contratos. */
    fun okxTrades(j: JsonObject, ctVal: Double): List<TradeEvent> = j.arr("data").map {
        val d = it.o
        TradeEvent(Venue.OKX, d.dbl("px"), d.dbl("sz") * ctVal, if (d.str("side") == "buy") Aggressor.BUY else Aggressor.SELL, d.lng("ts"))
    }

    /** liquidation-orders (SWAP, todos os instrumentos): filtramos BTC-USDT-SWAP. */
    fun okxLiquidations(j: JsonObject, ctVal: Double): List<LiqEvent> = j.arr("data")
        .map { it.o }
        .filter { it.str("instId") == "BTC-USDT-SWAP" }
        .flatMap { inst ->
            inst.arr("details").map {
                val d = it.o
                val side = when (d.str("posSide")) {
                    "long" -> LiqSide.LONG
                    "short" -> LiqSide.SHORT
                    else -> if (d.str("side") == "sell") LiqSide.LONG else LiqSide.SHORT
                }
                LiqEvent(Venue.OKX, "BTC-USDT-SWAP", d.dbl("bkPx"), d.dbl("sz") * ctVal, d.dbl("sz"), "contratos",
                    side, d.lng("ts"), "liquidation-orders")
            }
        }

    // ---------------- Gate, Bitget, Deribit (somente liquidações) ----------------
    // Formatos capturados ao vivo em 2026-11-21 e conferidos contra o REST de cada corretora.

    /** Gate futures.public_liquidates: size em contratos (1 = 0,0001 BTC); size < 0 = ordem de venda = LONG liquidado. */
    fun gateLiquidations(j: JsonObject, multiplier: Double): List<LiqEvent> {
        if (j.str("event") != "update") return emptyList()
        return j.arr("result").mapNotNull {
            val d = it.o
            if (d.str("contract") != "BTC_USDT") return@mapNotNull null
            val size = d.dbl("size")
            if (size == 0.0) return@mapNotNull null
            LiqEvent(Venue.GATE, "BTC_USDT", d.dbl("price"), kotlin.math.abs(size) * multiplier, kotlin.math.abs(size), "contratos",
                if (size < 0) LiqSide.LONG else LiqSide.SHORT, d.lng("time"), "futures.public_liquidates")
        }
    }

    /** Bitget liquidation (UTA v3): amount em USDT; side = lado da POSIÇÃO (sell = SHORT liquidado). */
    fun bitgetLiquidations(j: JsonObject): List<LiqEvent> = j.arr("data").mapNotNull {
        val d = it.o
        if (d.str("symbol") != "BTCUSDT") return@mapNotNull null
        val p = d.dbl("price"); val usd = d.dbl("amount")
        if (p <= 0 || usd <= 0) return@mapNotNull null
        LiqEvent(Venue.BITGET, "BTCUSDT", p, usd / p, usd, "USDT",
            if (d.str("side") == "sell") LiqSide.SHORT else LiqSide.LONG, d.lng("ts"), "liquidation")
    }

    /**
     * Deribit trades.BTC-PERPETUAL: trades com campo "liquidation" (M = lado passivo, T = agressor, MT = ambos).
     * amount em USD (contrato inverso). Agressor vendendo e liquidado = LONG; passivo de uma venda = comprador = SHORT.
     */
    fun deribitLiquidations(j: JsonObject): List<LiqEvent> {
        val data = j.obj("params")?.get("data") as? JsonArray ?: return emptyList()
        val out = ArrayList<LiqEvent>()
        for (e in data) {
            val d = e.o
            val liq = d.str("liquidation")
            if (liq.isEmpty()) continue
            val p = d.dbl("price"); val usd = d.dbl("amount")
            if (p <= 0 || usd <= 0) continue
            val takerSells = d.str("direction") == "sell"
            fun ev(side: LiqSide) = LiqEvent(Venue.DERIBIT, "BTC-PERPETUAL", p, usd / p, usd, "USD", side, d.lng("timestamp"), "trades.liquidation=$liq")
            if (liq.contains("T")) out += ev(if (takerSells) LiqSide.LONG else LiqSide.SHORT)
            if (liq.contains("M")) out += ev(if (takerSells) LiqSide.SHORT else LiqSide.LONG)
        }
        return out
    }
}

/**
 * Sincronização do livro diferencial da Binance (regras oficiais):
 * descarta eventos com u < lastUpdateId; o primeiro aplicado precisa ter U <= lastUpdateId <= u;
 * depois, cada evento precisa ter pu == u do anterior. Qualquer quebra = GAP (reconstruir).
 */
class BinanceSync {
    enum class Action { BUFFER, DROP, APPLY, GAP }

    var snapshotId = -1L; private set
    var synced = false; private set
    var lastU = -1L; private set

    fun reset() { snapshotId = -1; synced = false; lastU = -1 }
    fun onSnapshot(id: Long) { snapshotId = id; synced = false; lastU = -1 }

    fun check(ev: Parse.BinanceDepth): Action {
        if (snapshotId < 0) return Action.BUFFER
        if (ev.lastU < snapshotId) return Action.DROP
        if (!synced) {
            return if (ev.firstU <= snapshotId && ev.lastU >= snapshotId) {
                synced = true; lastU = ev.lastU; Action.APPLY
            } else Action.GAP
        }
        if (ev.prevU != lastU) return Action.GAP
        lastU = ev.lastU
        return Action.APPLY
    }
}
