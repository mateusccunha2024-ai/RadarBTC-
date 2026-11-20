package com.cunhac.btcradar.domain

import com.cunhac.btcradar.data.model.Candle
import com.cunhac.btcradar.data.model.OiPoint
import com.cunhac.btcradar.data.model.OrderBookSnapshot
import com.cunhac.btcradar.data.model.PriceBin
import kotlin.math.floor
import kotlin.math.max

/**
 * Estimativa de onde estão as liquidações ainda "vivas" (o mesmo princípio
 * dos mapas de liquidação tipo CoinGlass — nenhuma corretora publica isso):
 *
 * 1. Quando o open interest SOBE num candle, entraram posições novas naquele preço.
 * 2. Distribuímos esse valor entre alavancagens típicas (10x, 25x, 50x, 100x)
 *    e calculamos o preço de liquidação de longs (abaixo) e shorts (acima).
 * 3. Se o preço já passou por um nível depois, ele já foi liquidado e sai do mapa.
 * 4. Quando o OI CAI, reduzimos todos os níveis proporcionalmente (posições fechadas).
 *
 * O que sobra são os "ímãs": regiões com muita liquidez parada que tendem a atrair o preço.
 */
object LiquidityMap {

    data class Tier(val leverage: Double, val weight: Double)

    val defaultTiers = listOf(Tier(10.0, 0.30), Tier(25.0, 0.30), Tier(50.0, 0.25), Tier(100.0, 0.15))

    /** Margem de manutenção aproximada. */
    private const val MMR = 0.004

    data class Level(val price: Double, val isLong: Boolean, var usd: Double)

    fun estimateLevels(
        candles: List<Candle>,
        oi: List<OiPoint>,
        tiers: List<Tier> = defaultTiers,
    ): List<Level> {
        val oiSorted = oi.sortedBy { it.timeMs }
        val active = mutableListOf<Level>()
        var prevOi: Double? = null

        for (c in candles.sortedBy { it.timeMs }) {
            // 1) o candle atual liquida o que ele atravessou
            active.removeAll { (it.isLong && c.low <= it.price) || (!it.isLong && c.high >= it.price) }

            // 2) variação de OI neste candle
            val cur = oiAt(oiSorted, c.timeMs) ?: continue
            val prev = prevOi
            prevOi = cur
            if (prev == null) continue
            val delta = cur - prev

            if (delta > 0) {
                val entry = (c.high + c.low + c.close) / 3
                for (t in tiers) {
                    val usd = delta * t.weight // cada contrato novo tem um long e um short
                    active += Level(entry * (1 - 1 / t.leverage + MMR), true, usd)
                    active += Level(entry * (1 + 1 / t.leverage - MMR), false, usd)
                }
            } else if (delta < 0) {
                val total = active.sumOf { it.usd } / 2 // OI conta um lado só
                if (total > 0) {
                    val f = max(0.0, 1 + delta / total)
                    active.forEach { it.usd *= f }
                }
            }
        }
        return active
    }

    /** Último ponto de OI com timestamp <= t. */
    private fun oiAt(sorted: List<OiPoint>, t: Long): Double? {
        var lo = 0
        var hi = sorted.size - 1
        var ans: Double? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].timeMs <= t) { ans = sorted[mid].oiUsd; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** Tamanho de faixa "redondo" para ~[rows] linhas no intervalo escolhido. */
    fun niceBinSize(price: Double, rangePct: Double, rows: Int = 40): Double {
        val raw = price * rangePct * 2 / rows
        val steps = listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0)
        return steps.firstOrNull { it >= raw } ?: steps.last()
    }

    /**
     * Junta liquidações estimadas + paredes do book em faixas de preço,
     * ordenadas do preço mais alto para o mais baixo.
     */
    fun buildBins(
        price: Double,
        rangePct: Double,
        levels: List<Level>,
        books: List<OrderBookSnapshot>,
    ): List<PriceBin> {
        val size = niceBinSize(price, rangePct)
        val start = floor(price * (1 - rangePct) / size) * size
        val end = price * (1 + rangePct)
        val n = ((end - start) / size).toInt() + 1

        val longL = DoubleArray(n); val shortL = DoubleArray(n)
        val bid = DoubleArray(n); val ask = DoubleArray(n)
        fun idx(p: Double): Int? = ((p - start) / size).toInt().takeIf { p >= start && it in 0 until n }

        levels.forEach { l -> idx(l.price)?.let { if (l.isLong) longL[it] += l.usd else shortL[it] += l.usd } }
        books.forEach { b ->
            b.bids.forEach { lv -> idx(lv.price)?.let { bid[it] += lv.price * lv.sizeBtc } }
            b.asks.forEach { lv -> idx(lv.price)?.let { ask[it] += lv.price * lv.sizeBtc } }
        }

        return (0 until n).map { i ->
            PriceBin(start + i * size, start + (i + 1) * size, longL[i], shortL[i], bid[i], ask[i])
        }.reversed()
    }

    data class Magnet(val bin: PriceBin, val distancePct: Double, val above: Boolean)

    /** Maior aglomerado de liquidações acima e abaixo do preço atual. */
    fun magnets(price: Double, bins: List<PriceBin>): List<Magnet> {
        val up = bins.filter { it.low > price }.maxByOrNull { it.liqUsd }
        val down = bins.filter { it.high < price }.maxByOrNull { it.liqUsd }
        return listOfNotNull(
            up?.takeIf { it.liqUsd > 0 }?.let { Magnet(it, (it.mid / price - 1) * 100, true) },
            down?.takeIf { it.liqUsd > 0 }?.let { Magnet(it, (it.mid / price - 1) * 100, false) },
        )
    }
}
