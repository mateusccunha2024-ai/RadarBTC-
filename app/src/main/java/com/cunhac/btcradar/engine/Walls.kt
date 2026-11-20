package com.cunhac.btcradar.engine

import kotlin.math.abs
import kotlin.math.max

enum class BookSide { BID, ASK }
enum class WallClass { SMALL, MEDIUM, LARGE, EXTREME }

enum class WallStatus {
    NEW, ACTIVE, INCREASED, REDUCED,
    DISAPPEARED,            // saiu do livro sem evidência de execução (provável cancelamento)
    POSSIBLY_EXECUTED,      // o preço atravessou o nível, mas o volume negociado não confirma
    EXECUTION_CORROBORATED, // trades no nível somam pelo menos metade da quantidade que havia
}

/** Limites editáveis (BTC). */
class WallThresholds {
    @Volatile var small = 1.0
    @Volatile var medium = 3.0
    @Volatile var large = 5.0
    @Volatile var extreme = 10.0
    fun classify(q: Double): WallClass? = when {
        q >= extreme -> WallClass.EXTREME
        q >= large -> WallClass.LARGE
        q >= medium -> WallClass.MEDIUM
        q >= small -> WallClass.SMALL
        else -> null
    }
}

/**
 * Concentração num nível de preço de uma corretora. O livro público é agregado por preço:
 * a identidade é (corretora, instrumento, lado, preço) — não há como saber quem colocou a ordem.
 */
data class Wall(
    val venue: Venue,
    val side: BookSide,
    val price: Double,
    val initialQty: Double,
    val currentQty: Double,
    val peakQty: Double,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val status: WallStatus,
    val statusMs: Long,
    val cls: WallClass,
    val tradedNearBtc: Double = 0.0,
    /** BTC negociado exatamente neste preço desde que a parede apareceu. */
    val executedBtc: Double = 0.0,
) {
    val changeBtc get() = currentQty - initialQty
    val changePct get() = if (initialQty > 0) changeBtc / initialQty * 100 else 0.0
    val permanenceMs get() = lastSeenMs - firstSeenMs
    /** Parte da liquidez vista no nível que já foi executada. */
    val consumedPct get() = if (executedBtc + currentQty > 0) executedBtc / (executedBtc + currentQty) * 100 else 0.0
    fun distancePct(mid: Double) = (price / mid - 1) * 100
}

class WallTracker(val thresholds: WallThresholds) {
    @Volatile var rangePct = 5.0
    private val active = HashMap<String, Wall>()
    private val history = ArrayDeque<Wall>()
    private val lock = Any()
    /** Gancho para persistência de eventos de paredes. */
    @Volatile var onEvent: ((Wall) -> Unit)? = null

    private fun key(v: Venue, s: BookSide, p: Double) = "${v.name}|${s.name}|$p"
    private fun execKey(v: Venue, p: Double) = "${v.name}|${Math.round(p * 10)}"
    private var lastScanMs = 0L

    fun scan(views: List<BookView>, mid: Double?, flow: FlowEngine, now: Long = System.currentTimeMillis()) {
        if (mid == null || mid <= 0) return
        val events = mutableListOf<Wall>()
        // Volume executado por (corretora, preço) desde a varredura anterior
        val exec = HashMap<String, Double>()
        if (lastScanMs > 0) flow.tradesSince(lastScanMs).forEach { t -> exec.merge(execKey(t.venue, t.price), t.qtyBtc, Double::plus) }
        lastScanMs = now
        synchronized(lock) {
            for (v in views) {
                if (!v.valid) continue // livro inválido: não conclui nada sobre desaparecimentos
                val present = HashMap<String, Pair<BookSide, Level>>()
                v.bids.forEach { if (it.qtyBtc >= thresholds.small) present[key(v.venue, BookSide.BID, it.price)] = BookSide.BID to it }
                v.asks.forEach { if (it.qtyBtc >= thresholds.small) present[key(v.venue, BookSide.ASK, it.price)] = BookSide.ASK to it }

                for ((k, p) in present) {
                    val (side, lv) = p
                    val cls = thresholds.classify(lv.qtyBtc) ?: continue
                    val w = active[k]
                    if (w == null) {
                        if (abs(lv.price / mid - 1) * 100 > rangePct) continue
                        val nw = Wall(v.venue, side, lv.price, lv.qtyBtc, lv.qtyBtc, lv.qtyBtc, now, now, WallStatus.NEW, now, cls)
                        active[k] = nw; events += nw
                    } else {
                        val prev = w.currentQty
                        val st = when {
                            lv.qtyBtc > prev * 1.10 -> WallStatus.INCREASED
                            lv.qtyBtc < prev * 0.90 -> WallStatus.REDUCED
                            now - w.statusMs > 15_000 -> WallStatus.ACTIVE
                            else -> w.status
                        }
                        val nw = w.copy(currentQty = lv.qtyBtc, peakQty = max(w.peakQty, lv.qtyBtc), lastSeenMs = now,
                            status = st, statusMs = if (st != w.status) now else w.statusMs, cls = cls,
                            executedBtc = w.executedBtc + (exec[execKey(w.venue, w.price)] ?: 0.0))
                        active[k] = nw
                        if (st != w.status && (st == WallStatus.INCREASED || st == WallStatus.REDUCED)) events += nw
                    }
                }

                val prefix = "${v.venue.name}|"
                val ended = active.keys.filter { it.startsWith(prefix) && it !in present }
                for (k in ended) {
                    val w = active.remove(k) ?: continue
                    val traded = flow.volumeNear(w.venue, w.price, 0.02, w.lastSeenMs - 3_000)
                    val crossed = if (w.side == BookSide.BID) (v.bestBid ?: Double.MAX_VALUE) < w.price else (v.bestAsk ?: 0.0) > w.price
                    val st = when {
                        traded >= 0.5 * w.currentQty -> WallStatus.EXECUTION_CORROBORATED
                        crossed -> WallStatus.POSSIBLY_EXECUTED
                        else -> WallStatus.DISAPPEARED
                    }
                    val fw = w.copy(status = st, statusMs = now, tradedNearBtc = traded)
                    history.addFirst(fw)
                    while (history.size > 3_000) history.removeLast()
                    events += fw
                }
            }
        }
        onEvent?.let { cb -> events.forEach(cb) }
    }

    fun top(side: BookSide, venue: Venue?, n: Int = 50): List<Wall> = synchronized(lock) {
        active.values.filter { it.side == side && (venue == null || it.venue == venue) }.sortedByDescending { it.currentQty }.take(n)
    }

    fun history(n: Int = 300): List<Wall> = synchronized(lock) { history.take(n) }
    fun activeCount(): Int = synchronized(lock) { active.size }
}
