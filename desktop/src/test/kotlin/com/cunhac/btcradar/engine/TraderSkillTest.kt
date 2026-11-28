package com.cunhac.btcradar.engine

import com.cunhac.btcradar.desktop.JarvisFacts
import com.cunhac.btcradar.desktop.JarvisText
import com.cunhac.btcradar.desktop.Topic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Fluxo do day-trader-skill portado: sessões, gatilho, stop/alvo, Time Machine, tamanho, risco e boleta. */
class TraderSkillTest {
    private val ny = ZoneId.of("America/New_York")

    /** Sessão sintética de 78 candles (9h30–16h NY) com uma tendência leve e ruído determinístico. */
    private fun day(d: LocalDate, start: Double, drift: Double, seed: Int, upTo: Int = 78): List<Bar5> {
        val r = java.util.Random(seed.toLong())
        var p = start
        return (0 until upTo).map { i ->
            val t = d.atTime(9, 30).atZone(ny).toInstant().toEpochMilli() + i * 300_000L
            val o = p; val c = p * (1 + drift + (r.nextDouble() - 0.5) * 0.004)
            val h = maxOf(o, c) * (1 + r.nextDouble() * 0.001); val l = minOf(o, c) * (1 - r.nextDouble() * 0.001)
            p = c; Bar5(t, o, h, l, c, 1000.0)
        }
    }

    private fun past(n: Int, end: LocalDate): List<Bar5> {
        val out = ArrayList<Bar5>(); var d = end.minusDays(1); var k = 0
        while (k < n) { if (TraderClock.tradingDay(d) && d.toString() !in TraderClock.EARLY_CLOSE) { out += day(d, 100.0 + k, if (k % 2 == 0) 0.0004 else -0.0002, k); k++ }; d = d.minusDays(1) }
        return out
    }

    @Test fun sessionsAndEntry() {
        val d = LocalDate.of(2026, 10, 19)
        val bars = past(30, d) + day(d, 100.0, 0.0012, 99, upTo = 20)
        val ss = TraderSkill.sessions(bars, TradeMarket.US_STOCK)
        assertEquals(31, ss.size)
        assertEquals(30, ss.count { it.full })
        val today = ss.first { it.date == d }
        assertEquals(20, today.count())
        val now = bars.last().t + 300_000L
        val e = TraderSkill.entry(today, TradeMarket.US_STOCK, 15, now)
        assertEquals(TraderSkill.EntryStatus.TRIGGERED, e.status)
        assertTrue(e.price!! > e.orHigh!!)
        assertTrue(e.slot!! >= 3)
    }

    @Test fun exitPlanStopAndTarget() {
        val d = LocalDate.of(2026, 10, 19)
        val e = TraderSkill.Entry(TraderSkill.EntryStatus.TRIGGERED, 364.0, 352.64, 9, 364.18, 0L, 364.18, 0.05, 10)
        val r = TraderSkill.exitPlan("TSLA", TradeMarket.US_STOCK, d, e) as TraderSkill.ExitResult.Ok
        assertEquals(358.32, r.plan.stop, 1e-9)                 // meio da abertura, arredondado para baixo
        assertEquals(364.18 + 2 * (364.18 - 358.32), r.plan.target, 0.006)
        val bad = TraderSkill.exitPlan("TSLA", TradeMarket.US_STOCK, d, e.copy(orLow = 364.1, orHigh = 364.2))
        assertTrue(bad is TraderSkill.ExitResult.Refused)
    }

    @Test fun timeMachineIsDeterministicAndConsistent() {
        val d = LocalDate.of(2026, 10, 19)
        val full = TraderSkill.sessions(past(40, d), TradeMarket.US_STOCK).filter { it.full }
        val pool = TraderSkill.pool(full, TradeMarket.US_STOCK)
        val a = TraderSkill.simulate(pool, TradeMarket.US_STOCK, 100.0, 99.0, 102.0, 9, 76)!!
        val b = TraderSkill.simulate(pool, TradeMarket.US_STOCK, 100.0, 99.0, 102.0, 9, 76)!!
        assertEquals(a.pTarget, b.pTarget, 0.0)
        assertEquals(1.0, a.pTarget + a.pStop + a.pNeither, 1e-9)
        assertEquals(2.0, a.rAtTarget, 1e-9)
        assertEquals(68, a.bands.size)
        assertTrue(a.bands.all { it[0] <= it[2] && it[2] <= it[4] })
        val h = TraderSkill.history(full, TradeMarket.US_STOCK, 15, TraderSkill.StopRule.OR_MID, 1.0, 2.0, 76)
        assertEquals(40, h.sessions)
        assertEquals(h.triggered, h.target + h.stop + h.close)
    }

    @Test fun sizingRiskAndTicket() {
        val d = LocalDate.of(2026, 10, 19)
        val p = TraderSkill.Plan("TSLA", TradeMarket.US_STOCK, d, 364.18, 9, null, 358.32, 375.90, 2.0, TraderSkill.StopRule.OR_MID, 364.0, 352.64, 15)
        val s = TraderSkill.size(p, TraderSkill.Rules())
        assertEquals(8.0, s.qty, 0.0)                              // US$ 50 / 5,86 por ação = 8 (o exemplo do vídeo)
        assertEquals("perda máxima por trade", s.boundBy)
        assertNull(s.refusal)
        val now = d.atTime(10, 20).atZone(ny).toInstant().toEpochMilli()
        val ok = TraderSkill.risk(p, s, TraderSkill.Rules(), 0.0, 0, now)
        assertTrue(ok.checks.joinToString { it.name + "=" + it.pass }, ok.pass)
        val blocked = TraderSkill.risk(p, s, TraderSkill.Rules(), 140.0, 0, now)
        assertFalse(blocked.pass)
        assertTrue(TraderSkill.ticket(p, s).contains("\"order_class\":\"bracket\""))
        val tooFar = TraderSkill.size(p.copy(stop = 300.0), TraderSkill.Rules())
        assertNotNull(tooFar.refusal)
        assertEquals(0.0, tooFar.qty, 0.0)
    }

    /** Caso real (28/11): BTC rompeu em 60.069,8, stop 59.965, e o preço já estava em 59.283. Não pode PASSAR. */
    @Test fun stopAlreadyHitBlocksTheTrade() {
        val d = LocalDate.of(2026, 11, 28)
        val t0 = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val px = doubleArrayOf(59_950.0, 60_000.0, 59_980.0, 60_069.8, 60_010.0, 59_900.0, 59_500.0, 59_283.0)
        val bars = px.mapIndexed { i, c -> Bar5(t0 + i * 300_000L, c, c + 30, c - 30, c, 1.0) }
        val today = TraderSkill.sessions(bars, TradeMarket.BTC).first()
        val now = t0 + px.size * 300_000L
        val p = TraderSkill.Plan("BTC", TradeMarket.BTC, d, 60_069.8, 3, null, 59_965.0, 60_279.4, 2.0, TraderSkill.StopRule.OR_MID, 60_030.0, 59_920.0, 15)
        val pr = TraderSkill.progress(today, p, now, 59_283.0)
        assertEquals(TraderSkill.ProgressStatus.STOPPED, pr.status)
        assertEquals(t0 + 6 * 300_000L, pr.timeMs)                // o candle das 0h25 (fecha 0h30) foi o primeiro a tocar o stop
        val s = TraderSkill.size(p, TraderSkill.Rules(), 59_283.0)
        assertNotNull(s.refusal)
        val v = TraderSkill.risk(p, s, TraderSkill.Rules(), 0.0, 0, now, 59_283.0, pr)
        assertFalse(v.pass)
        assertFalse(v.checks.first { it.name == "Preço atual acima do stop" }.pass)
        assertFalse(v.checks.first { it.name == "Stop e alvo ainda não atingidos hoje" }.pass)
        // Ainda em aberto: passa nessas duas checagens
        val open = TraderSkill.progress(today, p.copy(stop = 59_000.0), now, 59_283.0)
        assertEquals(TraderSkill.ProgressStatus.OPEN, open.status)
    }

    @Test fun btcSessionsAreUtcDays() {
        val d = LocalDate.of(2026, 11, 20)
        val t0 = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val bars = (0 until 288 * 2).map { i -> Bar5(t0 + i * 300_000L, 1.0, 1.0, 1.0, 1.0) }
        val ss = TraderSkill.sessions(bars, TradeMarket.BTC)
        assertEquals(2, ss.size); assertTrue(ss.all { it.full })
        assertEquals(286, TradeMarket.BTC.lastSlot)
        assertEquals(76, TradeMarket.US_STOCK.lastSlot)
        val s = TraderSkill.size(TraderSkill.Plan("BTC", TradeMarket.BTC, d, 90_000.0, 10, null, 89_500.0, 91_000.0, 2.0, TraderSkill.StopRule.OR_MID, 0.0, 0.0, 15), TraderSkill.Rules())
        assertEquals(0.0416, s.qty, 1e-9)                             // teto de 75% de US$ 5.000 a US$ 90.000 (a perda máx. deixaria 0,1 BTC)
        assertEquals("teto da posição", s.boundBy)
    }

    @Test fun nyseCalendar() {
        val thanksgiving = LocalDate.of(2026, 11, 26).atTime(11, 0).atZone(ny).toInstant().toEpochMilli()
        assertFalse(TraderClock.usOpen(thanksgiving))
        val next = TraderClock.ny(TraderClock.nextUsOpen(thanksgiving))
        assertEquals(LocalDate.of(2026, 11, 27), next.toLocalDate())
        assertEquals(13 * 60, TraderClock.closeMin(LocalDate.of(2026, 11, 27)))
    }

    @Test fun assistantUnderstandsQuestions() {
        assertTrue(Topic.TRADE in JarvisFacts.topics("Monta um plano de trade para a TSLA"))
        assertEquals("TSLA", JarvisFacts.symbol("Monta um plano de trade para a TSLA"))
        assertEquals("NVDA", JarvisFacts.symbol("time machine na nvidia"))
        assertEquals("BTC", JarvisFacts.symbol("qual o preço do bitcoin?"))
        assertTrue(Topic.PRICE in JarvisFacts.topics("qual o preço do bitcoin?"))
        assertTrue(Topic.LIQ in JarvisFacts.topics("Liquidações da última hora"))
        assertTrue(Topic.MACRO in JarvisFacts.topics("o que o Fed fez com os juros?"))
        assertTrue(Topic.SCAN in JarvisFacts.topics("Scanner de ações"))
        assertNull(JarvisFacts.symbol("como está o mercado hoje?"))
        assertEquals(listOf("Olá, tudo certo por aqui.", "O preço subiu 2% hoje."), JarvisText.sentences("**Olá**, tudo certo por aqui. O preço subiu 2% hoje."))
    }
}
