package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Fontes públicas do Radar Macro (sem chave). Cada uma tem um "parse" puro (testado offline) e um "fetch".
 *   Mercado (intradiário):  Yahoo Finance chart API (não oficial) — DXY, S&P 500, Nasdaq 100, Dow, VIX, ^TNX, ouro, WTI, Brent, BTC.
 *   Juros oficiais:          U.S. Treasury — Daily Treasury Par Yield Curve (2 e 10 anos).
 *   Fed funds:               Federal Reserve Bank of New York — EFFR e intervalo-alvo.
 *   Inflação/emprego:        BLS Public API v1 (CPI, núcleo, payroll, desemprego, salário/hora; 25 consultas/dia por IP).
 *   PCE:                     BEA via DBnomics (espelho atualizado das tabelas NIPA).
 *   Liquidez:                U.S. Treasury Fiscal Data — Daily Treasury Statement (saldo do TGA).
 *   Calendário:              faireconomy.media (calendário semanal do Forex Factory: horário, impacto, expectativa e anterior).
 *   FOMC:                    federalreserve.gov — calendário de reuniões.
 *   Notícias:                Google News RSS (por tema), GDELT DOC 2.0 e press releases do Federal Reserve (RSS).
 * Opcional: BLS_API_KEY (variável de ambiente ou campo no app) usa a API v2 do BLS (500 consultas/dia).
 */
object MacroSources {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "*/*").build()
        Net.client.newCall(req).execute().use { r ->
            if (r.code == 429) throw IOException("limite de consultas da fonte (HTTP 429)")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("resposta vazia")
        }
    }

    suspend fun postJson(url: String, body: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).post(body.toRequestBody("application/json".toMediaType())).build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("resposta vazia")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ Yahoo
    /** key, rótulo, símbolo no Yahoo, unidade. */
    val MARKETS = listOf(
        listOf("DXY", "Dólar (DXY)", "DX-Y.NYB", ""), listOf("SPX", "S&P 500", "^GSPC", ""), listOf("NDX", "Nasdaq 100", "^NDX", ""),
        listOf("DJI", "Dow Jones", "^DJI", ""), listOf("VIX", "VIX", "^VIX", ""), listOf("TNX", "Treasury 10 anos (intradiário)", "^TNX", "%"),
        listOf("GOLD", "Ouro", "GC=F", ""), listOf("WTI", "Petróleo WTI", "CL=F", ""), listOf("BRENT", "Petróleo Brent", "BZ=F", ""),
        listOf("BTC", "Bitcoin", "BTC-USD", ""),
    )

    fun yahooUrl(sym: String) = "https://query1.finance.yahoo.com/v8/finance/chart/${enc(sym)}?range=1mo&interval=1h"

    fun parseYahoo(key: String, label: String, unit: String, text: String, nowMs: Long): MarketSeries {
        val root = Net.parse(text).jsonObject["chart"]!!.jsonObject
        val res = (root["result"] as? JsonArray)?.firstOrNull()?.jsonObject ?: error("sem resultado (${root["error"]})")
        val meta = res["meta"]!!.jsonObject
        val ts = (res["timestamp"] as? JsonArray)?.map { it.jsonPrimitive.content.toLong() * 1000 } ?: emptyList()
        val closesRaw = res["indicators"]?.jsonObject?.get("quote")?.jsonArray?.firstOrNull()?.jsonObject?.get("close") as? JsonArray ?: JsonArray(emptyList())
        val t = ArrayList<Long>(); val c = ArrayList<Double>()
        for (i in ts.indices) {
            val v = (closesRaw.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: continue
            t += ts[i]; c += v
        }
        fun m(k: String) = (meta[k] as? JsonPrimitive)?.doubleOrNull
        val lastT = (meta["regularMarketTime"] as? JsonPrimitive)?.content?.toLongOrNull()?.times(1000) ?: t.lastOrNull() ?: 0L
        return MarketSeries(key, label, m("regularMarketPrice") ?: c.lastOrNull(), m("previousClose"), lastT, t, c, nowMs, unit)
    }

    // ------------------------------------------------------------------ Treasury (juros oficiais)
    fun treasuryUrl(year: Int) = "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/$year/all" +
        "?type=daily_treasury_yield_curve&field_tdr_date_value=$year&page&_format=csv"

    fun parseTreasury(csv: String): List<Triple<String, Double?, Double?>> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val head = lines[0].split(',').map { it.trim('"', ' ') }
        val i2 = head.indexOf("2 Yr"); val i10 = head.indexOf("10 Yr")
        if (i2 < 0 || i10 < 0) error("colunas 2 Yr/10 Yr não encontradas")
        val fmt = DateTimeFormatter.ofPattern("MM/dd/yyyy")
        return lines.drop(1).mapNotNull { l ->
            val p = l.split(',')
            val d = runCatching { LocalDate.parse(p[0].trim('"'), fmt).toString() }.getOrNull() ?: return@mapNotNull null
            Triple(d, p.getOrNull(i2)?.trim()?.toDoubleOrNull(), p.getOrNull(i10)?.trim()?.toDoubleOrNull())
        }.sortedByDescending { it.first }
    }

    // ------------------------------------------------------------------ NY Fed (EFFR)
    const val EFFR_URL = "https://markets.newyorkfed.org/api/rates/unsecured/effr/last/1.json"

    fun parseEffr(text: String, nowMs: Long): FedRate {
        val r = Net.parse(text).jsonObject["refRates"]!!.jsonArray.first().jsonObject
        fun d(k: String) = (r[k] as? JsonPrimitive)?.doubleOrNull ?: error("campo $k ausente")
        return FedRate(r["effectiveDate"]!!.jsonPrimitive.content, d("percentRate"), d("targetRateFrom"), d("targetRateTo"), nowMs)
    }

    // ------------------------------------------------------------------ BLS
    /** id interno → (série BLS, rótulo). */
    val BLS = linkedMapOf(
        "CPI" to ("CUSR0000SA0" to "CPI (índice, dessaz.)"), "CORE_CPI" to ("CUSR0000SA0L1E" to "Núcleo do CPI (índice, dessaz.)"),
        "PAYROLLS" to ("CES0000000001" to "Nonfarm payrolls (mil)"), "UNEMP" to ("LNS14000000" to "Taxa de desemprego (%)"),
        "AHE" to ("CES0500000003" to "Salário médio por hora (US$)"),
    )

    fun blsUrl(key: String?) = if (key.isNullOrBlank()) "https://api.bls.gov/publicAPI/v1/timeseries/data/" else "https://api.bls.gov/publicAPI/v2/timeseries/data/"

    fun blsBody(key: String?, nowYear: Int): String {
        val ids = BLS.values.joinToString(",") { "\"${it.first}\"" }
        val k = if (key.isNullOrBlank()) "" else ",\"registrationkey\":\"$key\""
        return "{\"seriesid\":[$ids],\"startyear\":\"${nowYear - 2}\",\"endyear\":\"$nowYear\"$k}"
    }

    fun parseBls(text: String, nowMs: Long): Map<String, EconSeries> {
        val root = Net.parse(text).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && status != "REQUEST_SUCCEEDED") error("BLS: $status ${root["message"]}")
        val out = LinkedHashMap<String, EconSeries>()
        val series = root["Results"]?.jsonObject?.get("series")?.jsonArray ?: return out
        for (s in series) {
            val o = s.jsonObject
            val sid = o["seriesID"]?.jsonPrimitive?.content ?: continue
            val (id, label) = BLS.entries.firstOrNull { it.value.first == sid }?.let { it.key to it.value.second } ?: continue
            val pts = o["data"]!!.jsonArray.mapNotNull { e ->
                val d = e.jsonObject
                val per = d["period"]!!.jsonPrimitive.content
                if (!per.startsWith("M") || per == "M13") return@mapNotNull null
                val v = d["value"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                "${d["year"]!!.jsonPrimitive.content}-${per.drop(1)}" to v
            }.sortedBy { it.first }
            out[id] = EconSeries(id, label, pts, "BLS", nowMs)
        }
        return out
    }

    // ------------------------------------------------------------------ BEA via DBnomics (PCE)
    val PCE = linkedMapOf("PCE" to ("BEA/NIPA-T20804/DPCERG-M" to "PCE (índice de preços)"), "CORE_PCE" to ("BEA/NIPA-T20804/DPCCRG-M" to "Núcleo do PCE (índice)"))

    fun dbnomicsUrl(code: String) = "https://api.db.nomics.world/v22/series/$code?observations=1&format=json"

    fun parseDbnomics(id: String, label: String, text: String, nowMs: Long): EconSeries {
        val d = Net.parse(text).jsonObject["series"]!!.jsonObject["docs"]!!.jsonArray.first().jsonObject
        val per = d["period"]!!.jsonArray.map { it.jsonPrimitive.content }
        val vals = d["value"]!!.jsonArray.map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.doubleOrNull }
        val pts = per.indices.mapNotNull { i -> vals.getOrNull(i)?.let { per[i].take(7) to it } }.takeLast(40)
        return EconSeries(id, label, pts, "BEA (via DBnomics)", nowMs)
    }

    // ------------------------------------------------------------------ TGA
    const val TGA_URL = "https://api.fiscaldata.treasury.gov/services/api/fiscal_service/v1/accounting/dts/operating_cash_balance" +
        "?sort=-record_date&page[size]=400&fields=record_date,account_type,open_today_bal,close_today_bal"

    fun parseTga(text: String, nowMs: Long): TgaSeries {
        val data = Net.parse(text).jsonObject["data"]!!.jsonArray.map { it.jsonObject }
        fun num(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val byDate = data.filter { val a = it["account_type"]?.jsonPrimitive?.content ?: ""; a.contains("TGA") || a.contains("Treasury General Account") }
            .groupBy { it["record_date"]!!.jsonPrimitive.content }
        val rows = byDate.mapNotNull { (d, list) ->
            val closing = list.firstOrNull { it["account_type"]!!.jsonPrimitive.content.contains("Closing") }
            val v = closing?.let { num(it, "close_today_bal") ?: num(it, "open_today_bal") }
                ?: list.firstOrNull { it["account_type"]!!.jsonPrimitive.content.contains("Opening") }?.let { num(it, "open_today_bal") }
            v?.let { d to it }
        }.sortedByDescending { it.first }
        return TgaSeries(rows, nowMs)
    }

    // ------------------------------------------------------------------ Calendário
    const val CAL_URL = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"

    fun parseCalendar(text: String): List<CalEvent> = Net.parse(text).jsonArray.mapNotNull { e ->
        val o = e.jsonObject
        val country = o["country"]?.jsonPrimitive?.content ?: return@mapNotNull null
        if (country != "USD") return@mapNotNull null
        val t = runCatching { OffsetDateTime.parse(o["date"]!!.jsonPrimitive.content).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
        CalEvent(o["title"]?.jsonPrimitive?.content ?: "", "EUA", t, o["impact"]?.jsonPrimitive?.content ?: "",
            o["forecast"]?.jsonPrimitive?.contentOrNull ?: "", o["previous"]?.jsonPrimitive?.contentOrNull ?: "")
    }.sortedBy { it.timeMs }

    // ------------------------------------------------------------------ FOMC
    const val FOMC_URL = "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm"
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    /** Horário da decisão de cada reunião (último dia, 14h de Nova York). */
    fun parseFomc(html: String): List<Long> {
        val out = ArrayList<Long>()
        val ny = ZoneId.of("America/New_York")
        val years = Regex("(\\d{4}) FOMC Meetings").findAll(html).toList()
        for ((i, y) in years.withIndex()) {
            val year = y.groupValues[1].toInt()
            val end = if (i + 1 < years.size) years[i + 1].range.first else html.length
            val block = html.substring(y.range.last, end)
            val months = Regex("fomc-meeting__month[^>]*>\\s*<strong>([^<]+)</strong>").findAll(block).map { it.groupValues[1] }.toList()
            val dates = Regex("fomc-meeting__date[^>]*>([^<]+)<").findAll(block).map { it.groupValues[1].trim() }.toList()
            for (k in 0 until minOf(months.size, dates.size)) {
                val mParts = months[k].lowercase().split('/')
                val dParts = dates[k].replace("*", "").replace(Regex("\\(.*\\)"), "").trim().split('-')
                val lastDay = dParts.last().trim().toIntOrNull() ?: continue
                val mName = mParts.last().trim()
                val month = MONTHS.indexOfFirst { it.startsWith(mName.take(3)) } + 1
                if (month <= 0) continue
                runCatching { out += ZonedDateTime.of(year, month, lastDay, 14, 0, 0, 0, ny).toInstant().toEpochMilli() }
            }
        }
        return out.sorted()
    }

    // ------------------------------------------------------------------ Notícias
    data class RawNews(val title: String, val source: String, val url: String, val timeMs: Long, val origin: String)

    /** Temas buscados no Google News (últimas 24 h). */
    val NEWS_QUERIES = listOf(
        "(war OR missile OR airstrike OR invasion OR ceasefire OR \"military escalation\" OR \"Strait of Hormuz\") (US OR China OR Russia OR Iran OR Israel OR Taiwan OR Ukraine)",
        "(tariffs OR sanctions OR \"export controls\" OR \"trade deal\" OR \"trade war\")",
        "(\"oil prices\" OR OPEC OR \"natural gas prices\" OR \"energy supply\")",
        "(\"Federal Reserve\" OR Powell OR FOMC OR \"rate cut\" OR \"rate hike\" OR \"inflation report\" OR \"jobs report\")",
        "(\"debt ceiling\" OR \"government shutdown\" OR \"Treasury yields\" OR \"credit rating\" OR \"fiscal deficit\")",
        "(bitcoin OR crypto OR stablecoin) (SEC OR regulation OR bill OR ban OR \"strategic reserve\" OR ETF)",
    )

    fun gnewsUrl(q: String) = "https://news.google.com/rss/search?q=${enc("$q when:1d")}&hl=en-US&gl=US&ceid=US:en"

    private val RFC = DateTimeFormatter.RFC_1123_DATE_TIME

    fun parseRss(xml: String, origin: String, defaultSource: String = ""): List<RawNews> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("item").mapNotNull { it ->
            var title = it.selectFirst("title")?.text()?.trim() ?: return@mapNotNull null
            val link = it.selectFirst("link")?.text()?.trim() ?: ""
            val src = it.selectFirst("source")?.text()?.trim()?.ifBlank { null } ?: defaultSource
            if (src.isNotBlank() && title.endsWith(" - $src")) title = title.removeSuffix(" - $src")
            val t = runCatching { ZonedDateTime.parse(it.selectFirst("pubDate")!!.text().trim(), RFC).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            RawNews(title, src, link, t, origin)
        }
    }

    const val FED_RSS = "https://www.federalreserve.gov/feeds/press_all.xml"

    fun gdeltUrl() = "https://api.gdeltproject.org/api/v2/doc/doc?query=" +
        enc("(tariffs OR sanctions OR ceasefire OR airstrike OR \"federal reserve\" OR \"oil prices\" OR \"debt ceiling\") sourcelang:english") +
        "&mode=artlist&maxrecords=60&format=json&timespan=24h&sort=datedesc"

    fun parseGdelt(text: String): List<RawNews> {
        if (!text.trimStart().startsWith("{")) error(text.take(80))
        val arts = Net.parse(text).jsonObject["articles"] as? JsonArray ?: return emptyList()
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
        return arts.mapNotNull { a ->
            val o = a.jsonObject
            val t = runCatching { java.time.LocalDateTime.parse(o["seendate"]!!.jsonPrimitive.content, fmt).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                ?: return@mapNotNull null
            RawNews(o["title"]?.jsonPrimitive?.content ?: return@mapNotNull null, o["domain"]?.jsonPrimitive?.content ?: "", o["url"]?.jsonPrimitive?.content ?: "", t, "GDELT")
        }
    }

    /** Classifica e transforma em notícia do radar; sem regra que case = fora (sem ligação econômica clara). */
    fun classify(raw: List<RawNews>): List<MacroNews> = raw.mapNotNull { r ->
        val rule = MacroRules.classify(r.title) ?: return@mapNotNull null
        val rel = if (r.origin == "Federal Reserve") Reliability.OFFICIAL else MacroRules.reliability(r.source.ifBlank { r.url })
        MacroNews(Integer.toHexString((r.url.ifBlank { r.title }).hashCode()), r.title, r.source.ifBlank { r.origin }, r.url, r.timeMs,
            rule.category, rule.dir, rule.intensity, rule.horizon, rel, rule.intensity.weight * rel.weight, rule.rationale, rule.chain, r.origin)
    }
}

/** Liga um evento do calendário à série oficial que traz o resultado (para comparar com a expectativa). */
object MacroActuals {
    /** título no calendário → (série, como calcular, unidade). */
    private val MAP: List<Triple<String, String, (EconSeries) -> Double?>> = listOf(
        Triple("core cpi m/m", "CORE_CPI") { s: EconSeries -> s.pctChange(1) },
        Triple("cpi m/m", "CPI") { s: EconSeries -> s.pctChange(1) },
        Triple("cpi y/y", "CPI") { s: EconSeries -> s.pctChange(12) },
        Triple("core pce price index m/m", "CORE_PCE") { s: EconSeries -> s.pctChange(1) },
        Triple("pce price index m/m", "PCE") { s: EconSeries -> s.pctChange(1) },
        Triple("non-farm employment change", "PAYROLLS") { s: EconSeries -> s.diff(1) },
        Triple("unemployment rate", "UNEMP") { s: EconSeries -> s.v(0) },
        Triple("average hourly earnings m/m", "AHE") { s: EconSeries -> s.pctChange(1) },
    )

    /**
     * Preenche o resultado só quando: o evento já passou e a série oficial já tem o mês de referência
     * (mês anterior ao da divulgação). Assim um número antigo nunca aparece como resultado novo.
     */
    fun fill(events: List<CalEvent>, econ: Map<String, EconSeries>, nowMs: Long): List<CalEvent> = events.map { e ->
        if (e.timeMs > nowMs || e.actual != null) return@map e
        val t = e.title.lowercase()
        val m = MAP.firstOrNull { t == it.first || t.startsWith(it.first) } ?: return@map e
        val s = econ[m.second] ?: return@map e
        val rel = java.time.Instant.ofEpochMilli(e.timeMs).atZone(ZoneId.of("America/New_York")).toLocalDate().minusMonths(1)
        val refPeriod = "%04d-%02d".format(rel.year, rel.monthValue)
        if (s.lastPeriod != refPeriod) return@map e
        val v = m.third(s) ?: return@map e
        val txt = when (m.second) {
            "PAYROLLS" -> String.format(Locale.US, "%.0fK", v)
            else -> String.format(Locale.US, "%.1f%%", v)
        }
        e.copy(actual = txt, actualSource = "${s.source} ($refPeriod, calculado do índice oficial)")
    }
}
