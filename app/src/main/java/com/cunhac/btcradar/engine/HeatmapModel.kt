package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.model.Candle
import kotlin.math.floor
import kotlin.math.max

class LiqLevel(val price: Double, val isLong: Boolean, var btc: Double, val tier: Int = 0, val entry: Double = price)

/** Alavancagens simuladas pelo modelo (índice usado nos filtros). */
val HEAT_TIERS = listOf("10x", "25x", "50x", "100x")

/** Grade tempo × preço de liquidações ESTIMADAS por corretora (BTC por célula). */
class HeatData(
    val range: HeatRange,
    val candles: List<Candle>,
    val t0: Long,
    val stepMs: Long,
    val cols: Int,
    val pMin: Double,
    val pStep: Double,
    val rows: Int,
    val longGrid: Map<Venue, Array<FloatArray>>,
    val shortGrid: Map<Venue, Array<FloatArray>>,
    /** Mesmas grades separadas por alavancagem: [tier][linha][coluna]. */
    val longTier: Map<Venue, Array<Array<FloatArray>>>,
    val shortTier: Map<Venue, Array<Array<FloatArray>>>,
    /** Open interest estimado por PREÇO DE ENTRADA (posições ainda abertas no modelo). */
    val entryGrid: Map<Venue, Array<FloatArray>>,
    val finalLevels: Map<Venue, List<LiqLevel>>,
    val coverage: Map<Venue, String>,
    val builtAt: Long,
)

data class KeyLevel(val price: Double, val btc: Double, val distancePct: Double)

data class HeatSummary(
    val above: List<KeyLevel>,   // liquidações de SHORT acima do preço
    val below: List<KeyLevel>,   // liquidações de LONG abaixo do preço
    val shortsAboveBtc: Double,
    val longsBelowBtc: Double,
)

/**
 * Mesmo princípio dos heatmaps de liquidação (CoinGlass e afins): quando o open interest
 * sobe numa vela, simulamos posições 10x/25x/50x/100x abertas ao preço típico e marcamos onde
 * seriam liquidadas; níveis atravessados pelo preço saem; queda de OI reduz tudo proporcionalmente.
 * Cada corretora usa o próprio open interest; a trajetória de preço é comum.
 */
object HeatmapModel {
    private val TIERS = listOf(10.0 to 0.30, 25.0 to 0.30, 50.0 to 0.25, 100.0 to 0.15)
    private const val MMR = 0.004

    fun build(range: HeatRange, candlesAll: List<Candle>, oi: Map<Venue, List<OiBtc>>, coverage: Map<Venue, String>,
              now: Long = System.currentTimeMillis(), rows: Int = 180): HeatData? {
        val from = now - range.ms
        val all = candlesAll.sortedBy { it.timeMs }
        val win = all.filter { it.timeMs >= from }
        if (win.size < 2) return null
        val lo = win.minOf { it.low } * 0.90
        val hi = win.maxOf { it.high } * 1.10
        val pStep = (hi - lo) / rows
        val cols = win.size
        val longG = HashMap<Venue, Array<FloatArray>>()
        val shortG = HashMap<Venue, Array<FloatArray>>()
        val longT = HashMap<Venue, Array<Array<FloatArray>>>()
        val shortT = HashMap<Venue, Array<Array<FloatArray>>>()
        val entryG = HashMap<Venue, Array<FloatArray>>()
        val finals = HashMap<Venue, List<LiqLevel>>()

        for ((v, ptsRaw) in oi) {
            if (ptsRaw.size < 2) continue
            val pts = ptsRaw.sortedBy { it.timeMs }
            val lg = Array(rows) { FloatArray(cols) }
            val sg = Array(rows) { FloatArray(cols) }
            val lt = Array(TIERS.size) { Array(rows) { FloatArray(cols) } }
            val st = Array(TIERS.size) { Array(rows) { FloatArray(cols) } }
            val eg = Array(rows) { FloatArray(cols) }
            val active = ArrayList<LiqLevel>()
            var prev: Double? = null
            var col = -1
            var idx = 0
            for (c in all) {
                active.removeAll { (it.isLong && c.low <= it.price) || (!it.isLong && c.high >= it.price) }
                while (idx + 1 < pts.size && pts[idx + 1].timeMs <= c.timeMs) idx++
                val cur = if (pts[idx].timeMs <= c.timeMs) pts[idx].btc else null
                if (cur != null) {
                    val p = prev
                    prev = cur
                    if (p != null) {
                        val delta = cur - p
                        if (delta > 0) {
                            val entry = (c.high + c.low + c.close) / 3
                            TIERS.forEachIndexed { ti, (lev, w) ->
                                active += LiqLevel(entry * (1 - 1 / lev + MMR), true, delta * w, ti, entry)
                                active += LiqLevel(entry * (1 + 1 / lev - MMR), false, delta * w, ti, entry)
                            }
                        } else if (delta < 0) {
                            val total = active.sumOf { it.btc } / 2
                            if (total > 0) { val f = max(0.0, 1 + delta / total); active.forEach { it.btc *= f } }
                        }
                    }
                }
                if (c.timeMs >= from) {
                    col++
                    for (l in active) {
                        val r = floor((l.price - lo) / pStep).toInt()
                        if (r in 0 until rows) {
                            if (l.isLong) { lg[r][col] += l.btc.toFloat(); lt[l.tier][r][col] += l.btc.toFloat() }
                            else { sg[r][col] += l.btc.toFloat(); st[l.tier][r][col] += l.btc.toFloat() }
                        }
                        val re = floor((l.entry - lo) / pStep).toInt()
                        if (re in 0 until rows) eg[re][col] += l.btc.toFloat()
                    }
                }
            }
            longG[v] = lg; shortG[v] = sg; longT[v] = lt; shortT[v] = st; entryG[v] = eg
            finals[v] = active.map { LiqLevel(it.price, it.isLong, it.btc, it.tier, it.entry) }
        }
        return HeatData(range, win, win.first().timeMs, range.stepMs, cols, lo, pStep, rows, longG, shortG, longT, shortT, entryG, finals, coverage, now)
    }

    /** Soma as grades das corretoras e alavancagens escolhidas. */
    fun combine(d: HeatData, venues: Set<Venue>, tiers: Set<Int> = HEAT_TIERS.indices.toSet()): Pair<Array<FloatArray>, Array<FloatArray>> {
        val l = Array(d.rows) { FloatArray(d.cols) }
        val s = Array(d.rows) { FloatArray(d.cols) }
        fun addAll(dst: Array<FloatArray>, g: Array<FloatArray>) { for (r in 0 until d.rows) for (c in 0 until d.cols) dst[r][c] += g[r][c] }
        for (v in venues) {
            if (tiers.size == HEAT_TIERS.size) {
                d.longGrid[v]?.let { addAll(l, it) }; d.shortGrid[v]?.let { addAll(s, it) }
            } else for (t in tiers) {
                d.longTier[v]?.getOrNull(t)?.let { addAll(l, it) }; d.shortTier[v]?.getOrNull(t)?.let { addAll(s, it) }
            }
        }
        return l to s
    }

    /** Open interest estimado por preço de entrada, somado nas corretoras escolhidas. */
    fun entries(d: HeatData, venues: Set<Venue>): Array<FloatArray> {
        val e = Array(d.rows) { FloatArray(d.cols) }
        for (v in venues) d.entryGrid[v]?.let { g -> for (r in 0 until d.rows) for (c in 0 until d.cols) e[r][c] += g[r][c] }
        return e
    }

    /** Por coluna: níveis LONG em aberto − níveis SHORT em aberto (BTC). Positivo = mais longs expostos. */
    fun levelDelta(g: Pair<Array<FloatArray>, Array<FloatArray>>): FloatArray {
        val cols = g.first.firstOrNull()?.size ?: 0
        val out = FloatArray(cols)
        for (r in g.first.indices) for (c in 0 until cols) out[c] += g.first[r][c] - g.second[r][c]
        return out
    }

    /** Níveis-chave no fim da janela, agrupados em faixas de [binUsd]. */
    fun summary(d: HeatData, venues: Set<Venue>, price: Double, binUsd: Double, n: Int = 6, tiers: Set<Int> = HEAT_TIERS.indices.toSet()): HeatSummary {
        val above = HashMap<Double, Double>(); val below = HashMap<Double, Double>()
        var sa = 0.0; var lb = 0.0
        for (v in venues) d.finalLevels[v]?.forEach { l ->
            if (l.tier !in tiers) return@forEach
            val k = floor(l.price / binUsd) * binUsd + binUsd / 2
            if (!l.isLong && l.price > price) { above[k] = (above[k] ?: 0.0) + l.btc; sa += l.btc }
            if (l.isLong && l.price < price) { below[k] = (below[k] ?: 0.0) + l.btc; lb += l.btc }
        }
        val biggest = (above.values + below.values).maxOrNull() ?: 0.0
        fun top(m: Map<Double, Double>) = m.entries.filter { it.value >= biggest * 0.05 }.sortedByDescending { it.value }.take(n)
            .map { KeyLevel(it.key, it.value, (it.key / price - 1) * 100) }.sortedByDescending { it.price }
        return HeatSummary(top(above), top(below), sa, lb)
    }
}
