package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.model.Candle
import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.data.net.arr
import com.cunhac.btcradar.data.net.dbl
import com.cunhac.btcradar.data.net.dblAt
import com.cunhac.btcradar.data.net.lng
import com.cunhac.btcradar.data.net.lngAt
import com.cunhac.btcradar.data.net.o
import com.cunhac.btcradar.data.net.obj
import com.cunhac.btcradar.data.net.str
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray

private const val MIN = 60_000L
private const val HOUR = 3_600_000L
private const val DAY = 86_400_000L

/** Períodos do heatmap (sem limite de plano). step = resolução de cada coluna. */
enum class HeatRange(val label: String, val ms: Long, val stepMs: Long) {
    H12("12h", 12 * HOUR, 5 * MIN), H24("24h", DAY, 5 * MIN), H48("48h", 2 * DAY, 15 * MIN), D3("3 dias", 3 * DAY, 15 * MIN),
    W1("1 sem", 7 * DAY, HOUR), W2("2 sem", 14 * DAY, HOUR), M1("1 mês", 30 * DAY, 4 * HOUR), M3("3 meses", 90 * DAY, 4 * HOUR),
    Y1("1 ano", 365 * DAY, DAY), Y2("2 anos", 730 * DAY, DAY);

    /** Histórico extra antes da janela, para o modelo já começar com posições abertas. */
    val warmupMs: Long get() = ms / 4
}

data class OiBtc(val timeMs: Long, val btc: Double)

/**
 * Histórico público (REST) para o heatmap. Tudo em BTC.
 * Limites das APIs: Binance guarda só ~30 dias de open interest; OKX e Bybit guardam anos no diário.
 */
object HistoryApi {
    private fun okxBar(step: Long) = when (step) { 5 * MIN -> "5m"; 15 * MIN -> "15m"; HOUR -> "1H"; 4 * HOUR -> "4H"; else -> "1D" }
    private fun bybitInterval(step: Long) = when (step) { 5 * MIN -> "5min"; 15 * MIN -> "15min"; HOUR -> "1h"; 4 * HOUR -> "4h"; else -> "1d" }
    private fun binancePeriod(step: Long) = when (step) { 5 * MIN -> "5m"; 15 * MIN -> "15m"; HOUR -> "1h"; 4 * HOUR -> "4h"; else -> "1d" }

    /** Velas da OKX (BTC-USDT-SWAP) como trajetória de preço comum. Páginas de 100, da mais nova para trás. */
    suspend fun candles(step: Long, fromMs: Long): List<Candle> {
        val out = ArrayList<Candle>()
        var after = System.currentTimeMillis() + step
        var pg = 0
        while (pg++ < 40) {
            val d = Net.getJson("https://www.okx.com/api/v5/market/history-candles?instId=BTC-USDT-SWAP&bar=${okxBar(step)}&limit=100&after=$after").o.arr("data")
            if (d.isEmpty()) break
            d.forEach { out += Candle(it.lngAt(0), it.dblAt(1), it.dblAt(2), it.dblAt(3), it.dblAt(4)) }
            after = d.last().lngAt(0)
            if (after <= fromMs) return out.filter { it.timeMs >= fromMs }.sortedBy { it.timeMs }
            delay(120)
        }
        return out.filter { it.timeMs >= fromMs }.sortedBy { it.timeMs }
    }

    /** OKX: [ts, contratos, oiCcy(BTC), oiUsd], páginas de 100 para trás via `end`. */
    suspend fun oiOkx(step: Long, fromMs: Long): List<OiBtc> {
        val out = ArrayList<OiBtc>()
        var end = System.currentTimeMillis() + step
        var pg = 0
        while (pg++ < 40) {
            val d = Net.getJson("https://www.okx.com/api/v5/rubik/stat/contracts/open-interest-history?instId=BTC-USDT-SWAP&period=${okxBar(step)}&limit=100&end=$end").o.arr("data")
            if (d.isEmpty()) break
            d.forEach { out += OiBtc(it.lngAt(0), it.dblAt(2)) }
            end = d.last().lngAt(0)
            if (end <= fromMs) break
            delay(450) // limite: 5 requisições / 2 s
        }
        return out.filter { it.timeMs >= fromMs }.distinctBy { it.timeMs }.sortedBy { it.timeMs }
    }

    /** Bybit: openInterest em BTC, páginas de 200 para trás via endTime. */
    suspend fun oiBybit(step: Long, fromMs: Long): List<OiBtc> {
        val out = ArrayList<OiBtc>()
        var end = System.currentTimeMillis()
        var pg = 0
        while (pg++ < 40) {
            val l = Net.getJson("https://api.bybit.com/v5/market/open-interest?category=linear&symbol=BTCUSDT&intervalTime=${bybitInterval(step)}&limit=200&endTime=$end")
                .o.obj("result")?.arr("list") ?: break
            if (l.isEmpty()) break
            l.forEach { out += OiBtc(it.o.lng("timestamp"), it.o.dbl("openInterest")) }
            val oldest = l.last().o.lng("timestamp")
            if (oldest <= fromMs || oldest >= end) break
            end = oldest - 1
            delay(120)
        }
        return out.filter { it.timeMs >= fromMs }.distinctBy { it.timeMs }.sortedBy { it.timeMs }
    }

    /** Binance: sumOpenInterest (BTC). A API só responde os últimos ~30 dias. */
    suspend fun oiBinance(step: Long, fromMs: Long): List<OiBtc> {
        val now = System.currentTimeMillis()
        var start = maxOf(fromMs, now - 29 * DAY)
        val out = ArrayList<OiBtc>()
        while (start < now) {
            val end = minOf(now, start + 499 * step)
            Net.getJson("https://fapi.binance.com/futures/data/openInterestHist?symbol=BTCUSDT&period=${binancePeriod(step)}&limit=500&startTime=$start&endTime=$end")
                .jsonArray.forEach { out += OiBtc(it.o.lng("timestamp"), it.o.dbl("sumOpenInterest")) }
            start = end + 1
            delay(150)
        }
        return out.distinctBy { it.timeMs }.sortedBy { it.timeMs }
    }

    /** Liquidez coberta por cada corretora no período pedido (texto para a tela). */
    fun coverage(v: Venue, pts: List<OiBtc>, fromMs: Long, error: String?): String = when {
        error != null -> if (error.contains("451") || error.contains("403")) "bloqueado nesta região" else "indisponível: ${error.take(60)}"
        pts.isEmpty() -> "sem dados no período"
        pts.first().timeMs > fromMs + (System.currentTimeMillis() - fromMs) / 10 ->
            "parcial: desde " + java.text.SimpleDateFormat("dd/MM HH:mm").format(java.util.Date(pts.first().timeMs)) + (if (v == Venue.BINANCE) " (API guarda ~30 dias)" else "")
        else -> "completo (${pts.size} pontos)"
    }
}
