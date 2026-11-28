package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.net.Net
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Dados gratuitos para o fluxo de trade do assistente (os mesmos da skill):
 * ações dos EUA = gráfico do Yahoo (5 min, 60 dias; não oficial, pode atrasar);
 * BTC = candles de 5 min da Binance (spot BTCUSDT, 30 dias).
 */
object TraderData {
    data class Quote(val symbol: String, val price: Double?, val prevClose: Double?, val timeMs: Long, val name: String?)

    /** Candles de 5 min do Yahoo (só pregão regular) + cotação. */
    suspend fun yahoo5m(symbol: String, range: String = "60d"): Pair<List<Bar5>, Quote> {
        val s = symbol.uppercase().replace('.', '-')
        val text = runCatching { Net.getText("https://query2.finance.yahoo.com/v8/finance/chart/$s?range=$range&interval=5m&includePrePost=false") }
            .getOrElse { Net.getText("https://query1.finance.yahoo.com/v8/finance/chart/$s?range=$range&interval=5m&includePrePost=false") }
        return parseYahoo(text, s)
    }

    fun parseYahoo(text: String, symbol: String): Pair<List<Bar5>, Quote> {
        val res = Net.parse(text).jsonObject["chart"]!!.jsonObject["result"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("Yahoo não tem dados para $symbol")
        val meta = res["meta"]?.jsonObject
        val ts = res["timestamp"]?.jsonArray ?: JsonArray(emptyList())
        val qt = res["indicators"]?.jsonObject?.get("quote")?.jsonArray?.firstOrNull()?.jsonObject
        fun col(k: String) = qt?.get(k) as? JsonArray
        val o = col("open"); val h = col("high"); val l = col("low"); val c = col("close"); val v = col("volume")
        fun d(a: JsonArray?, i: Int) = (a?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
        val bars = ArrayList<Bar5>()
        for (i in ts.indices) {
            val t = (ts[i] as? JsonPrimitive)?.longOrNull ?: continue
            val oo = d(o, i); val hh = d(h, i); val ll = d(l, i); val cc = d(c, i)
            if (oo == null || hh == null || ll == null || cc == null) continue
            bars += Bar5(t * 1000, oo, hh, ll, cc, d(v, i) ?: 0.0)
        }
        val q = Quote(symbol, meta?.num("regularMarketPrice"), meta?.num("chartPreviousClose") ?: meta?.num("previousClose"),
            (meta?.get("regularMarketTime")?.jsonPrimitive?.longOrNull ?: 0L) * 1000,
            meta?.get("shortName")?.jsonPrimitive?.contentOrNull ?: meta?.get("longName")?.jsonPrimitive?.contentOrNull)
        return bars.sortedBy { it.t } to q
    }

    /** Fechamentos diários (scanner antes da abertura). */
    suspend fun yahooDaily(symbol: String): List<TraderSkill.Daily> {
        val s = symbol.uppercase().replace('.', '-')
        val text = Net.getText("https://query2.finance.yahoo.com/v8/finance/chart/$s?range=3mo&interval=1d")
        val res = Net.parse(text).jsonObject["chart"]!!.jsonObject["result"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val ts = res["timestamp"]?.jsonArray ?: return emptyList()
        val qt = res["indicators"]?.jsonObject?.get("quote")?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        fun d(k: String, i: Int) = ((qt[k] as? JsonArray)?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull
        return ts.indices.mapNotNull { i ->
            val t = (ts[i] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val c = d("close", i) ?: return@mapNotNull null
            TraderSkill.Daily(t * 1000, c, d("high", i) ?: c, d("low", i) ?: c)
        }
    }

    /** BTC: [days] dias de candles de 5 min da Binance (1.000 por pedido). */
    suspend fun btc5m(days: Int = 30, nowMs: Long = System.currentTimeMillis()): List<Bar5> = coroutineScope {
        val step = 1000L * 300_000L
        val start = (nowMs - days * 86_400_000L) / 300_000L * 300_000L
        val starts = generateSequence(start) { it + step }.takeWhile { it < nowMs }.toList()
        starts.map { st ->
            async {
                runCatching { parseBinance(Net.getText("https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=5m&limit=1000&startTime=$st")) }
                    .getOrElse { parseBinance(Net.getText("https://data-api.binance.vision/api/v3/klines?symbol=BTCUSDT&interval=5m&limit=1000&startTime=$st")) }
            }
        }.awaitAll().flatten().distinctBy { it.t }.sortedBy { it.t }
    }

    fun parseBinance(text: String): List<Bar5> = Net.parse(text).jsonArray.map { e ->
        val a = e.jsonArray
        fun d(i: Int) = a[i].jsonPrimitive.content.toDouble()
        Bar5(a[0].jsonPrimitive.content.toLong(), d(1), d(2), d(3), d(4), d(5))
    }

    /** Manchetes do Yahoo Finance por ticker (RSS). */
    suspend fun headlines(symbol: String, n: Int = 6): List<MacroSources.RawNews> {
        val s = if (symbol.equals("BTC", true)) "BTC-USD" else symbol.uppercase()
        val xml = Net.getText("https://feeds.finance.yahoo.com/rss/2.0/headline?s=$s&region=US&lang=en-US")
        return MacroSources.parseRss(xml, "Yahoo Finance", "Yahoo Finance").sortedByDescending { it.timeMs }.take(n)
    }

    /** Vários tickers em paralelo (scanner). */
    suspend fun <T> each(symbols: List<String>, f: suspend (String) -> T): List<Pair<String, Result<T>>> = coroutineScope {
        symbols.map { s -> async { s to runCatching { f(s) } } }.awaitAll()
    }

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
}
