package com.cunhac.btcradar.engine

import java.util.TreeMap

data class Level(val price: Double, val qtyBtc: Double)

data class BookView(
    val venue: Venue,
    val valid: Boolean,
    val bids: List<Level>,   // melhor primeiro
    val asks: List<Level>,   // melhor primeiro
    val bidLevels: Int,
    val askLevels: Int,
    val lastUpdateMs: Long,
    val latencyMs: Long,
    val lastSeq: Long,
    val resyncs: Int,
    val note: String,
) {
    val bestBid: Double? get() = bids.firstOrNull()?.price
    val bestAsk: Double? get() = asks.firstOrNull()?.price
    val mid: Double? get() = if (bestBid != null && bestAsk != null) (bestBid!! + bestAsk!!) / 2 else null
}

/**
 * Livro local de uma corretora. Quantidades sempre em BTC.
 * Nunca é publicado como válido depois de uma quebra de sequência até ser reconstruído.
 */
class LocalBook(val venue: Venue) {
    private val bids = TreeMap<Double, Double>(Comparator.reverseOrder())
    private val asks = TreeMap<Double, Double>()

    @Volatile var valid = false; private set
    @Volatile var lastUpdateMs = 0L; private set
    @Volatile var latencyMs = 0L; private set
    @Volatile var lastSeq = 0L; private set
    @Volatile var resyncs = 0; private set
    @Volatile var note = "aguardando snapshot"

    @Synchronized
    fun reset(b: List<Level>, a: List<Level>, seq: Long, eventMs: Long) {
        bids.clear(); asks.clear()
        b.forEach { if (it.qtyBtc > 0) bids[it.price] = it.qtyBtc }
        a.forEach { if (it.qtyBtc > 0) asks[it.price] = it.qtyBtc }
        lastSeq = seq
        valid = true
        note = "ok"
        touch(eventMs)
    }

    /** Aplica incremento: quantidade 0 remove o nível. */
    @Synchronized
    fun apply(b: List<Level>, a: List<Level>, seq: Long, eventMs: Long) {
        if (!valid) return
        b.forEach { if (it.qtyBtc <= 0) bids.remove(it.price) else bids[it.price] = it.qtyBtc }
        a.forEach { if (it.qtyBtc <= 0) asks.remove(it.price) else asks[it.price] = it.qtyBtc }
        lastSeq = seq
        // Livro cruzado = estado inconsistente
        val bb = bids.firstKey(); val ba = asks.firstKey()
        if (bids.isNotEmpty() && asks.isNotEmpty() && bb != null && ba != null && bb >= ba) {
            invalidate("livro cruzado (bid $bb >= ask $ba)")
            return
        }
        touch(eventMs)
    }

    @Synchronized
    fun invalidate(reason: String) {
        if (valid) resyncs++
        valid = false
        note = reason
    }

    private fun touch(eventMs: Long) {
        val now = System.currentTimeMillis()
        lastUpdateMs = now
        if (eventMs > 0) latencyMs = (now - eventMs).coerceAtLeast(0)
    }

    @Synchronized
    fun view(maxLevels: Int = Int.MAX_VALUE): BookView = BookView(
        venue = venue,
        valid = valid,
        bids = bids.entries.asSequence().take(maxLevels).map { Level(it.key, it.value) }.toList(),
        asks = asks.entries.asSequence().take(maxLevels).map { Level(it.key, it.value) }.toList(),
        bidLevels = bids.size,
        askLevels = asks.size,
        lastUpdateMs = lastUpdateMs,
        latencyMs = latencyMs,
        lastSeq = lastSeq,
        resyncs = resyncs,
        note = note,
    )
}
