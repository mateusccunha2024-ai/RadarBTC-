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

    // ------------------------------------------------------------------ curiosidade e proteção contra fechamento

    @Test fun curiosityFindsNewRecurringNames() {
        val news = listOf(
            "Trump picks Kevin Warsh to lead the Federal Reserve - Reuters" to "Reuters",
            "Markets weigh what Kevin Warsh means for rates - CNBC" to "CNBC",
            "Bitcoin jumps as Warsh nomination sinks in - CoinDesk" to "CoinDesk",
            "Stocks Rise After Fed Minutes - Bloomberg" to "Bloomberg",
            "Stocks Rise Again On Tech - Bloomberg" to "Bloomberg",
            "Stocks Rise For A Third Day - Bloomberg" to "Bloomberg",
            "Polymarket volume hits record - The Block" to "The Block",
        )
        val c = JarvisCuriosity.candidates(news, known = emptySet())
        assertTrue("candidatos: $c", c.firstOrNull() == "Warsh" || c.firstOrNull() == "Kevin Warsh")
        assertTrue("palavra comum não é termo: $c", c.none { it.contains("Stocks") || it.contains("Rise") })
        assertTrue("uma fonte só não basta: $c", c.none { it.contains("Polymarket") })
        assertTrue("já conhecido não repete", JarvisCuriosity.candidates(news, known = setOf("Kevin Warsh")).none { it.contains("Warsh") })
        assertEquals("Trump picks Kevin Warsh to lead the Federal Reserve", JarvisCuriosity.cleanTitle("Trump picks Kevin Warsh to lead the Federal Reserve - Reuters"))
    }

    @Test fun curiosityReadsWikipediaWithoutGuessing() {
        val search = """{"pages":[{"id":1,"key":"Kevin_Warsh","title":"Kevin Warsh","description":"American financier"}]}"""
        assertEquals("Kevin_Warsh", JarvisCuriosity.parseSearch(search, "Kevin Warsh"))
        assertEquals(null, JarvisCuriosity.parseSearch("""{"pages":[{"key":"Warsaw","title":"Warsaw"}]}""", "Kevin Warsh"))
        val sum = """{"type":"standard","title":"Kevin Warsh","extract":"Kevin Maxwell Warsh is an American financier. He served on the Federal Reserve Board of Governors from 2006 to 2011. He later taught at Stanford.","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Kevin_Warsh"}}}"""
        val c = JarvisCuriosity.parseSummary(sum, "Kevin Warsh", "en")!!
        assertEquals("https://en.wikipedia.org/wiki/Kevin_Warsh", c.url)
        assertTrue(c.text.startsWith("Kevin Maxwell Warsh is an American financier. He served"))
        assertTrue("só 2 frases", !c.text.contains("Stanford"))
        assertEquals(null, JarvisCuriosity.parseSummary("""{"type":"disambiguation","title":"X","extract":"X may refer to many different things in this long text."}""", "X", "en"))
    }

    @Test fun crashSummaryIsShortAndReadable() {
        val lines = listOf("123", "main", "painel=aberto", "java.lang.IllegalStateException: Vertically scrollable component was measured", "\tat a.b(C.kt:1)")
        assertEquals("IllegalStateException: Vertically scrollable component was measured", JarvisCrash.summary(lines))
        assertEquals("OutOfMemoryError", JarvisCrash.summary(listOf("1", "t", "painel=fechado", "java.lang.OutOfMemoryError")))
    }
}
