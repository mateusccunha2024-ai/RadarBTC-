package com.cunhac.btcradar.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Atualização 17: os erros vistos no teste do PC (28/11) não podem voltar. */
class JarvisChatTest {
    @Test fun greetingsGetAGreeting() {
        for (q in listOf("olá jarvis", "eae jarvis", "oi, tudo bem?", "bom dia", "valeu!", "quem é você?")) {
            val a = JarvisChat.smallTalk(q, 13)
            assertNotNull(q, a)
            assertFalse(q, a!!.contains("US$"))
        }
        assertTrue(JarvisChat.smallTalk("oi, tudo bem?", 13)!!.startsWith("Boa tarde"))
        // Cumprimento + pergunta de verdade: segue para os dados
        assertNull(JarvisChat.smallTalk("oi, como está o funding?", 13))
        assertNull(JarvisChat.smallTalk("Jarvis, qual o preço do BTC?", 13))
        assertNull(JarvisChat.smallTalk("Como está o BTC agora?", 13))
    }

    @Test fun conceptQuestionsExplainTheConcept() {
        val c = JarvisChat.concept("o que é funding e por que importa?")
        assertEquals("funding", c?.id)
        val (text, used) = JarvisChat.conceptAnswer(c!!, listOf(Fact("Preço do BTC agora: US$ 59.000", "x"), Fact("Funding OKX: +0,0044%", "OKX")))
        assertTrue(text.startsWith("Funding é a taxa"))
        assertTrue(text.contains("Funding OKX: +0,0044%"))
        assertEquals(1, used.size)
        assertEquals("oi", JarvisChat.concept("o que significa open interest?")?.id)
        assertEquals("timemachine", JarvisChat.concept("explica o time machine")?.id)
        assertNull(JarvisChat.concept("qual o funding agora?"))       // não pede explicação: vai para os dados
    }

    @Test fun answersFocusOnTheAskedTopic() {
        val facts = listOf(Fact("Preço do BTC agora: US$ 59.319,4", "x"), Fact("Variação do BTC em 5 min: +0,11%", "x"),
            Fact("Open interest somado (3 corretoras): 202.137 BTC", "x"), Fact("Funding OKX: +0,0044%", "OKX"), Fact("Funding Bybit: -0,0015%", "Bybit"))
        val a = JarvisChat.ruleAnswer(facts, setOf(Topic.DERIV))
        assertTrue(a, a.startsWith("Open interest") || a.startsWith("Funding"))
        assertFalse(a, a.contains("Preço do BTC"))
        // "Quanto está o funding?": o "quanto está" não pode puxar o preço para a frente (visto nas capturas)
        assertFalse(Topic.PRICE in JarvisFacts.topics("Quanto está o funding?"))
        assertTrue(Topic.DERIV in JarvisFacts.topics("Quanto está o funding?"))
        assertTrue(Topic.PRICE in JarvisFacts.topics("Quanto está o bitcoin?"))
        assertTrue(Topic.PRICE in JarvisFacts.topics("Qual o preço e o funding?"))
        val all = JarvisChat.ruleAnswer(facts, setOf(Topic.SUMMARY))
        assertTrue(all.startsWith("Preço do BTC"))
    }

    @Test fun speechIsShort() {
        val long = "Preço do BTC agora: US$ 59.226,0. Variação do BTC em 5 min: -0,05%. Variação do BTC em 1 h: -0,05%. Open interest somado: 202.066 BTC. Funding OKX: +0,0047%."
        val s = JarvisChat.forSpeech(long)
        assertTrue(s, s.length < long.length)
        assertTrue(s.endsWith("Os detalhes estão na tela."))
        assertEquals("Oi.", JarvisChat.forSpeech("Oi."))
    }

    @Test fun silenceIsNotSpeech() {
        val r = java.util.Random(7)
        val noise = FloatArray(16_000 * 3) { (r.nextGaussian() * 0.004).toFloat() }
        assertFalse(JarvisChat.hasSpeech(noise))
        val voice = noise.copyOf()
        for (i in 16_000 until 16_000 + 12_000) voice[i] += (0.2 * sin(2 * PI * 220 * i / 16_000.0)).toFloat()
        assertTrue(JarvisChat.hasSpeech(voice))
        assertTrue(JarvisChat.suspicious("Eu não sou de você."))
        assertTrue(JarvisChat.suspicious("Obrigado."))
        assertFalse(JarvisChat.suspicious("qual o preço do bitcoin"))
    }

    @Test fun curiosityIgnoresSongsAndKeepsMarketTerms() {
        val song = JarvisCuriosity.Concept("Could It Be Magic", "Could It Be Magic", "\"Could It Be Magic\" é uma canção de Barry Manilow lançada em 1973.", "u", "pt", "canção de Barry Manilow")
        assertFalse(JarvisCuriosity.relevant(song))
        val nvda = JarvisCuriosity.Concept("Nvidia", "Nvidia", "Nvidia Corporation é uma empresa multinacional de tecnologia que fabrica chips gráficos e de inteligência artificial.", "u", "pt", "empresa de tecnologia")
        assertTrue(JarvisCuriosity.relevant(nvda))
        val noDesc = JarvisCuriosity.Concept("X", "X", "Uma palavra qualquer sem contexto nenhum.", "u", "pt")
        assertFalse(JarvisCuriosity.relevant(noDesc))
    }
}
