package com.cunhac.btcradar.engine

import java.util.TreeMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Uma vela do footprint: OHLC dos trades e volume agressor por faixa de preço. */
class FootCandle(val startMs: Long, val open: Double, val high: Double, val low: Double, val close: Double,
                 /** preço inicial da faixa → [compra agressora, venda agressora] em BTC */
                 val cells: TreeMap<Double, DoubleArray>) {
    val buy: Double get() = cells.values.sumOf { it[0] }
    val sell: Double get() = cells.values.sumOf { it[1] }
    val delta: Double get() = buy - sell
    val volume: Double get() = buy + sell
    /** Faixa com mais volume na vela. */
    val poc: Double? get() = cells.maxByOrNull { it.value[0] + it.value[1] }?.key
}

data class ProfileRow(val price: Double, val buy: Double, val sell: Double) { val total get() = buy + sell }

/** Perfil de volume: POC = faixa de maior volume; VAH/VAL = limites da área com 70% do volume. */
data class VolumeProfile(val rows: List<ProfileRow>, val poc: Double?, val vah: Double?, val val_: Double?, val total: Double)

/**
 * Agregação compacta dos trades das corretoras principais para footprint e perfil de volume.
 * Guarda 24 h em baldes de 1 minuto × US$ 5 por corretora, para não depender da fita de 1 h.
 */
class FootprintStore(private val keepMs: Long = 86_400_000L) {
    companion object { const val BASE_STEP = 5.0 }

    private class Minute {
        var open = 0.0; var high = 0.0; var low = Double.MAX_VALUE; var close = 0.0
        /** faixa de US$ 5 → [corretora*2 + lado] */
        val cells = HashMap<Long, DoubleArray>()
    }

    private val minutes = TreeMap<Long, Minute>()
    private val lock = Any()

    fun add(t: TradeEvent) {
        if (!t.venue.core) return
        synchronized(lock) {
            val m = minutes.getOrPut(t.recvMs / 60_000 * 60_000) { Minute().also { it.open = t.price } }
            m.high = max(m.high, t.price); m.low = min(m.low, t.price); m.close = t.price
            val k = floor(t.price / BASE_STEP).toLong()
            m.cells.getOrPut(k) { DoubleArray(CORE_VENUES.size * 2) }[t.venue.ordinal * 2 + if (t.aggressor == Aggressor.BUY) 0 else 1] += t.qtyBtc
            val cutoff = t.recvMs - keepMs
            while (minutes.isNotEmpty() && minutes.firstKey() < cutoff) minutes.pollFirstEntry()
        }
    }

    fun oldestMs(): Long? = synchronized(lock) { minutes.firstEntry()?.key }

    /**
     * Velas de [candleMs] (múltiplo de 1 min) com faixas de [step] (múltiplo de US$ 5), das mais antigas às mais novas.
     */
    fun candles(candleMs: Long, step: Double, count: Int, venues: Set<Venue>, now: Long = System.currentTimeMillis()): List<FootCandle> =
        synchronized(lock) {
            val endStart = now / candleMs * candleMs
            val firstStart = endStart - (count - 1) * candleMs
            val out = ArrayList<FootCandle>()
            var start = firstStart
            while (start <= endStart) {
                val mins = minutes.subMap(start, true, start + candleMs, false).values
                if (mins.isNotEmpty()) {
                    val cells = TreeMap<Double, DoubleArray>()
                    for (m in mins) for ((k, arr) in m.cells) {
                        val p = floor(k * BASE_STEP / step) * step
                        val c = cells.getOrPut(p) { DoubleArray(2) }
                        for (v in venues) if (v.core) { c[0] += arr[v.ordinal * 2]; c[1] += arr[v.ordinal * 2 + 1] }
                    }
                    cells.entries.removeIf { it.value[0] + it.value[1] <= 0 }
                    if (cells.isNotEmpty()) out += FootCandle(start, mins.first().open, mins.maxOf { it.high }, mins.minOf { it.low }, mins.last().close, cells)
                }
                start += candleMs
            }
            out
        }

    fun profile(windowMs: Long, step: Double, venues: Set<Venue>, now: Long = System.currentTimeMillis()): VolumeProfile = synchronized(lock) {
        val acc = TreeMap<Double, DoubleArray>()
        for (m in minutes.tailMap(now - windowMs, true).values) for ((k, arr) in m.cells) {
            val c = acc.getOrPut(floor(k * BASE_STEP / step) * step) { DoubleArray(2) }
            for (v in venues) if (v.core) { c[0] += arr[v.ordinal * 2]; c[1] += arr[v.ordinal * 2 + 1] }
        }
        buildProfile(acc.map { ProfileRow(it.key, it.value[0], it.value[1]) }.filter { it.total > 0 })
    }
}

/** Calcula POC e área de valor (70%) expandindo a partir do POC para o lado de maior volume. */
fun buildProfile(rowsAsc: List<ProfileRow>, valueArea: Double = 0.70): VolumeProfile {
    if (rowsAsc.isEmpty()) return VolumeProfile(emptyList(), null, null, null, 0.0)
    val total = rowsAsc.sumOf { it.total }
    val pocIdx = rowsAsc.indices.maxByOrNull { rowsAsc[it].total }!!
    var lo = pocIdx; var hi = pocIdx
    var acc = rowsAsc[pocIdx].total
    while (acc < total * valueArea && (lo > 0 || hi < rowsAsc.size - 1)) {
        val up = if (hi < rowsAsc.size - 1) rowsAsc[hi + 1].total else -1.0
        val down = if (lo > 0) rowsAsc[lo - 1].total else -1.0
        if (up >= down) { hi++; acc += up } else { lo--; acc += down }
    }
    return VolumeProfile(rowsAsc, rowsAsc[pocIdx].price, rowsAsc[hi].price, rowsAsc[lo].price, total)
}
