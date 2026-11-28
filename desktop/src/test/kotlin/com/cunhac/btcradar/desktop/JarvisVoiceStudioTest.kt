package com.cunhac.btcradar.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Atualização 18: a voz Jarvis pelo VoiceStudio (API local compatível com a OpenAI). */
class JarvisVoiceStudioTest {

    @Test fun requestAsksForTheJarvisVoiceInRawPcm() {
        val o = Json.parseToJsonElement(JarvisVoiceStudio.body("Olá, senhor.", "pt", 1.1f)).jsonObject
        assertEquals("omnivoice", o["model"]!!.jsonPrimitive.content)
        assertEquals("Olá, senhor.", o["input"]!!.jsonPrimitive.content)
        assertEquals("pcm", o["response_format"]!!.jsonPrimitive.content)
        assertEquals("pt", o["language"]!!.jsonPrimitive.content)
        assertEquals(JarvisVoiceStudio.SEED, o["seed"]!!.jsonPrimitive.content.toInt())
        val tags = o["instruct"]!!.jsonPrimitive.content.split(", ")
        assertEquals(listOf("male", "middle-aged", "low pitch", "british accent"), tags)
        assertEquals("en", Json.parseToJsonElement(JarvisVoiceStudio.body("hi", "en-gb", 9f)).jsonObject["language"]!!.jsonPrimitive.content)
        assertEquals(4.0, Json.parseToJsonElement(JarvisVoiceStudio.body("hi", "en", 9f)).jsonObject["speed"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun pcmDecodes() {
        val b = byteArrayOf(0, 0, 0xFF.toByte(), 0x7F, 0, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val f = JarvisVoiceStudio.pcmToFloat(b)
        assertEquals(4, f.size)
        assertEquals(0f, f[0], 0f); assertEquals(32767 / 32768f, f[1], 1e-6f); assertEquals(-1f, f[2], 0f); assertEquals(-1 / 32768f, f[3], 1e-6f)
    }

    @Test fun addressIsChecked() {
        assertEquals(JarvisVoiceStudio.DEFAULT_URL, JarvisVoiceStudio.normalize(""))
        assertEquals("http://127.0.0.1:3900", JarvisVoiceStudio.normalize(" http://127.0.0.1:3900/ "))
        assertNull(JarvisVoiceStudio.normalize("file:///etc/passwd"))
        assertNull(JarvisVoiceStudio.normalize("http://user:pw@127.0.0.1:3900"))
        assertTrue(JarvisVoiceStudio.isLoopback("http://localhost:3900"))
        assertFalse(JarvisVoiceStudio.isLoopback("http://192.168.0.10:3900"))
    }

    @Test fun speaksSentenceBySentenceThroughAFakeVoiceStudio() {
        val bodies = CopyOnWriteArrayList<String>()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/.well-known/voicestudio-speech") { ex -> ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("{}".toByteArray()) } }
        srv.createContext("/v1/audio/speech") { ex ->
            bodies += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val pcm = ByteArray(2400 * 2) { if (it % 2 == 1) 0x10 else 0 }
            ex.sendResponseHeaders(200, pcm.size.toLong()); ex.responseBody.use { it.write(pcm) }
        }
        srv.start()
        try {
            val url = "http://127.0.0.1:${srv.address.port}"
            assertTrue(JarvisVoiceStudio.probe(url))
            assertEquals(JarvisVoiceStudio.Status.ONLINE, JarvisVoiceStudio.status)
            val a = JarvisVoiceStudio.synth("Primeira frase.", "pt", 1f, url)
            assertNotNull(a); assertEquals(2400, a!!.size)
            bodies.clear()
            val played = mutableListOf<Int>()
            val ok = JarvisVoiceStudio.speak("O bitcoin subiu dois por cento hoje. O funding está neutro agora. As liquidações ficaram abaixo do preço.", "pt", 1f, url) { s, rate -> assertEquals(24_000, rate); played += s.size; true }
            assertTrue(ok)
            assertEquals(listOf(2400, 2400, 2400), played)
            assertEquals(3, bodies.size)
            // parar no meio: não gera mais nada além da frase já pedida
            played.clear()
            JarvisVoiceStudio.speak("O bitcoin subiu dois por cento hoje. O funding está neutro agora.", "pt", 1f, url) { s, _ -> played += s.size; false }
            assertEquals(1, played.size)
            assertTrue(bodies.all { it.contains("\"response_format\":\"pcm\"") && it.contains("british accent") })
        } finally { srv.stop(0) }
    }

    @Test fun closedVoiceStudioFailsFastSoKokoroTakesOver() {
        val t0 = System.currentTimeMillis()
        assertFalse(JarvisVoiceStudio.probe("http://127.0.0.1:1"))
        assertEquals(JarvisVoiceStudio.Status.OFFLINE, JarvisVoiceStudio.status)
        assertNull(JarvisVoiceStudio.synth("Oi.", "pt", 1f, "http://127.0.0.1:1"))
        assertNotNull(JarvisVoiceStudio.lastError)
        assertTrue("demorou demais para desistir", System.currentTimeMillis() - t0 < 10_000)
    }
}
