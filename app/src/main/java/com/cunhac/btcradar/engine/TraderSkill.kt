package com.cunhac.btcradar.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Fluxo do "day-trader-skill" (MIT, Jake Schincariol) portado para o app, para BTC e ações dos EUA:
 * scanner → notícias → gatilho de entrada → stop e alvo → Time Machine → tamanho da posição → risco → boleta.
 * Tudo aqui é cálculo puro sobre candles de 5 minutos já baixados (testável sem rede).
 * NADA aqui envia ordem: a boleta é só um texto para o usuário ler. Só compras (long), como na skill.
 */

/** Candle de 5 minutos. [t] = início do candle em ms (UTC). */
data class Bar5(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double = 0.0)

/** Como é um "dia de pregão" em cada mercado. */
enum class TradeMarket(val zone: ZoneId, val openMin: Int, val bars: Int, val flatByMin: Int, val noEntryBeforeMin: Int,
                       val noEntryAfterMin: Int, val tick: Double, val qtyStep: Double) {
    /** NYSE/Nasdaq: 9h30 às 16h (Nova York), 78 candles; zera até 15h55; entradas entre 9h45 e 15h30. */
    US_STOCK(ZoneId.of("America/New_York"), 9 * 60 + 30, 78, 15 * 60 + 55, 9 * 60 + 45, 15 * 60 + 30, 0.01, 1.0),
    /** BTC não fecha: cada dia UTC (0h às 24h) é uma sessão de 288 candles; zera até 23h55 UTC; entradas até 23h. */
    BTC(ZoneOffset.UTC, 0, 288, 23 * 60 + 55, 15, 23 * 60, 0.1, 0.0001);

    /** Índice do último candle que termina até o horário de zerar. */
    val lastSlot: Int get() = min(bars - 1, (flatByMin - openMin) / 5 - 1)
}

/** Sessão: candles indexados pela posição no dia (0 = abertura). Sessões com buracos ficam com null. */
data class TradeSession(val date: LocalDate, val slots: Array<Bar5?>) {
    val full: Boolean get() = slots.all { it != null }
    fun count() = slots.count { it != null }
    override fun equals(other: Any?) = other is TradeSession && other.date == date && other.slots.contentEquals(slots)
    override fun hashCode() = date.hashCode()
}

object TraderClock {
    val NY: ZoneId = ZoneId.of("America/New_York")
    /** Feriados da NYSE 2026–2027 (fonte: nyse.com, lista da skill). */
    val HOLIDAYS = setOf(
        "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25", "2026-06-19", "2026-07-03", "2026-09-07",
        "2026-11-26", "2026-12-25", "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31", "2027-06-18",
        "2027-07-05", "2027-09-06", "2027-11-25", "2027-12-24",
    )
    /** Fechamento às 13h (Nova York). */
    val EARLY_CLOSE = setOf("2026-11-27", "2026-12-24", "2027-11-26")

    fun ny(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(NY)
    fun tradingDay(d: LocalDate) = d.dayOfWeek.value <= 5 && d.toString() !in HOLIDAYS
    fun closeMin(d: LocalDate) = if (d.toString() in EARLY_CLOSE) 13 * 60 else 16 * 60

    /** Bolsa dos EUA aberta agora? */
    fun usOpen(ms: Long): Boolean {
        val z = ny(ms); val m = z.hour * 60 + z.minute
        return tradingDay(z.toLocalDate()) && m >= 9 * 60 + 30 && m < closeMin(z.toLocalDate())
    }

    /** Próxima abertura da bolsa (ms). */
    fun nextUsOpen(ms: Long): Long {
        var z = ny(ms)
        val m = z.hour * 60 + z.minute
        var d = z.toLocalDate()
        if (!(tradingDay(d) && m < 9 * 60 + 30)) d = d.plusDays(1)
        while (!tradingDay(d)) d = d.plusDays(1)
        z = d.atTime(9, 30).atZone(NY)
        return z.toInstant().toEpochMilli()
    }
}

object TraderSkill {

    // ------------------------------------------------------------------ sessões

    /** Agrupa os candles por dia de pregão do mercado; descarta candles fora do horário e fora da grade de 5 min. */
    fun sessions(bars: List<Bar5>, m: TradeMarket): List<TradeSession> {
        val byDay = sortedMapOf<LocalDate, Array<Bar5?>>()
        for (b in bars) {
            if (b.t % 300_000L != 0L) continue
            val z = Instant.ofEpochMilli(b.t).atZone(m.zone)
            val mins = z.hour * 60 + z.minute - m.openMin
            if (mins < 0 || mins >= m.bars * 5) continue
            byDay.getOrPut(z.toLocalDate()) { arrayOfNulls(m.bars) }[mins / 5] = b
        }
        return byDay.map { (d, s) -> TradeSession(d, s) }
    }

    // ------------------------------------------------------------------ gatilho de entrada (trader-entry)

    enum class EntryStatus { NO_DATA, BUILDING_RANGE, WAITING, TRIGGERED, EXPIRED }

    data class Entry(
        val status: EntryStatus, val orHigh: Double?, val orLow: Double?, val slot: Int?, val price: Double?,
        val timeMs: Long?, val last: Double?, val distancePct: Double?, val barsSeen: Int,
    )

    /**
     * Abertura de [orMinutes] minutos; entrada no primeiro candle COMPLETO que FECHA acima da máxima da abertura,
     * dentro da janela de entradas. Só compra.
     */
    fun entry(today: TradeSession?, m: TradeMarket, orMinutes: Int = 15, nowMs: Long = System.currentTimeMillis()): Entry {
        val n = orMinutes / 5
        if (today == null) return Entry(EntryStatus.NO_DATA, null, null, null, null, null, null, null, 0)
        val done = today.slots.withIndex().filter { (_, b) -> b != null && b.t + 300_000L <= nowMs }.map { it.index to it.value!! }
        val last = done.lastOrNull()?.second?.c
        if ((0 until n).any { i -> done.none { it.first == i } }) return Entry(EntryStatus.BUILDING_RANGE, null, null, null, null, null, last, null, done.size)
        val orH = (0 until n).maxOf { i -> today.slots[i]!!.h }
        val orL = (0 until n).minOf { i -> today.slots[i]!!.l }
        val minSlot = max(n, (m.noEntryBeforeMin - m.openMin) / 5 - 1)
        val maxSlot = (m.noEntryAfterMin - m.openMin) / 5 - 1
        val hit = done.firstOrNull { (i, b) -> i in minSlot..maxSlot && b.c > orH }
        if (hit != null) {
            val (i, b) = hit
            return Entry(EntryStatus.TRIGGERED, orH, orL, i, b.c, b.t + 300_000L, last, (b.c / orH - 1) * 100, done.size)
        }
        val lastSlot = done.lastOrNull()?.first ?: -1
        val st = if (lastSlot >= maxSlot) EntryStatus.EXPIRED else EntryStatus.WAITING
        return Entry(st, orH, orL, null, null, null, last, last?.let { (orH / it - 1) * 100 }, done.size)
    }

    // ------------------------------------------------------------------ stop e alvo (trader-exit)

    enum class StopRule { OR_MID, OR_LOW, PCT }

    data class Plan(
        val symbol: String, val market: TradeMarket, val date: LocalDate, val entry: Double, val entrySlot: Int,
        val entryTimeMs: Long?, val stop: Double, val target: Double, val targetR: Double, val stopRule: StopRule,
        val orHigh: Double, val orLow: Double, val orMinutes: Int,
    ) {
        val risk: Double get() = entry - stop
        val reward: Double get() = target - entry
    }

    /** Arredonda o stop para longe da entrada (para baixo numa compra), no passo de preço do mercado. */
    fun stopRound(x: Double, tick: Double): Double = floor(Math.round(x / tick * 1e4) / 1e4) * tick
    fun tickRound(x: Double, tick: Double): Double = (x / tick).roundToLong() * tick

    sealed class ExitResult {
        data class Ok(val plan: Plan) : ExitResult()
        /** Motivo da recusa (em português; a tela traduz). */
        data class Refused(val reason: String) : ExitResult()
    }

    fun exitPlan(symbol: String, m: TradeMarket, date: LocalDate, e: Entry, orMinutes: Int = 15, rule: StopRule = StopRule.OR_MID,
                 stopPct: Double = 1.0, targetR: Double = 2.0): ExitResult {
        val entry = e.price ?: return ExitResult.Refused("Ainda não houve gatilho de entrada: o plano só existe depois do rompimento.")
        val oh = e.orHigh!!; val ol = e.orLow!!
        val raw = when (rule) { StopRule.OR_MID -> (oh + ol) / 2; StopRule.OR_LOW -> ol; StopRule.PCT -> entry * (1 - stopPct / 100) }
        val stop = stopRound(raw, m.tick)
        if (stop >= entry) return ExitResult.Refused("O stop (${fmt(stop)}) ficou acima ou igual à entrada (${fmt(entry)}).")
        val risk = entry - stop
        val minRisk = max(2 * m.tick, entry * 0.0005)
        if (risk < minRisk) return ExitResult.Refused("Stop apertado demais (${fmt(risk)} de risco): não sobrevive ao spread.")
        val target = tickRound(entry + targetR * risk, m.tick)
        if (target <= entry) return ExitResult.Refused("O alvo ficou abaixo da entrada.")
        return ExitResult.Ok(Plan(symbol, m, date, entry, e.slot!!, e.timeMs, stop, target, targetR, rule, oh, ol, orMinutes))
    }

    // ------------------------------------------------------------------ Time Machine (trader-timemachine)

    data class MonteCarlo(
        val replays: Int, val seed: Long, val window: Int, val barsLeft: Int, val pTarget: Double, val pStop: Double,
        val pNeither: Double, val pGreen: Double, val medianClose: Double, val p5Close: Double, val p95Close: Double,
        val avgR: Double, val rAtTarget: Double, val bands: List<DoubleArray>, val sessionsUsed: Int,
    )

    data class HistoryTrade(val date: LocalDate, val entry: Double, val stop: Double, val target: Double, val outcome: String, val r: Double)
    data class History(val sessions: Int, val trades: List<HistoryTrade>) {
        val triggered get() = trades.size
        val target get() = trades.count { it.outcome == "target" }
        val stop get() = trades.count { it.outcome == "stop" }
        val close get() = trades.count { it.outcome == "close" }
        val avgR: Double? get() = if (trades.isEmpty()) null else trades.sumOf { it.r } / trades.size
    }

    val BAND_Q = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 0.95)

    /** pool[i] = barra i de cada sessão passada como (o,h,l,c) / fechamento da barra i-1. */
    fun pool(full: List<TradeSession>, m: TradeMarket): Array<List<DoubleArray>> {
        val p = Array(m.bars) { ArrayList<DoubleArray>() }
        for (s in full) for (i in 1 until m.bars) {
            val pc = s.slots[i - 1]!!.c; val b = s.slots[i]!!
            p[i].add(doubleArrayOf(b.o / pc, b.h / pc, b.l / pc, b.c / pc))
        }
        return Array(m.bars) { p[it] }
    }

    private fun q(sorted: DoubleArray, x: Double) = sorted[min(sorted.size - 1, (x * sorted.size).toInt())]

    /**
     * 10.000 replays do resto do dia: a cada candle sorteia, entre as sessões passadas, o mesmo horário (±[window] candles)
     * e aplica a variação dele ao preço. Stop e alvo no mesmo candle contam como stop (conservador), como na skill.
     */
    fun simulate(pool: Array<List<DoubleArray>>, m: TradeMarket, entry: Double, stop: Double, target: Double, k: Int, last: Int,
                 replays: Int = 10_000, seed: Long = 19, window: Int = 2): MonteCarlo? {
        if (k >= last || pool.drop(1).any { it.isEmpty() }) return null
        val risk = entry - stop
        val rT = (target - entry) / risk
        fun slot(r: java.util.Random, i: Int) = if (window == 0) i else min(m.bars - 1, max(1, i + r.nextInt(2 * window + 1) - window))
        var rng = java.util.Random(seed)
        var nT = 0; var nS = 0; var green = 0; var sumR = 0.0
        val closes = DoubleArray(replays)
        for (n in 0 until replays) {
            var p = entry; var out = 0 // 0 nenhum, 1 alvo, 2 stop
            for (i in k + 1..last) {
                val pl = pool[slot(rng, i)]; val b = pl[rng.nextInt(pl.size)]
                val hi = p * b[1]; val lo = p * b[2]
                if (out == 0) { if (lo <= stop) out = 2 else if (hi >= target) out = 1 }
                p *= b[3]
            }
            closes[n] = p
            when (out) { 1 -> { nT++; green++; sumR += rT }; 2 -> { nS++; sumR -= 1.0 }; else -> { if (p > entry) green++; sumR += (p - entry) / risk } }
        }
        val srt = closes.sortedArray()
        // Leque: 2.000 caminhos, mesma semente, só fechamentos.
        rng = java.util.Random(seed)
        val paths = 2_000; val len = last - k + 1
        val all = Array(len) { DoubleArray(paths) }
        for (n in 0 until paths) {
            var p = entry; all[0][n] = p
            for ((j, i) in (k + 1..last).withIndex()) { val pl = pool[slot(rng, i)]; p *= pl[rng.nextInt(pl.size)][3]; all[j + 1][n] = p }
        }
        val bands = all.map { col -> col.sort(); DoubleArray(BAND_Q.size) { qi -> q(col, BAND_Q[qi]) } }
        return MonteCarlo(replays, seed, window, last - k, nT.toDouble() / replays, nS.toDouble() / replays,
            (replays - nT - nS).toDouble() / replays, green.toDouble() / replays, q(srt, 0.5), q(srt, 0.05), q(srt, 0.95),
            sumR / replays, rT, bands, pool[1].size)
    }

    /** A mesma regra em cada sessão passada: entrada no 1º fechamento acima da abertura, depois stop, depois alvo, senão o fechamento. */
    fun history(full: List<TradeSession>, m: TradeMarket, orMinutes: Int, rule: StopRule, stopPct: Double, targetR: Double, last: Int): History {
        val n = orMinutes / 5
        val maxSlot = min(last, (m.noEntryAfterMin - m.openMin) / 5 - 1)
        val minSlot = max(n, (m.noEntryBeforeMin - m.openMin) / 5 - 1)
        val out = ArrayList<HistoryTrade>()
        for (s in full) {
            val d = s.slots.map { it!! }
            val oh = (0 until n).maxOf { d[it].h }; val ol = (0 until n).minOf { d[it].l }
            val kk = (minSlot..maxSlot).firstOrNull { d[it].c > oh } ?: continue
            val e = d[kk].c
            val st = stopRound(when (rule) { StopRule.OR_MID -> (oh + ol) / 2; StopRule.OR_LOW -> ol; StopRule.PCT -> e * (1 - stopPct / 100) }, m.tick)
            val risk = e - st
            if (risk <= 0) continue
            val t = e + targetR * risk
            var oc = "close"
            for (b in d.subList(kk + 1, last + 1)) {
                if (b.l <= st) { oc = "stop"; break }
                if (b.h >= t) { oc = "target"; break }
            }
            val r = when (oc) { "target" -> targetR; "stop" -> -1.0; else -> (d[last].c - e) / risk }
            out += HistoryTrade(s.date, e, st, t, oc, r)
        }
        return History(full.size, out)
    }

    /**
     * Placar: o que aconteceu com o plano naquela sessão — "target", "stop", "close" (nenhum até zerar) ou "no_trigger"
     * (plano hipotético que não rompeu). null = sessão ainda incompleta. Stop e alvo no mesmo candle contam como stop.
     */
    fun outcome(s: TradeSession, m: TradeMarket, entry: Double, stop: Double, target: Double, slot: Int, hypothetical: Boolean): String? {
        val last = m.lastSlot
        if (s.slots[last] == null) return null
        var k = slot
        if (hypothetical) {
            val maxSlot = (m.noEntryAfterMin - m.openMin) / 5 - 1
            k = (slot..maxSlot).firstOrNull { i -> s.slots[i]?.let { it.c >= entry } == true } ?: return "no_trigger"
        }
        for (i in k + 1..last) {
            val b = s.slots[i] ?: continue
            if (b.l <= stop) return "stop"
            if (b.h >= target) return "target"
        }
        return "close"
    }

    enum class Lean { NONE, COIN_FLIP, TOWARD_TARGET, TOWARD_STOP, STRONG_TARGET, STRONG_STOP }

    /** Veredito honesto da skill: participação do alvo entre os replays que tocaram um lado, contra o ponto de equilíbrio do R. */
    fun lean(mc: MonteCarlo): Pair<Lean, Double> {
        val hit = mc.pTarget + mc.pStop
        if (hit <= 0) return Lean.NONE to 0.0
        val share = mc.pTarget / hit
        val even = 1.0 / (1.0 + mc.rAtTarget)
        val d = share - even
        return when {
            abs(d) < 0.05 -> Lean.COIN_FLIP
            abs(d) < 0.15 -> if (d > 0) Lean.TOWARD_TARGET else Lean.TOWARD_STOP
            else -> if (d > 0) Lean.STRONG_TARGET else Lean.STRONG_STOP
        } to d
    }

    // ------------------------------------------------------------------ tamanho (trader-budget)

    data class Rules(
        val budget: Double = 5_000.0, val cashBufferPct: Double = 10.0, val maxPositionPct: Double = 75.0,
        val maxOrderUsd: Double = 4_000.0, val minOrderUsd: Double = 5.0, val maxLossPerTrade: Double = 50.0,
        val maxDailyLoss: Double = 150.0, val maxTradesPerDay: Int = 3, val minRewardRisk: Double = 1.0,
        val blocked: Set<String> = emptySet(),
    )

    data class Limit(val name: String, val usd: Double, val qty: Double)
    data class Sizing(
        val qty: Double, val boundBy: String, val limits: List<Limit>, val ref: Double, val cost: Double,
        val maxLoss: Double, val rewardRisk: Double, val refusal: String?,
    )

    private fun fit(usd: Double, per: Double, step: Double): Double = if (per <= 0) 0.0 else floor(usd / per / step + 1e-9) * step

    /** Maior quantidade que cabe nos 4 limites (perda máx., teto da posição, ordem máx., caixa menos a reserva). */
    fun size(p: Plan, r: Rules, freshPrice: Double? = null): Sizing {
        val ref = if (freshPrice != null && freshPrice > p.entry) tickRound(freshPrice, p.market.tick) else p.entry
        val step = p.market.qtyStep
        val risk = ref - p.stop
        val avail = max(0.0, r.budget - r.budget * r.cashBufferPct / 100)
        val limits = listOf(
            Limit("perda máxima por trade", r.maxLossPerTrade, fit(r.maxLossPerTrade, risk, step)),
            Limit("teto da posição", r.budget * r.maxPositionPct / 100, fit(r.budget * r.maxPositionPct / 100, ref, step)),
            Limit("ordem máxima", r.maxOrderUsd, fit(r.maxOrderUsd, ref, step)),
            Limit("caixa menos a reserva", avail, fit(avail, ref, step)),
        )
        val qty = max(0.0, limits.minOf { it.qty })
        val bound = limits.first { it.qty == qty }.name
        val cost = qty * ref
        val loss = qty * risk
        val rr = (p.target - ref) / risk
        val refusal = when {
            risk <= 0 -> "O preço já está no stop ou abaixo dele."
            p.target <= ref -> "O preço já passou do alvo: o movimento já aconteceu."
            p.symbol.uppercase() in r.blocked -> "${p.symbol} está na sua lista de bloqueados."
            qty <= 0 && bound == "perda máxima por trade" -> "Uma unidade arrisca ${fmt(risk)}, mais que a sua perda máxima de ${fmt(r.maxLossPerTrade)} por trade. Sem ordem."
            qty <= 0 -> "Uma unidade custa ${fmt(ref)}, mais que o limite \"$bound\". Sem ordem."
            rr < r.minRewardRisk - 1e-9 -> "Retorno/risco de ${fmt(rr)} abaixo do seu mínimo de ${fmt(r.minRewardRisk)}. Sem ordem."
            cost < r.minOrderUsd -> "A ordem (${fmt(cost)}) fica abaixo do mínimo de ${fmt(r.minOrderUsd)}."
            else -> null
        }
        return Sizing(qty, bound, limits, ref, cost, loss, rr, refusal)
    }

    // ------------------------------------------------------------------ risco (trader-risk)

    data class Check(val name: String, val pass: Boolean, val detail: String)
    data class RiskVerdict(val pass: Boolean, val checks: List<Check>)

    /** PASS ou BLOCK antes de qualquer boleta. [riskToday]/[tradesToday] vêm do diário de paper trades do aparelho. */
    fun risk(p: Plan, s: Sizing, r: Rules, riskToday: Double, tradesToday: Int, nowMs: Long): RiskVerdict {
        val c = ArrayList<Check>()
        c += Check("Stop definido", p.stop > 0 && p.stop < s.ref, "stop ${fmt(p.stop)}")
        c += Check("Perda no stop ≤ máximo por trade", s.maxLoss <= r.maxLossPerTrade + 0.005, "${fmt(s.maxLoss)} de ${fmt(r.maxLossPerTrade)}")
        c += Check("Retorno/risco ≥ mínimo", s.rewardRisk >= r.minRewardRisk - 1e-9, "${fmt(s.rewardRisk)} ≥ ${fmt(r.minRewardRisk)}")
        c += Check("Risco do dia ≤ limite diário", riskToday + s.maxLoss <= r.maxDailyLoss + 0.005, "${fmt(riskToday)} + ${fmt(s.maxLoss)} de ${fmt(r.maxDailyLoss)}")
        c += Check("Trades hoje < máximo", tradesToday < r.maxTradesPerDay, "$tradesToday de ${r.maxTradesPerDay}")
        c += Check("Ordem ≤ ordem máxima", s.cost <= r.maxOrderUsd + 0.005, "${fmt(s.cost)} de ${fmt(r.maxOrderUsd)}")
        c += Check("Não está bloqueado", p.symbol.uppercase() !in r.blocked, p.symbol)
        c += Check("Quantidade maior que zero", s.qty > 0, fmtQty(s.qty, p.market))
        val z = Instant.ofEpochMilli(nowMs).atZone(p.market.zone)
        val mins = z.hour * 60 + z.minute
        if (p.market == TradeMarket.US_STOCK) {
            c += Check("Bolsa aberta", TraderClock.usOpen(nowMs), if (TraderClock.usOpen(nowMs)) "aberta" else "fechada")
            c += Check("Dentro da janela de entradas (9h45–15h30 NY)", mins in p.market.noEntryBeforeMin..p.market.noEntryAfterMin, "%02d:%02d NY".format(z.hour, z.minute))
        } else {
            c += Check("Dentro da janela de entradas (até 23h UTC)", mins <= p.market.noEntryAfterMin, "%02d:%02d UTC".format(z.hour, z.minute))
        }
        c += Check("Plano é de hoje", p.date == z.toLocalDate(), p.date.toString())
        return RiskVerdict(c.all { it.pass }, c)
    }

    // ------------------------------------------------------------------ boleta (trader-broker, só leitura)

    /** Boleta bracket no formato do place_stock_order da Alpaca (conta paper). Só texto: o app nunca envia. */
    fun ticket(p: Plan, s: Sizing): String {
        val q = fmtQty(s.qty, p.market)
        return if (p.market == TradeMarket.US_STOCK)
            """{"symbol":"${p.symbol}","qty":"$q","side":"buy","type":"market","time_in_force":"day","order_class":"bracket",""" +
                """"take_profit":{"limit_price":"${"%.2f".format(java.util.Locale.US, p.target)}"},"stop_loss":{"stop_price":"${"%.2f".format(java.util.Locale.US, p.stop)}"}}"""
        else """{"symbol":"BTC/USD","qty":"$q","side":"buy","type":"market","time_in_force":"gtc","stop":"${"%.1f".format(java.util.Locale.US, p.stop)}","target":"${"%.1f".format(java.util.Locale.US, p.target)}"}"""
    }

    fun fmtQty(q: Double, m: TradeMarket) = if (m == TradeMarket.BTC) "%.4f".format(java.util.Locale.US, q) else "%.0f".format(java.util.Locale.US, q)

    // ------------------------------------------------------------------ scanner (trader-scanner, versão de lista)

    data class Daily(val t: Long, val c: Double, val h: Double, val l: Double)
    enum class Setup { BREAKOUT_20D, RECLAIM_20MA, OVERSOLD, OVERSOLD_BOUNCE }

    /** Setups de fechamento diário: rompeu a máxima de fechamento de 20 dias, voltou acima da média de 20, sobrevendido (RSI14 < 30). */
    fun dailySetups(d: List<Daily>): List<Setup> {
        if (d.size < 22) return emptyList()
        val c = d.map { it.c }
        val last = c.last(); val prev = c[c.size - 2]
        val out = ArrayList<Setup>()
        if (last > c.subList(c.size - 21, c.size - 1).max()) out += Setup.BREAKOUT_20D
        val ma = c.subList(c.size - 20, c.size).average(); val maPrev = c.subList(c.size - 21, c.size - 1).average()
        if (last > ma && prev <= maPrev) out += Setup.RECLAIM_20MA
        val r = rsi(c, 14); val rPrev = rsi(c.dropLast(1), 14)
        if (r != null && r < 30) out += Setup.OVERSOLD
        if (r != null && rPrev != null && rPrev < 30 && r >= 30) out += Setup.OVERSOLD_BOUNCE
        return out
    }

    fun rsi(c: List<Double>, n: Int): Double? {
        if (c.size <= n) return null
        var g = 0.0; var l = 0.0
        for (i in 1..n) { val d = c[i] - c[i - 1]; if (d > 0) g += d else l -= d }
        g /= n; l /= n
        for (i in n + 1 until c.size) { val d = c[i] - c[i - 1]; g = (g * (n - 1) + max(d, 0.0)) / n; l = (l * (n - 1) + max(-d, 0.0)) / n }
        return if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
    }

    private fun fmt(v: Double) = if (abs(v) >= 1000) "%,.2f".format(NumLocale.current, v) else "%.2f".format(NumLocale.current, v)

    /** Usado pelos testes: arredonda para cima no passo (stop de venda). */
    internal fun ceilTo(x: Double, tick: Double) = ceil(x / tick - 1e-9) * tick
}
