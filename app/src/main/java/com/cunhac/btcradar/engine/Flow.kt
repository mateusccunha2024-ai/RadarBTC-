package com.cunhac.btcradar.engine

import java.util.TreeMap
import kotlin.math.abs

/** Janelas de agregação visual. Os eventos são processados na chegada; só a exibição é agrupada. */
val FLOW_WINDOWS = listOf(
    "1s" to 1_000L, "5s" to 5_000L, "15s" to 15_000L, "30s" to 30_000L,
    "1m" to 60_000L, "5m" to 300_000L, "15m" to 900_000L,
)

data class FlowStats(
    val windowMs: Long,
    val buyBtc: Double = 0.0,
    val sellBtc: Double = 0.0,
    val buyCount: Int = 0,
    val sellCount: Int = 0,
    val maxBuy: TradeEvent? = null,
    val maxSell: TradeEvent? = null,
) {
    val deltaBtc get() = buyBtc - sellBtc
    val volumeBtc get() = buyBtc + sellBtc
    val count get() = buyCount + sellCount
    val avgBtc get() = if (count > 0) volumeBtc / count else 0.0
    val tradesPerSec get() = count * 1000.0 / windowMs
    val imbalance get() = if (volumeBtc > 0) deltaBtc / volumeBtc else 0.0
}

/**
 * Guarda os trades da última hora (todas as corretoras) e calcula estatísticas por janela.
 * CVD em baldes de 1 s desde a abertura do app.
 */
class FlowEngine(private val keepMs: Long = 3_600_000L, private val maxTrades: Int = 400_000) {
    private val trades = ArrayDeque<TradeEvent>()
    private val cvd = TreeMap<Long, DoubleArray>() // segundo -> delta por corretora
    private val totalDelta = DoubleArray(Venue.entries.size)
    private val lock = Any()
    @Volatile var totalTrades = 0L; private set

    fun add(t: TradeEvent) = synchronized(lock) {
        trades.addLast(t)
        totalTrades++
        val d = if (t.aggressor == Aggressor.BUY) t.qtyBtc else -t.qtyBtc
        totalDelta[t.venue.ordinal] += d
        cvd.getOrPut(t.recvMs / 1000) { DoubleArray(Venue.entries.size) }[t.venue.ordinal] += d
        val cutoff = t.recvMs - keepMs
        while (trades.isNotEmpty() && (trades.first().recvMs < cutoff || trades.size > maxTrades)) trades.removeFirst()
        while (cvd.isNotEmpty() && cvd.firstKey() < cutoff / 1000) cvd.pollFirstEntry()
    }

    /** Estatísticas de todas as janelas numa única passada (venue nulo = consolidado). */
    fun allWindows(venue: Venue?, now: Long = System.currentTimeMillis()): List<FlowStats> = synchronized(lock) {
        val acc = FLOW_WINDOWS.map { MutableStats(it.second) }
        val maxWin = FLOW_WINDOWS.last().second
        var i = trades.size - 1
        while (i >= 0) {
            val t = trades[i--]
            val age = now - t.recvMs
            if (age > maxWin) break
            if (venue != null && t.venue != venue) continue
            for (a in acc) if (age <= a.windowMs) a.add(t)
        }
        acc.map { it.toStats() }
    }

    /** BTC negociado perto de um preço numa corretora desde um instante (corroboração de execução de paredes). */
    fun volumeNear(venue: Venue, price: Double, tolPct: Double, sinceMs: Long): Double = synchronized(lock) {
        var sum = 0.0
        val tol = price * tolPct / 100
        var i = trades.size - 1
        while (i >= 0) {
            val t = trades[i--]
            if (t.recvMs < sinceMs) break
            if (t.venue == venue && abs(t.price - price) <= tol) sum += t.qtyBtc
        }
        sum
    }

    /** CVD acumulado por segundo nos últimos [windowMs] (relativo ao início da janela). */
    fun cvdSeries(windowMs: Long, now: Long = System.currentTimeMillis()): List<Pair<Long, DoubleArray>> = synchronized(lock) {
        val from = (now - windowMs) / 1000
        val run = DoubleArray(Venue.entries.size)
        cvd.tailMap(from, true).map { (sec, d) ->
            for (i in run.indices) run[i] += d[i]
            sec * 1000 to run.copyOf()
        }
    }

    fun cumulativeDelta(): DoubleArray = synchronized(lock) { totalDelta.copyOf() }

    /** Trades recebidos depois de [sinceMs] (mais novos primeiro). */
    fun tradesSince(sinceMs: Long): List<TradeEvent> = synchronized(lock) {
        val out = ArrayList<TradeEvent>()
        var i = trades.size - 1
        while (i >= 0 && trades[i].recvMs > sinceMs) out += trades[i--]
        out
    }

    /** BTC executado por faixa de preço na janela: [compra agressora, venda agressora]. */
    fun volumeByPrice(windowMs: Long, venues: Set<Venue>, key: (Double) -> Double, now: Long = System.currentTimeMillis()): Map<Double, DoubleArray> =
        synchronized(lock) {
            val out = HashMap<Double, DoubleArray>()
            var i = trades.size - 1
            while (i >= 0) {
                val t = trades[i--]
                if (now - t.recvMs > windowMs) break
                if (t.venue !in venues) continue
                out.getOrPut(key(t.price)) { DoubleArray(2) }[if (t.aggressor == Aggressor.BUY) 0 else 1] += t.qtyBtc
            }
            out
        }

    private class MutableStats(val windowMs: Long) {
        var buy = 0.0; var sell = 0.0; var nb = 0; var ns = 0
        var maxB: TradeEvent? = null; var maxS: TradeEvent? = null
        fun add(t: TradeEvent) {
            if (t.aggressor == Aggressor.BUY) {
                buy += t.qtyBtc; nb++; if (maxB == null || t.qtyBtc > maxB!!.qtyBtc) maxB = t
            } else {
                sell += t.qtyBtc; ns++; if (maxS == null || t.qtyBtc > maxS!!.qtyBtc) maxS = t
            }
        }
        fun toStats() = FlowStats(windowMs, buy, sell, nb, ns, maxB, maxS)
    }
}

/** Grandes execuções (acima do limite em BTC), últimas 24 h. */
class LargeTrades(@Volatile var minBtc: Double = 2.0, private val keepMs: Long = 86_400_000L) {
    private val list = ArrayDeque<TradeEvent>()
    private val lock = Any()

    /** Retorna true se o trade entrou como grande execução. */
    fun offer(t: TradeEvent): Boolean = synchronized(lock) {
        if (t.qtyBtc < minBtc) return false
        list.addLast(t)
        val cutoff = t.recvMs - keepMs
        while (list.isNotEmpty() && (list.first().recvMs < cutoff || list.size > 20_000)) list.removeFirst()
        true
    }

    fun recent(n: Int = 200): List<TradeEvent> = synchronized(lock) {
        val out = ArrayList<TradeEvent>(n)
        var i = list.size - 1
        while (i >= 0 && out.size < n) out += list[i--]
        out
    }

    fun top(periodMs: Long, aggressor: Aggressor?, venue: Venue?, n: Int = 50, now: Long = System.currentTimeMillis()): List<TradeEvent> =
        synchronized(lock) {
            list.asSequence().filter { now - it.recvMs <= periodMs && (aggressor == null || it.aggressor == aggressor) && (venue == null || it.venue == venue) }
                .sortedByDescending { it.qtyBtc }.take(n).toList()
        }

    /** Sequências: 3+ grandes execuções do mesmo lado em até 10 s. */
    fun clusters(periodMs: Long, now: Long = System.currentTimeMillis()): List<List<TradeEvent>> = synchronized(lock) {
        val src = list.filter { now - it.recvMs <= periodMs }
        val out = mutableListOf<List<TradeEvent>>()
        var cur = mutableListOf<TradeEvent>()
        for (t in src) {
            val last = cur.lastOrNull()
            if (last != null && (t.aggressor != last.aggressor || t.recvMs - last.recvMs > 10_000)) {
                if (cur.size >= 3) out += cur
                cur = mutableListOf()
            }
            cur += t
        }
        if (cur.size >= 3) out += cur
        out.reversed()
    }
}

// ---------------------------------------------------------------------
// Sinais inferenciais LONG / SHORT
// ---------------------------------------------------------------------

enum class FlowClass(val label: String, val side: String) {
    LONG_ACCUMULATION("LONG-BIASED ACCUMULATION", "LONG FLOW"),
    SHORT_COVERING("SHORT COVERING", "LONG FLOW"),
    BUYING_PRESSURE("BUYING PRESSURE", "LONG FLOW"),
    SHORT_ACCUMULATION("SHORT-BIASED ACCUMULATION", "SHORT FLOW"),
    LONG_CLOSING("LONG CLOSING", "SHORT FLOW"),
    SELLING_PRESSURE("SELLING PRESSURE", "SHORT FLOW"),
    NEUTRAL("SEM VIÉS CLARO", "—"),
}

data class FlowSignal(
    val cls: FlowClass,
    val confidence: String,
    val observed: List<String>,
    val interpretation: String,
    val limitation: String,
)

object Signals {
    const val LIMITATION = "Inferência. A agressão mostra quem iniciou a execução, não se abriu ou fechou posição. " +
        "Todo contrato tem comprador e vendedor; OI subindo com compra agressora é compatível com expansão LONG, mas não prova."

    /**
     * @param oiChangePct variação do open interest somado na janela (null = indisponível)
     * @param priceChangePct variação do preço na janela
     */
    fun classify(flow: FlowStats, priceChangePct: Double?, oiChangePct: Double?, funding: Double?): FlowSignal {
        val obs = mutableListOf(
            "Compra agressora: %.3f BTC".format(PT_BR, flow.buyBtc),
            "Venda agressora: %.3f BTC".format(PT_BR, flow.sellBtc),
            "Delta: %+.3f BTC (desequilíbrio %+.0f%%)".format(PT_BR, flow.deltaBtc, flow.imbalance * 100),
            "Preço: " + (priceChangePct?.let { "%+.3f%%".format(PT_BR, it) } ?: "sem dado"),
            "Open interest: " + (oiChangePct?.let { "%+.3f%%".format(PT_BR, it) } ?: "sem dado"),
            "Funding médio: " + (funding?.let { "%+.4f%%".format(PT_BR, it * 100) } ?: "sem dado"),
        )
        val imb = flow.imbalance
        if (flow.volumeBtc <= 0 || abs(imb) < 0.10) {
            return FlowSignal(FlowClass.NEUTRAL, "—", obs, "Fluxo agressor equilibrado na janela.", LIMITATION)
        }
        val oiUp = oiChangePct != null && oiChangePct > 0.05
        val oiDown = oiChangePct != null && oiChangePct < -0.05
        val cls = if (imb > 0) {
            when { oiUp && (priceChangePct ?: 0.0) >= 0 -> FlowClass.LONG_ACCUMULATION; oiDown -> FlowClass.SHORT_COVERING; else -> FlowClass.BUYING_PRESSURE }
        } else {
            when { oiUp && (priceChangePct ?: 0.0) <= 0 -> FlowClass.SHORT_ACCUMULATION; oiDown -> FlowClass.LONG_CLOSING; else -> FlowClass.SELLING_PRESSURE }
        }
        var score = 0
        if (abs(imb) > 0.30) score++
        if (oiChangePct != null && abs(oiChangePct) > 0.20) score++
        if (priceChangePct != null && priceChangePct * imb > 0) score++
        val conf = when (score) { 3 -> "ALTA"; 2 -> "MÉDIA"; else -> "BAIXA" }
        val interp = when (cls) {
            FlowClass.LONG_ACCUMULATION -> "Compras agressoras com OI subindo: compatível com abertura líquida de LONG."
            FlowClass.SHORT_COVERING -> "Compras agressoras com OI caindo: compatível com fechamento de SHORT."
            FlowClass.BUYING_PRESSURE -> "Predomínio comprador sem variação relevante de OI."
            FlowClass.SHORT_ACCUMULATION -> "Vendas agressoras com OI subindo: compatível com abertura líquida de SHORT."
            FlowClass.LONG_CLOSING -> "Vendas agressoras com OI caindo: compatível com fechamento de LONG."
            FlowClass.SELLING_PRESSURE -> "Predomínio vendedor sem variação relevante de OI."
            FlowClass.NEUTRAL -> ""
        }
        return FlowSignal(cls, conf, obs, interp, LIMITATION)
    }
}
