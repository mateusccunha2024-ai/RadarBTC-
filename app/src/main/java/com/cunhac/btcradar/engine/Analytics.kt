package com.cunhac.btcradar.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class DivKind(val label: String, val bullish: Boolean) {
    BULLISH("DIVERGÊNCIA DE ALTA", true),   // preço faz fundo mais baixo, CVD faz fundo mais alto
    BEARISH("DIVERGÊNCIA DE BAIXA", false), // preço faz topo mais alto, CVD faz topo mais baixo
}

data class Divergence(
    val kind: DivKind, val windowMs: Long, val detectedMs: Long,
    val price1: Double, val price2: Double, val cvd1: Double, val cvd2: Double,
) {
    val text: String get() = when (kind) {
        DivKind.BULLISH -> "Preço: fundo mais baixo (%,.1f → %,.1f). CVD: fundo mais alto (%+.2f → %+.2f BTC). Vendas agressoras perdendo força.".format(PT_BR, price1, price2, cvd1, cvd2)
        DivKind.BEARISH -> "Preço: topo mais alto (%,.1f → %,.1f). CVD: topo mais baixo (%+.2f → %+.2f BTC). Compras agressoras perdendo força.".format(PT_BR, price1, price2, cvd1, cvd2)
    }
}

/** Um minuto do painel de correlação. */
data class FlowBucket(
    val startMs: Long, val priceClose: Double?, val priceChgPct: Double?, val cvdDeltaBtc: Double,
    val oiChgBtc: Double?, val liqLongBtc: Double, val liqShortBtc: Double,
) { val liqNetBtc get() = liqShortBtc - liqLongBtc }

object Analytics {
    /**
     * Compara as duas metades da janela. Precisa de variação mínima de preço (0,05%) e de CVD
     * (1 BTC ou 5% da amplitude do CVD) para não marcar ruído como divergência.
     */
    fun divergence(prices: List<Pair<Long, Double>>, cvd: List<Pair<Long, Double>>, windowMs: Long, now: Long = System.currentTimeMillis()): Divergence? {
        if (prices.size < 20 || cvd.size < 20) return null
        val mid = now - windowMs / 2
        val p1 = prices.filter { it.first < mid }.map { it.second }; val p2 = prices.filter { it.first >= mid }.map { it.second }
        val c1 = cvd.filter { it.first < mid }.map { it.second }; val c2 = cvd.filter { it.first >= mid }.map { it.second }
        if (p1.size < 5 || p2.size < 5 || c1.size < 5 || c2.size < 5) return null
        val cRange = (c1 + c2).let { it.max() - it.min() }
        val minC = max(1.0, cRange * 0.05)
        val pl1 = p1.min(); val pl2 = p2.min(); val ph1 = p1.max(); val ph2 = p2.max()
        val cl1 = c1.min(); val cl2 = c2.min(); val ch1 = c1.max(); val ch2 = c2.max()
        if (pl2 < pl1 * (1 - 0.0005) && cl2 > cl1 + minC) return Divergence(DivKind.BULLISH, windowMs, now, pl1, pl2, cl1, cl2)
        if (ph2 > ph1 * (1 + 0.0005) && ch2 < ch1 - minC) return Divergence(DivKind.BEARISH, windowMs, now, ph1, ph2, ch1, ch2)
        return null
    }

    /** CVD em % do open interest somado (comparável entre dias com OI diferente). */
    fun normalizedCvd(cvdBtc: Double, oiBtc: Double?): Double? = if (oiBtc == null || oiBtc <= 0) null else cvdBtc / oiBtc * 100

    fun buckets(samples: List<Sample>, cvdPerSec: List<Pair<Long, Double>>, liqs: List<LiqEvent>, from: Long, stepMs: Long, n: Int): List<FlowBucket> {
        val out = ArrayList<FlowBucket>(n)
        var prevClose: Double? = samples.lastOrNull { it.tMs < from }?.price
        var prevOi: Double? = samples.lastOrNull { it.tMs < from }?.oiBtc
        for (i in 0 until n) {
            val a = from + i * stepMs; val b = a + stepMs
            val ss = samples.filter { it.tMs in a until b }
            val close = ss.lastOrNull { it.price != null }?.price
            val oi = ss.lastOrNull { it.oiBtc != null }?.oiBtc
            val open = prevClose ?: ss.firstOrNull { it.price != null }?.price
            val cd = cvdPerSec.filter { it.first in a until b }.sumOf { it.second }
            var ll = 0.0; var ls = 0.0
            for (e in liqs) if (e.recvMs in a until b) { if (e.side == LiqSide.LONG) ll += e.qtyBtc else ls += e.qtyBtc }
            out += FlowBucket(a, close, if (open != null && close != null && open > 0) (close / open - 1) * 100 else null, cd,
                if (oi != null && prevOi != null) oi - prevOi else null, ll, ls)
            if (close != null) prevClose = close
            if (oi != null) prevOi = oi
        }
        return out
    }

    /** Correlação de Pearson; null com menos de 8 pares ou série constante. */
    fun pearson(x: List<Double?>, y: List<Double?>): Double? {
        val pairs = x.zip(y).mapNotNull { (a, b) -> if (a != null && b != null) a to b else null }
        if (pairs.size < 8) return null
        val mx = pairs.sumOf { it.first } / pairs.size; val my = pairs.sumOf { it.second } / pairs.size
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for ((a, b) in pairs) { sxy += (a - mx) * (b - my); sxx += (a - mx) * (a - mx); syy += (b - my) * (b - my) }
        if (sxx < 1e-12 || syy < 1e-12) return null
        return sxy / sqrt(sxx * syy)
    }

    fun corrLabel(r: Double?): String = when {
        r == null -> "sem dados"
        abs(r) >= 0.7 -> if (r > 0) "forte positiva" else "forte negativa"
        abs(r) >= 0.4 -> if (r > 0) "moderada positiva" else "moderada negativa"
        abs(r) >= 0.2 -> if (r > 0) "fraca positiva" else "fraca negativa"
        else -> "nenhuma"
    }
}
