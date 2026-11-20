package com.cunhac.btcradar.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Carga sintética isolada (não são dados reais): mede custo de ingestão e de cálculo. */
class LoadTest {
    @Test fun flowAndBookUnderLoad() {
        val rt = Runtime.getRuntime()
        System.gc(); val mem0 = rt.totalMemory() - rt.freeMemory()
        val flow = FlowEngine()
        val now = System.currentTimeMillis()
        val n = 300_000
        val t0 = System.nanoTime()
        for (i in 0 until n) flow.add(TradeEvent(Venue.entries[i % 3], 60_000.0 + (i % 500) * 0.1, 0.001 + (i % 50) * 0.01,
            if (i % 2 == 0) Aggressor.BUY else Aggressor.SELL, now, recvMs = now - (n - i) * 10L))
        val ingestMs = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        repeat(10) { flow.allWindows(null, now); Venue.entries.forEach { v -> flow.allWindows(v, now) } }
        val windowsMs = (System.nanoTime() - t1) / 1e6 / 10

        val book = LocalBook(Venue.BINANCE)
        book.reset((0 until 1000).map { Level(60_000.0 - it * 0.1, 1.0) }, (0 until 1000).map { Level(60_000.1 + it * 0.1, 1.0) }, 1, 0)
        val t2 = System.nanoTime()
        for (i in 0 until 50_000) book.apply(listOf(Level(59_990.0 - (i % 300) * 0.1, (i % 7).toDouble())), listOf(Level(60_010.0 + (i % 300) * 0.1, (i % 5).toDouble())), i + 2L, 0)
        val bookUs = (System.nanoTime() - t2) / 1e3 / 50_000
        val t3 = System.nanoTime(); repeat(20) { book.view() }; val viewMs = (System.nanoTime() - t3) / 1e6 / 20
        System.gc(); val memMb = (rt.totalMemory() - rt.freeMemory() - mem0) / 1_048_576.0

        val rep = "## Teste de carga (sintético)\n\n- Ingestão de $n trades: %.0f ms (%.2f µs/trade)\n- 7 janelas × 4 fontes sobre $n trades: %.1f ms por publicação\n- Livro: %.2f µs por atualização incremental; cópia do livro completo: %.2f ms\n- Memória retida aprox.: %.0f MB\n"
            .format(ingestMs, ingestMs * 1000 / n, windowsMs, bookUs, viewMs, memMb)
        File("build/load-report.md").apply { parentFile.mkdirs() }.writeText(rep)
        println(rep)
        assertTrue("cálculo das janelas deve caber folgado em 1,5 s", windowsMs < 750)
        assertTrue("livro deve aplicar atualizações em menos de 50 µs", bookUs < 50)
    }
}
