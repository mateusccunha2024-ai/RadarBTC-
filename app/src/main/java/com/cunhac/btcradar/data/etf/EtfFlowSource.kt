package com.cunhac.btcradar.data.etf

import com.cunhac.btcradar.data.model.EtfDay
import com.cunhac.btcradar.data.net.Net
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/**
 * Fonte dos fluxos diários dos ETFs spot de Bitcoin (EUA).
 * Fica atrás de uma interface para trocar de provedor depois
 * (ex.: SoSoValue ou um backend próprio) sem mexer na tela.
 */
interface EtfFlowSource {
    suspend fun fetch(): List<EtfDay>
}

/**
 * Lê a tabela pública da Farside Investors (valores em US$ milhões,
 * negativos entre parênteses). Não é uma API oficial: se o site mudar
 * o layout, só este parser precisa ser ajustado.
 */
object FarsideEtfSource : EtfFlowSource {
    private const val URL = "https://farside.co.uk/bitcoin-etf-flow-all-data/"
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    override suspend fun fetch(): List<EtfDay> = parse(Net.getText(URL))

    fun parse(html: String): List<EtfDay> {
        val table = Jsoup.parse(html).select("table").maxByOrNull { it.select("tr").size }
            ?: error("Tabela de ETFs não encontrada")

        var header: List<String>? = null
        val days = mutableListOf<EtfDay>()

        for (tr in table.select("tr")) {
            val cells = tr.select("th, td").map { it.text().trim() }
            if (cells.isEmpty()) continue
            if (header == null && cells.any { it == "IBIT" }) { header = cells; continue }
            val h = header ?: continue
            val date = parseDate(cells[0]) ?: continue

            val flows = linkedMapOf<String, Double>()
            var total: Double? = null
            for (i in 1 until min(cells.size, h.size)) {
                val v = parseNumber(cells[i]) ?: continue
                when {
                    h[i].equals("Total", ignoreCase = true) -> total = v
                    h[i].isNotBlank() -> flows[h[i]] = v
                }
            }
            if (flows.isNotEmpty()) days += EtfDay(date, flows, total ?: flows.values.sum())
        }
        return days.sortedByDescending { it.date }
    }

    private fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s, dateFmt) }.getOrNull()

    /** "1,234.5" → 1234.5 · "(12.3)" → -12.3 · "-" → 0 · "" → null */
    internal fun parseNumber(raw: String): Double? {
        val s = raw.replace(",", "").replace(" ", "").trim()
        if (s.isEmpty()) return null
        if (s == "-" || s == "–") return 0.0
        val neg = s.startsWith("(") && s.endsWith(")")
        val n = s.trim('(', ')').toDoubleOrNull() ?: return null
        return if (neg) -n else n
    }
}
