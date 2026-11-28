package com.cunhac.btcradar.desktop

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A IA do Jarvis roda num processo separado: se ela morrer, o app continua e recebe o motivo.
 * Com JARVIS_TEST_MODEL (caminho de um .gguf) o teste também carrega o modelo e pede uma resposta de verdade.
 */
class JarvisLlmProcessTest {
    private val dir: File = Files.createTempDirectory("jarvis-ia").toFile()

    @Test
    fun processoDaIaCaiESoEleMorre() {
        LlmProcess.dir = { dir }
        assertEquals("o processo da IA deve iniciar", null, LlmProcess.start())
        assertTrue(LlmProcess.alive)
        assertEquals(listOf("ok"), LlmProcess.call(30_000, "ping"))
        // Pedido sem modelo carregado: erro normal, o processo continua vivo
        assertEquals("err", LlmProcess.call(30_000, "complete", "oi", "5", "0.1").first())
        assertTrue(LlmProcess.alive)

        val model = System.getenv("JARVIS_TEST_MODEL")?.takeIf { File(it).isFile }
        if (model != null) {
            val t0 = System.currentTimeMillis()
            assertEquals(listOf("ok"), LlmProcess.call(300_000, "load", model, "4096", "2"))
            val p = "<|im_start|>system\nAnswer with one short word.<|im_end|>\n<|im_start|>user\nSay hello.<|im_end|>\n<|im_start|>assistant\n"
            val r = LlmProcess.call(300_000, "complete", p, "8", "0.1", "<|im_end|>", "<|im_start|>")
            println("IA no processo separado: carregou e respondeu em ${(System.currentTimeMillis() - t0) / 1000} s: ${r.getOrNull(1)}")
            assertEquals("ok", r.first())
            assertTrue("a IA deve responder algo", r.getOrNull(1).orEmpty().isNotBlank())
        }

        // Simula a IA morrendo no meio (como um erro nativo): o próximo pedido falha com motivo, este processo segue vivo
        LlmProcess.crashForTest()
        try {
            LlmProcess.call(30_000, "ping")
            fail("o pedido deveria falhar depois da queda")
        } catch (e: IOException) {
            println("motivo mostrado: ${e.message}")
            assertNotNull(e.message)
            assertTrue(e.message, e.message!!.startsWith("a IA fechou sozinha"))
        }
        assertFalse(LlmProcess.alive)
        assertNotNull(LlmProcess.crash)

        // Reinicia normalmente depois da queda
        LlmProcess.crash = null
        assertEquals(null, LlmProcess.start())
        assertEquals(listOf("ok"), LlmProcess.call(30_000, "ping"))
        LlmProcess.stop()
        assertFalse(LlmProcess.alive)
    }
}
