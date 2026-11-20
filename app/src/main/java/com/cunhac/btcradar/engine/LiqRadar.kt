package com.cunhac.btcradar.engine

import kotlin.math.floor
import kotlin.math.max

enum class LiqClass { SMALL, LARGE, EXTREME }

class LiqThresholds {
    @Volatile var large = 1.0
    @Volatile var extreme = 5.0
    fun classify(btc: Double) = when { btc >= extreme -> LiqClass.EXTREME; btc >= large -> LiqClass.LARGE; else -> LiqClass.SMALL }
}

val LIQ_PERIODS = listOf("1m" to 60_000L, "5m" to 300_000L, "15m" to 900_000L, "1h" to 3_600_000L, "24h" to 86_400_000L)

/** Grade tempo × preço (BTC liquidado por célula). Só eventos reportados pelas corretoras. */
class HeatGrid(
    val t0: Long, val tStep: Long, val cols: Int,
    val pMin: Double, val pStep: Double, val rows: Int,
    val longBtc: Array<DoubleArray>, val shortBtc: Array<DoubleArray>,
) {
    val max: Double = max(longBtc.maxOf { r -> r.maxOrNull() ?: 0.0 }, shortBtc.maxOf { r -> r.maxOrNull() ?: 0.0 })
}

data class LiqTotals(val longBtc: Double, val shortBtc: Double, val longCount: Int, val shortCount: Int, val byVenue: Map<Venue, Double>)

/**
 * Liquidações reais das últimas 24 h. Janelas usam o horário de RECEBIMENTO
 * (a OKX pode entregar eventos com horário de origem atrasado); o horário da corretora é preservado.
 */
class LiqRadar(val thresholds: LiqThresholds) {
    private val events = ArrayDeque<LiqEvent>()
    private val lock = Any()
    @Volatile var received = 0L; private set

    fun add(e: LiqEvent) = synchronized(lock) {
        events.addLast(e)
        received++
        val cutoff = e.recvMs - 86_400_000L
        while (events.isNotEmpty() && (events.first().recvMs < cutoff || events.size > 200_000)) events.removeFirst()
    }

    private fun inPeriod(periodMs: Long, now: Long) = events.asSequence().filter { now - it.recvMs <= periodMs }

    fun recent(n: Int = 200): List<LiqEvent> = synchronized(lock) {
        val out = ArrayList<LiqEvent>(n)
        var i = events.size - 1
        while (i >= 0 && out.size < n) { out += events[i]; i-- }
        out
    }

    fun top(periodMs: Long, side: LiqSide?, n: Int = 50, now: Long = System.currentTimeMillis()): List<LiqEvent> = synchronized(lock) {
        inPeriod(periodMs, now).filter { side == null || it.side == side }.sortedByDescending { it.qtyBtc }.take(n).toList()
    }

    fun totals(periodMs: Long, now: Long = System.currentTimeMillis()): LiqTotals = synchronized(lock) {
        var lb = 0.0; var sb = 0.0; var lc = 0; var sc = 0
        val byV = HashMap<Venue, Double>()
        for (e in inPeriod(periodMs, now)) {
            if (e.side == LiqSide.LONG) { lb += e.qtyBtc; lc++ } else { sb += e.qtyBtc; sc++ }
            byV[e.venue] = (byV[e.venue] ?: 0.0) + e.qtyBtc
        }
        LiqTotals(lb, sb, lc, sc, byV)
    }

    fun heatmap(periodMs: Long, cols: Int, rows: Int, pMin: Double, pMax: Double, now: Long = System.currentTimeMillis()): HeatGrid = synchronized(lock) {
        val tStep = max(1L, periodMs / cols)
        val t0 = now - periodMs
        val pStep = (pMax - pMin) / rows
        val lg = Array(rows) { DoubleArray(cols) }
        val sg = Array(rows) { DoubleArray(cols) }
        for (e in inPeriod(periodMs, now)) {
            val c = ((e.recvMs - t0) / tStep).toInt().coerceIn(0, cols - 1)
            val r = floor((e.price - pMin) / pStep).toInt()
            if (r !in 0 until rows) continue
            if (e.side == LiqSide.LONG) lg[r][c] += e.qtyBtc else sg[r][c] += e.qtyBtc
        }
        HeatGrid(t0, tStep, cols, pMin, pStep, rows, lg, sg)
    }
}
