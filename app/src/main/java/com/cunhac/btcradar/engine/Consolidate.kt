package com.cunhac.btcradar.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/** Linha do livro consolidado. byVenue: índices 0..2 = Venue.ordinal, 3 = Bybit RPI. */
class ConsRow(val price: Double, val side: BookSide, val byVenue: DoubleArray) {
    val total: Double get() = byVenue.sum()
    var cumulative = 0.0
    /** Nº de ordens da OKX no nível (única que publica). */
    var okxOrders = 0
}

data class ConsBook(
    val asks: List<ConsRow>,    // do mais próximo para o mais distante
    val bids: List<ConsRow>,    // do mais próximo para o mais distante
    val maxRow: Double,
    val bidSum: Double,
    val askSum: Double,
    val biggestBid: ConsRow?,
    val biggestAsk: ConsRow?,
    val missing: List<Venue>,   // corretoras fora da consolidação (livro inválido)
) {
    val imbalance: Double get() = if (bidSum + askSum > 0) (bidSum - askSum) / (bidSum + askSum) else 0.0
}

object Consolidate {
    /**
     * @param granularity 0 = preços exatamente iguais; >0 = agrupa em faixas desse tamanho (USDT)
     * Nenhuma liquidez é descartada por diferença de tick: com agrupamento, tudo cai em alguma faixa.
     */
    fun build(
        views: Collection<BookView>,
        rpi: BookView?,
        granularity: Double,
        levels: Int,
        minBtc: Double,
        venues: Set<Venue>,
        includeRpi: Boolean,
    ): ConsBook {
        val use = views.filter { it.venue in venues && it.valid }
        val missing = views.filter { it.venue in venues && !it.valid }.map { it.venue }
        // Ao agrupar, compras arredondam para baixo e vendas para cima: o spread nunca some na faixa.
        fun key(p: Double, ask: Boolean): Double = when {
            granularity <= 0 -> (p * 100).roundToLong() / 100.0
            ask -> ceil(p / granularity) * granularity
            else -> floor(p / granularity) * granularity
        }
        val ordMap = HashMap<Pair<Boolean, Double>, Int>()

        val bidMap = HashMap<Double, DoubleArray>()
        val askMap = HashMap<Double, DoubleArray>()
        fun add(map: HashMap<Double, DoubleArray>, lv: Level, idx: Int) {
            val ask = map === askMap
            val k = key(lv.price, ask)
            map.getOrPut(k) { DoubleArray(4) }[idx] += lv.qtyBtc
            if (idx == Venue.OKX.ordinal && lv.orders > 0) ordMap[ask to k] = (ordMap[ask to k] ?: 0) + lv.orders
        }
        for (v in use) {
            v.bids.forEach { add(bidMap, it, v.venue.ordinal) }
            v.asks.forEach { add(askMap, it, v.venue.ordinal) }
        }
        if (includeRpi && rpi != null && rpi.valid && Venue.BYBIT in venues) {
            rpi.bids.forEach { add(bidMap, it, 3) }
            rpi.asks.forEach { add(askMap, it, 3) }
        }
        val bids = bidMap.entries.sortedByDescending { it.key }.map { ConsRow(it.key, BookSide.BID, it.value).also { r -> r.okxOrders = ordMap[false to it.key] ?: 0 } }
            .filter { it.total >= minBtc }.take(levels)
        val asks = askMap.entries.sortedBy { it.key }.map { ConsRow(it.key, BookSide.ASK, it.value).also { r -> r.okxOrders = ordMap[true to it.key] ?: 0 } }
            .filter { it.total >= minBtc }.take(levels)
        var c = 0.0; bids.forEach { c += it.total; it.cumulative = c }
        c = 0.0; asks.forEach { c += it.total; it.cumulative = c }
        val all = bids + asks
        return ConsBook(asks, bids, all.maxOfOrNull { it.total } ?: 0.0, bids.sumOf { it.total }, asks.sumOf { it.total },
            bids.maxByOrNull { it.total }, asks.maxByOrNull { it.total }, missing)
    }
}
