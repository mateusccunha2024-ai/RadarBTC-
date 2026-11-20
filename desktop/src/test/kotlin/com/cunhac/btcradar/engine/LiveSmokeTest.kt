package com.cunhac.btcradar.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Teste com DADOS REAIS (só roda com LIVE=1). Conecta nas três corretoras por 45 s e
 * grava um relatório. Não falha o build: bloqueios geográficos do servidor de CI são reportados.
 */
class LiveSmokeTest {
    @Test fun live45s() {
        assumeTrue(System.getenv("LIVE") == "1")
        val hub = Hub(refreshMs = 1_500)
        hub.start()
        Thread.sleep(45_000)
        val s = hub.stateFlow.value
        val sb = StringBuilder("## Teste ao vivo (45 s, dados reais)\n\n")
        sb.append("Preço consolidado: ${s.price}\n\nFontes LIVE (conexão + livro válido): ${s.liveCount()}/3\n\n")
        sb.append("| Corretora | Livro válido | Níveis bid/ask | Latência ms | Ressincs | Nota |\n|---|---|---|---|---|---|\n")
        for ((v, b) in s.books) sb.append("| ${v.label} | ${b.valid} | ${b.bidLevels}/${b.askLevels} | ${b.latencyMs} | ${b.resyncs} | ${b.note} |\n")
        s.rpi?.let { sb.append("| Bybit RPI | ${it.valid} | ${it.bidLevels}/${it.askLevels} | ${it.latencyMs} | ${it.resyncs} | ${it.note} |\n") }
        sb.append("\n| Canal | Estado | Mensagens | Reconexões | Último erro |\n|---|---|---|---|---|\n")
        for (r in s.statuses) sb.append("| ${r.venue.label}: ${r.channel} | ${r.state} | ${r.messages} | ${r.reconnects} | ${r.lastError ?: ""} |\n")
        val f = s.flowAll.firstOrNull { it.windowMs == 60_000L }
        sb.append("\nTrades recebidos: ${s.tradesTotal}; último 1m: compra ${f?.buyBtc} BTC, venda ${f?.sellBtc} BTC\n")
        sb.append("\nTickers: " + s.tickers.entries.joinToString { "${it.key.label}: mark=${it.value.markPrice} OI=${it.value.oiBtc} funding=${it.value.fundingRate}" } + "\n")
        sb.append("\nLiquidações recebidas: ${s.liqTotal}\n\nOKX ctVal: ${s.okxCtVal}\n\nParedes ativas: ${hub.walls.activeCount()}\n")
        sb.append("\n### Log\n```\n" + hub.logs(60).reversed().joinToString("\n") { "${it.level} ${it.venue?.label ?: "-"} ${it.text}" } + "\n```\n")
        hub.stop()
        File("build/live-report.md").apply { parentFile.mkdirs() }.writeText(sb.toString())
        println(sb)
    }
}
