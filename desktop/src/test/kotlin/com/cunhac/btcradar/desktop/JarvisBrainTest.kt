package com.cunhac.btcradar.desktop

import com.cunhac.btcradar.engine.Bar5
import com.cunhac.btcradar.engine.TradeMarket
import com.cunhac.btcradar.engine.TraderSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Atualização 14: checagem de números, ferramentas, placar e a prova de roteamento. */
class JarvisBrainTest {
    @Test fun numberReadings() {
        assertTrue(59930.6 in JvText.readings("59.930,6"))
        assertTrue(59930.6 in JvText.readings("59,930.6"))
        assertTrue(0.0624 in JvText.readings("0,0624"))
        assertTrue(1500.0 in JvText.readings("1.500"))
        assertTrue(-0.04 in JvText.readings("-0,04"))
    }

    @Test fun groundingCatchesInventedNumbers() {
        val facts = listOf("Preço do BTC agora: US$ 59.930,6", "Funding Binance: +0,0087%", "Variação do BTC em 1 h: -0,04%")
        assertTrue(Grounding.ungrounded("O BTC está em US$ 59.930,6, com funding de 0,0087% e queda de 0,04% em 1 hora.", facts).isEmpty())
        assertTrue(Grounding.ungrounded("BTC at $59,930.6 right now.", facts).isEmpty())
        assertEquals(listOf("61.200"), Grounding.ungrounded("O BTC deve ir a 61.200 hoje.", facts))
        assertEquals(listOf("0,25"), Grounding.ungrounded("O funding subiu para 0,25%.", facts))
    }

    @Test fun toolCallsAreParsedSafely() {
        val c = JarvisTools.parse("dados fluxo\n- comparar BTC QQQ\nplano TSLA 1 3R\nrm -rf /\nNONE")
        assertEquals(listOf("dados", "comparar", "plano"), c.map { it.name })
        assertEquals(listOf("btc", "qqq"), c[1].args)
        assertEquals(listOf("tsla", "1", "3r"), c[2].args)
        assertTrue(JarvisTools.parse("I think we should look at the data").isEmpty())
    }

    @Test fun correlationOfIdenticalSeriesIsOne() {
        val d = (0 until 40).map { TraderSkill.Daily(it * 86_400_000L, 100.0 + (it % 5) * 1.3 + it * 0.2, 0.0, 0.0) }
        assertEquals(1.0, JarvisTools.correlation(d, d)!!, 1e-9)
    }

    @Test fun scoreOutcome() {
        val ny = ZoneId.of("America/New_York")
        val d = LocalDate.of(2026, 10, 19)
        fun bars(path: (Int) -> Double) = (0 until 78).map { i ->
            val t = d.atTime(9, 30).atZone(ny).toInstant().toEpochMilli() + i * 300_000L
            val p = path(i); Bar5(t, p, p + 0.05, p - 0.05, p)
        }
        val up = TraderSkill.sessions(bars { 100.0 + it * 0.1 }, TradeMarket.US_STOCK).first()
        assertEquals("target", TraderSkill.outcome(up, TradeMarket.US_STOCK, 101.0, 100.5, 102.0, 10, false))
        val down = TraderSkill.sessions(bars { 101.0 - it * 0.1 }, TradeMarket.US_STOCK).first()
        assertEquals("stop", TraderSkill.outcome(down, TradeMarket.US_STOCK, 100.0, 99.5, 101.0, 10, false))
        assertEquals("no_trigger", TraderSkill.outcome(down, TradeMarket.US_STOCK, 105.0, 104.0, 107.0, 3, true))
    }

    @Test fun routingExamPasses() {
        val (ok, total) = JarvisEval.routing()
        val misses = JarvisEval.CASES.filter { c -> Jarvis.route(c.q).let { r -> r.kind != c.route || (c.symbol != null && r.symbol != c.symbol) } }.map { it.q }
        assertTrue("roteamento $ok/$total; erros: $misses", ok >= total - 2)
    }
}
