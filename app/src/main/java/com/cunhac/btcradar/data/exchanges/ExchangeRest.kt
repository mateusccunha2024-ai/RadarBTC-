package com.cunhac.btcradar.data.exchanges

import com.cunhac.btcradar.data.model.BookLevel
import com.cunhac.btcradar.data.model.Candle
import com.cunhac.btcradar.data.model.Exchange
import com.cunhac.btcradar.data.model.MarketStats
import com.cunhac.btcradar.data.model.OiPoint
import com.cunhac.btcradar.data.model.OrderBookSnapshot
import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.data.net.arr
import com.cunhac.btcradar.data.net.dbl
import com.cunhac.btcradar.data.net.dblAt
import com.cunhac.btcradar.data.net.lng
import com.cunhac.btcradar.data.net.lngAt
import com.cunhac.btcradar.data.net.o
import com.cunhac.btcradar.data.net.obj
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

/**
 * Endpoints públicos (sem chave de API) dos futuros perpétuos BTC/USDT.
 * Todos os tamanhos são convertidos para BTC.
 */

private fun levels(a: JsonArray, contractBtc: Double = 1.0) =
    a.map { BookLevel(it.dblAt(0), it.dblAt(1) * contractBtc) }

object Binance {
    private const val BASE = "https://fapi.binance.com"

    suspend fun orderBook(): OrderBookSnapshot {
        val j = Net.getJson("$BASE/fapi/v1/depth?symbol=BTCUSDT&limit=1000").o
        return OrderBookSnapshot(Exchange.BINANCE, levels(j.arr("bids")), levels(j.arr("asks")))
    }

    /** Velas; interval ex.: "15m". Máx. 1500. */
    suspend fun klines(interval: String, limit: Int): List<Candle> =
        Net.getJson("$BASE/fapi/v1/klines?symbol=BTCUSDT&interval=$interval&limit=$limit").jsonArray.map {
            Candle(it.lngAt(0), it.dblAt(1), it.dblAt(2), it.dblAt(3), it.dblAt(4))
        }

    /** Histórico de open interest (Binance guarda só os últimos 30 dias, máx. 500 pontos). */
    suspend fun openInterestHist(period: String, limit: Int): List<OiPoint> =
        Net.getJson("$BASE/futures/data/openInterestHist?symbol=BTCUSDT&period=$period&limit=$limit")
            .jsonArray.map { OiPoint(it.o.lng("timestamp"), it.o.dbl("sumOpenInterestValue")) }

    suspend fun stats(): MarketStats = coroutineScope {
        val t = async { Net.getJson("$BASE/fapi/v1/ticker/24hr?symbol=BTCUSDT").o }
        val p = async { Net.getJson("$BASE/fapi/v1/premiumIndex?symbol=BTCUSDT").o }
        val oi = async { Net.getJson("$BASE/fapi/v1/openInterest?symbol=BTCUSDT").o }
        val ticker = t.await()
        MarketStats(
            price = ticker.dbl("lastPrice"),
            change24hPct = ticker.dbl("priceChangePercent"),
            fundingRate = p.await().dbl("lastFundingRate"),
            openInterestBtc = oi.await().dbl("openInterest"),
        )
    }
}

object Bybit {
    private const val BASE = "https://api.bybit.com"

    suspend fun orderBook(): OrderBookSnapshot {
        val r = Net.getJson("$BASE/v5/market/orderbook?category=linear&symbol=BTCUSDT&limit=500").o.obj("result")
            ?: error("Bybit: resposta sem 'result'")
        return OrderBookSnapshot(Exchange.BYBIT, levels(r.arr("b")), levels(r.arr("a")))
    }
}

object Okx {
    private const val BASE = "https://www.okx.com"
    /** 1 contrato BTC-USDT-SWAP = 0,01 BTC. */
    const val CONTRACT_BTC = 0.01

    suspend fun orderBook(): OrderBookSnapshot {
        val d = Net.getJson("$BASE/api/v5/market/books?instId=BTC-USDT-SWAP&sz=400").o.arr("data").first().o
        return OrderBookSnapshot(Exchange.OKX, levels(d.arr("bids"), CONTRACT_BTC), levels(d.arr("asks"), CONTRACT_BTC))
    }
}
