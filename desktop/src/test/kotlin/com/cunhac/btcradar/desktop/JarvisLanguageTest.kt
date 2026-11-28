package com.cunhac.btcradar.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Atualização 18: o Jarvis fala português e troca de idioma quando a pessoa pede. */
class JarvisLanguageTest {

    private fun code(q: String) = JarvisLanguage.detect(q)?.code

    @Test fun switchesWhenAsked() {
        assertEquals("en", code("Fale em inglês"))
        assertEquals("en", code("fala inglês comigo"))
        assertEquals("en", code("Speak English, please"))
        assertEquals("en", code("english please"))
        assertEquals("es", code("Responda em espanhol"))
        assertEquals("es", code("habla en español"))
        assertEquals("fr", code("parle français"))
        assertEquals("de", code("sprich Deutsch"))
        assertEquals("it", code("pode falar em italiano?"))
        assertEquals("ja", code("fale em japonês"))
        assertEquals("zh", code("fale em chinês"))
        assertEquals("ko", code("fale em coreano"))
        assertEquals("ru", code("fale em russo"))
        assertEquals("pt", code("volte a falar português"))
        assertEquals("pt", code("go back to Portuguese"))
        assertEquals("pt", code("Volte para o português"))
    }

    @Test fun commandAloneHasNoQuestion() {
        assertEquals("", JarvisLanguage.detect("Fale em inglês")!!.rest)
        assertEquals("", JarvisLanguage.detect("a partir de agora fale em inglês por favor")!!.rest)
    }

    @Test fun questionThatCameAlongIsKept() {
        val s = JarvisLanguage.detect("Fale em inglês: como está o funding?")
        assertNotNull(s); assertEquals("en", s!!.code); assertEquals("como está o funding?", s.rest)
        val t = JarvisLanguage.detect("me fala as notícias em inglês")
        assertNotNull(t); assertEquals("en", t!!.code); assertTrue(t.rest.contains("notícias"))
    }

    @Test fun ordinaryQuestionsDoNotSwitch() {
        assertNull(code("Como está o BTC agora?"))
        assertNull(code("o mercado chinês caiu hoje?"))
        assertNull(code("como se diz liquidação em inglês?"))
        assertNull(code("o que é funding em inglês?"))
        assertNull(code("quanto está o funding?"))
        assertNull(code("plano de trade TSLA"))
    }

    @Test fun oneJarvisVoicePerLanguage() {
        assertEquals("pm_alex", JarvisVoices.jarvis("pt")!!.name)
        assertEquals("bm_george", JarvisVoices.jarvis("en")!!.name)
        assertEquals("em_alex", JarvisVoices.jarvis("es")!!.name)
        assertNull(JarvisVoices.jarvis("de"))
        assertEquals("Very well, sir. I'll speak English from now on.", JarvisLanguage.confirm("en"))
        assertEquals("Brazilian Portuguese", JarvisLanguage.llmName(JarvisLanguage.DEFAULT))
    }
}
