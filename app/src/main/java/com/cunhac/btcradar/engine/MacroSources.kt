package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
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
            val raw = (it.selectFirst("pubDate") ?: it.selectFirst("dc|date") ?: it.selectFirst("date"))?.text()?.trim() ?: return@mapNotNull null
            val t = parseDate(raw) ?: return@mapNotNull null
            RawNews(title, src, link, t, origin)
        }
    }

    private val ZONES = mapOf(" GMT" to " +0000", " UTC" to " +0000", " UT" to " +0000", " EST" to " -0500", " EDT" to " -0400",
        " CST" to " -0600", " CDT" to " -0500", " PST" to " -0800", " PDT" to " -0700", " Z" to " +0000")

    /** Datas de RSS: RFC 1123 (com fuso por nome ou número) ou ISO 8601. */
    fun parseDate(raw0: String): Long? {
        var raw = raw0.trim().replace(Regex("\\s+"), " ")
        runCatching { return ZonedDateTime.parse(raw, RFC).toInstant().toEpochMilli() }
        for ((k, v) in ZONES) if (raw.endsWith(k)) { raw = raw.removeSuffix(k) + v; break }
        runCatching { return ZonedDateTime.parse(raw, RFC).toInstant().toEpochMilli() }
        // Sem dia da semana: "26 Nov 2026 10:00:00 +0000"
        runCatching { return ZonedDateTime.parse(raw, DateTimeFormatter.ofPattern("d MMM yyyy HH:mm[:ss] Z", Locale.ENGLISH)).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(raw, DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm[:ss] Z", Locale.ENGLISH)).toInstant().toEpochMilli() }
        runCatching { return OffsetDateTime.parse(raw0.trim()).toInstant().toEpochMilli() }
        runCatching { return java.time.Instant.parse(raw0.trim()).toEpochMilli() }
        return null
    }

    /** Mais veículos e órgãos oficiais (RSS público). Nome exibido → endereço. */
    val FEEDS = listOf(
        "SEC" to "https://www.sec.gov/news/pressreleases.rss",
        "CFTC" to "https://www.cftc.gov/RSS/RSSGP/rssgp.xml",
        "ECB" to "https://www.ecb.europa.eu/rss/press.html",
        "CoinDesk" to "https://www.coindesk.com/arc/outboundfeeds/rss/",
        "Cointelegraph" to "https://cointelegraph.com/rss",
        "Decrypt" to "https://decrypt.co/feed",
        "The Block" to "https://www.theblock.co/rss.xml",
        "Bitcoin Magazine" to "https://bitcoinmagazine.com/.rss/full/",
        "CNBC" to "https://www.cnbc.com/id/100003114/device/rss/rss.html",
        "CNBC Economy" to "https://www.cnbc.com/id/20910258/device/rss/rss.html",
        "MarketWatch" to "https://feeds.content.dowjones.io/public/rss/mw_topstories",
        "WSJ Markets" to "https://feeds.content.dowjones.io/public/rss/RSSMarketsMain",
        "New York Times" to "https://rss.nytimes.com/services/xml/rss/nyt/Business.xml",
        "New York Times World" to "https://rss.nytimes.com/services/xml/rss/nyt/World.xml",
        "BBC Business" to "https://feeds.bbci.co.uk/news/business/rss.xml",
        "BBC World" to "https://feeds.bbci.co.uk/news/world/rss.xml",
        "Al Jazeera" to "https://www.aljazeera.com/xml/rss/all.xml",
        "Financial Times" to "https://www.ft.com/world?format=rss",
        "Bloomberg" to "https://feeds.bloomberg.com/markets/news.rss",
        "DW Business" to "https://rss.dw.com/rdf/rss-en-bus",
        "Investing.com" to "https://www.investing.com/rss/news_14.rss",
        "OilPrice" to "https://oilprice.com/rss/main",
    )

    // ------------------------------------------------------------------ mais mercados (Yahoo)
    /** key, rótulo, símbolo, grupo. */
    val EXTRA_MARKETS = listOf(
        listOf("RUT", "Russell 2000", "^RUT", "Bolsas"), listOf("FTSE", "FTSE 100 (Londres)", "^FTSE", "Bolsas"),
        listOf("DAX", "DAX (Alemanha)", "^GDAXI", "Bolsas"), listOf("N225", "Nikkei 225 (Japão)", "^N225", "Bolsas"),
        listOf("HSI", "Hang Seng (Hong Kong)", "^HSI", "Bolsas"), listOf("SSE", "Xangai", "000001.SS", "Bolsas"),
        listOf("BVSP", "Ibovespa", "^BVSP", "Bolsas"),
        listOf("EURUSD", "Euro / dólar", "EURUSD=X", "Moedas"), listOf("USDJPY", "Dólar / iene", "JPY=X", "Moedas"),
        listOf("USDCNY", "Dólar / yuan", "CNY=X", "Moedas"), listOf("USDBRL", "Dólar / real", "BRL=X", "Moedas"),
        listOf("SILVER", "Prata", "SI=F", "Commodities"), listOf("COPPER", "Cobre", "HG=F", "Commodities"),
        listOf("NATGAS", "Gás natural", "NG=F", "Commodities"),
        listOf("IRX", "Treasury 13 semanas", "^IRX", "Juros e crédito"), listOf("FVX", "Treasury 5 anos", "^FVX", "Juros e crédito"),
        listOf("TYX", "Treasury 30 anos", "^TYX", "Juros e crédito"), listOf("MOVE", "MOVE (volatilidade dos Treasuries)", "^MOVE", "Juros e crédito"),
        listOf("HYG", "HYG (crédito high yield)", "HYG", "Juros e crédito"),
        listOf("ETH", "Ethereum", "ETH-USD", "Cripto e ações ligadas"), listOf("IBIT", "IBIT (ETF de BTC da BlackRock)", "IBIT", "Cripto e ações ligadas"),
        listOf("MSTR", "Strategy (MSTR)", "MSTR", "Cripto e ações ligadas"), listOf("COIN", "Coinbase (COIN)", "COIN", "Cripto e ações ligadas"),
    )

    private val MONTH_CODES = "FGHJKMNQUVXZ"

    /** Contratos futuros de Fed funds (CBOT) dos próximos [n] meses: (AAAA-MM, símbolo). Taxa implícita = 100 − preço. */
    fun fedFuturesSymbols(nowMs: Long, n: Int = 8): List<Pair<String, String>> {
        val d = java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
        return (0 until n).map { i ->
            val m = d.plusMonths(i.toLong())
            "%04d-%02d".format(m.year, m.monthValue) to "ZQ${MONTH_CODES[m.monthValue - 1]}${"%02d".format(m.year % 100)}.CBT"
        }
    }

    // ------------------------------------------------------------------ Treasury: juros reais (TIPS)
    fun treasuryRealUrl(year: Int) = "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/$year/all" +
        "?type=daily_treasury_real_yield_curve&field_tdr_date_value=$year&page&_format=csv"

    /** Uma coluna da curva do Tesouro (ex.: "10 YR" da curva real), mais recente primeiro. */
    fun parseTreasuryColumn(csv: String, column: String): List<Pair<String, Double>> {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val head = lines[0].split(',').map { it.trim('"', ' ').uppercase() }
        val ci = head.indexOf(column.uppercase())
        if (ci < 0) error("coluna $column não encontrada")
        val fmt = DateTimeFormatter.ofPattern("MM/dd/yyyy")
        return lines.drop(1).mapNotNull { l ->
            val p = l.split(',')
            val d = runCatching { LocalDate.parse(p[0].trim('"'), fmt).toString() }.getOrNull() ?: return@mapNotNull null
            val v = p.getOrNull(ci)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            d to v
        }.distinctBy { it.first }.sortedByDescending { it.first }
    }

    // ------------------------------------------------------------------ NY Fed: SOFR e reverse repo
    const val SOFR_URL = "https://markets.newyorkfed.org/api/rates/secured/sofr/last/1.json"

    /** (data, taxa) de uma taxa de referência do NY Fed (SOFR, EFFR…). */
    fun parseRefRate(text: String): Pair<String, Double> {
        val r = Net.parse(text).jsonObject["refRates"]!!.jsonArray.first().jsonObject
        return r["effectiveDate"]!!.jsonPrimitive.content to ((r["percentRate"] as JsonPrimitive).doubleOrNull ?: error("sem taxa"))
    }

    fun rrpUrl(nowMs: Long): String {
        val from = java.time.Instant.ofEpochMilli(nowMs - 45 * 86_400_000L).atZone(ZoneOffset.UTC).toLocalDate()
        return "https://markets.newyorkfed.org/api/rp/reverserepo/propositions/search.json?startDate=$from"
    }

    /** Reverse repo overnight do Fed: (data, US$ milhões aceitos), mais recente primeiro. */
    fun parseRrp(text: String): List<Pair<String, Double>> {
        val ops = Net.parse(text).jsonObject["repo"]!!.jsonObject["operations"]!!.jsonArray.map { it.jsonObject }
        return ops.mapNotNull { o ->
            val d = o["operationDate"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val v = (o["totalAmtAccepted"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            d to v / 1e6
        }.groupBy { it.first }.map { (d, l) -> d to l.sumOf { it.second } }.sortedByDescending { it.first }
    }

    // ------------------------------------------------------------------ Fed: balanço (H.4.1)
    const val H41_URL = "https://www.federalreserve.gov/releases/h41/current/h41.htm"

    /** Balanço semanal do Fed (US$ milhões). */
    data class FedBalance(val week: String, val totalAssets: Double, val weekChange: Double, val yearChange: Double?,
                          val reserves: Double?, val reservesWeekChange: Double?)

    fun parseH41(html: String): FedBalance {
        val t = html.replace(Regex("&#xa0;|&nbsp;|&#160;"), " ").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
        fun n(x: String) = x.replace(",", "").toDouble()
        fun sgn(sig: String, x: String) = if (sig == "-") -n(x) else n(x)
        val ta = Regex("Total assets\\s*(?:\\(\\d+\\)\\s*)?([\\d,]+)\\s*([+-])\\s*([\\d,]+)\\s*([+-])\\s*([\\d,]+)").find(t) ?: error("total de ativos não encontrado")
        val rs = Regex("Reserve balances with Federal Reserve Banks\\s*([\\d,]+)\\s*([+-])\\s*([\\d,]+)").find(t)
        val wk = Regex("Week ended\\s+([A-Z][a-z]{2,8}\\.? \\d{1,2}, \\d{4})").find(t)?.groupValues?.get(1)
        val week = wk?.let { s -> runCatching { LocalDate.parse(s.replace(".", ""), DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)).toString() }.getOrNull() ?: s } ?: "—"
        return FedBalance(week, n(ta.groupValues[1]), sgn(ta.groupValues[2], ta.groupValues[3]), sgn(ta.groupValues[4], ta.groupValues[5]),
            rs?.let { n(it.groupValues[1]) }, rs?.let { sgn(it.groupValues[2], it.groupValues[3]) })
    }

    // ------------------------------------------------------------------ Kalshi: probabilidades da próxima decisão do Fed
    const val KALSHI_URL = "https://api.elections.kalshi.com/trade-api/v2/markets?series_ticker=KXFEDDECISION&status=open&limit=200"

    data class FedOdds(val event: String, val closeMs: Long, val outcomes: List<Pair<String, Double>>)

    /** Mercados abertos agrupados por reunião, da mais próxima para a mais distante. Probabilidade = último preço (0–1). */
    fun parseKalshi(text: String, nowMs: Long): List<FedOdds> {
        val ms = Net.parse(text).jsonObject["markets"]!!.jsonArray.map { it.jsonObject }
        return ms.mapNotNull { m ->
            val ev = m["event_ticker"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val close = runCatching { OffsetDateTime.parse(m["close_time"]!!.jsonPrimitive.content).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            val name = m["yes_sub_title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val p = (m["last_price_dollars"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: (m["last_price"] as? JsonPrimitive)?.doubleOrNull?.div(100) ?: return@mapNotNull null
            Triple(ev, close, name to p)
        }.filter { it.second > nowMs }.groupBy { it.first }
            .map { (ev, l) -> FedOdds(ev, l.first().second, l.map { it.third }.sortedByDescending { it.second }) }
            .sortedBy { it.closeMs }
    }

    // ------------------------------------------------------------------ cripto: sentimento, mercado, stablecoins, rede
    const val FNG_URL = "https://api.alternative.me/fng/?limit=31"
    data class FearGreed(val value: Int, val label: String, val dayMs: Long, val weekAgo: Int?, val monthAgo: Int?)

    fun parseFng(text: String): FearGreed {
        val d = Net.parse(text).jsonObject["data"]!!.jsonArray.map { it.jsonObject }
        fun v(i: Int) = d.getOrNull(i)?.get("value")?.jsonPrimitive?.content?.toIntOrNull()
        val first = d.first()
        return FearGreed(v(0) ?: error("sem valor"), first["value_classification"]?.jsonPrimitive?.content ?: "",
            (first["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) * 1000, v(7), v(30))
    }

    const val CG_GLOBAL_URL = "https://api.coingecko.com/api/v3/global"
    data class CryptoGlobal(val totalCapUsd: Double, val btcDominance: Double, val ethDominance: Double?, val cap24hPct: Double?, val volumeUsd: Double?, val updatedMs: Long)

    fun parseCgGlobal(text: String): CryptoGlobal {
        val d = Net.parse(text).jsonObject["data"]!!.jsonObject
        fun obj(k: String) = d[k]?.jsonObject
        return CryptoGlobal(obj("total_market_cap")?.get("usd")?.jsonPrimitive?.doubleOrNull ?: error("sem valor de mercado"),
            obj("market_cap_percentage")?.get("btc")?.jsonPrimitive?.doubleOrNull ?: error("sem dominância"),
            obj("market_cap_percentage")?.get("eth")?.jsonPrimitive?.doubleOrNull,
            d["market_cap_change_percentage_24h_usd"]?.jsonPrimitive?.doubleOrNull, obj("total_volume")?.get("usd")?.jsonPrimitive?.doubleOrNull,
            (d["updated_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0) * 1000)
    }

    const val STABLE_URL = "https://stablecoins.llama.fi/stablecoincharts/all"

    /** Total de stablecoins em dólar por dia (US$), mais recente primeiro (últimos 400 dias). */
    fun parseStablecoins(text: String): List<Pair<Long, Double>> = Net.parse(text).jsonArray.mapNotNull { e ->
        val o = e.jsonObject
        val t = o["date"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
        val v = o["totalCirculatingUSD"]?.jsonObject?.get("peggedUSD")?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        t * 1000 to v
    }.sortedByDescending { it.first }.take(400)

    const val HASHRATE_URL = "https://mempool.space/api/v1/mining/hashrate/1m"
    const val FEES_URL = "https://mempool.space/api/v1/fees/recommended"
    data class Network(val hashrateEh: Double, val hashrate30dChangePct: Double?, val difficulty: Double?)

    fun parseHashrate(text: String): Network {
        val o = Net.parse(text).jsonObject
        val hs = o["hashrates"]!!.jsonArray.map { it.jsonObject["avgHashrate"]!!.jsonPrimitive.double }
        val cur = (o["currentHashrate"] as? JsonPrimitive)?.doubleOrNull ?: hs.last()
        val first = hs.firstOrNull()
        return Network(cur / 1e18, if (first != null && first > 0) (cur - first) / first * 100 else null, (o["currentDifficulty"] as? JsonPrimitive)?.doubleOrNull)
    }

    /** Taxas recomendadas (sat/vB): rápida, 30 min, 1 h. */
    fun parseFees(text: String): Triple<Int, Int, Int> {
        val o = Net.parse(text).jsonObject
        fun i(k: String) = o[k]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: error("sem $k")
        return Triple(i("fastestFee"), i("halfHourFee"), i("hourFee"))
    }

    // ------------------------------------------------------------------ outros: BCE e dívida dos EUA
    const val ECB_URL = "https://data-api.ecb.europa.eu/service/data/FM/D.U2.EUR.4F.KR.DFR.LEV?lastNObservations=1&format=jsondata"

    /** Taxa de depósito do BCE: (data, %). */
    fun parseEcb(text: String): Pair<String, Double> {
        val root = Net.parse(text).jsonObject
        val series = root["dataSets"]!!.jsonArray.first().jsonObject["series"]!!.jsonObject.values.first().jsonObject
        val obs = series["observations"]!!.jsonObject
        val lastKey = obs.keys.maxByOrNull { it.toInt() } ?: error("sem observação")
        val v = obs[lastKey]!!.jsonArray.first().jsonPrimitive.double
        val dates = root["structure"]!!.jsonObject["dimensions"]!!.jsonObject["observation"]!!.jsonArray.first().jsonObject["values"]!!.jsonArray
        val d = dates.getOrNull(lastKey.toInt())?.jsonObject?.get("id")?.jsonPrimitive?.content ?: "—"
        return d to v
    }

    const val DEBT_URL = "https://api.fiscaldata.treasury.gov/services/api/fiscal_service/v2/accounting/od/debt_to_penny?sort=-record_date&page[size]=40"

    /** Dívida pública total dos EUA (US$), mais recente primeiro. */
    fun parseDebt(text: String): List<Pair<String, Double>> = Net.parse(text).jsonObject["data"]!!.jsonArray.mapNotNull { e ->
        val o = e.jsonObject
        val d = o["record_date"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val v = o["tot_pub_debt_out_amt"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
        d to v
    }.sortedByDescending { it.first }

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

/** Catálogo das fontes do Radar macro (aba Fontes e docs/MACRO.md). */
object MacroCatalog {
    data class Src(val name: String, val provides: String, val url: String, val official: Boolean)
    val ALL = listOf(
        Src("Federal Reserve Bank of New York", "Taxa efetiva do Fed (EFFR), intervalo-alvo, SOFR e reverse repo overnight", "https://markets.newyorkfed.org", true),
        Src("Federal Reserve", "Calendário do FOMC, balanço semanal (H.4.1), reservas bancárias e comunicados", "https://www.federalreserve.gov", true),
        Src("U.S. Treasury", "Curva de juros nominal e real (TIPS), saldo do TGA e dívida pública", "https://home.treasury.gov", true),
        Src("Treasury Fiscal Data", "Daily Treasury Statement (TGA) e Debt to the Penny", "https://fiscaldata.treasury.gov", true),
        Src("U.S. Bureau of Labor Statistics (BLS)", "CPI, núcleo do CPI, payroll, desemprego e salário por hora", "https://www.bls.gov", true),
        Src("U.S. Bureau of Economic Analysis (BEA) via DBnomics", "PCE e núcleo do PCE", "https://db.nomics.world/BEA", true),
        Src("Banco Central Europeu (BCE)", "Taxa de depósito e comunicados", "https://data.ecb.europa.eu", true),
        Src("SEC e CFTC", "Comunicados dos reguladores americanos", "https://www.sec.gov", true),
        Src("Yahoo Finance", "Bolsas (EUA, Europa, Ásia, Brasil), VIX, MOVE, DXY, moedas, commodities, Treasuries intradiários, futuros de Fed funds (CBOT), ETH, IBIT, MSTR, COIN, HYG", "https://finance.yahoo.com", false),
        Src("Kalshi", "Probabilidades negociadas da próxima decisão de juros do Fed", "https://kalshi.com", false),
        Src("Forex Factory (faireconomy.media)", "Calendário econômico semanal: horário, impacto, expectativa e anterior", "https://www.forexfactory.com/calendar", false),
        Src("Farside Investors", "Fluxo diário dos ETFs spot de bitcoin", "https://farside.co.uk", false),
        Src("CoinGecko", "Valor de mercado cripto, dominância do BTC e volume", "https://www.coingecko.com", false),
        Src("DefiLlama", "Estoque total de stablecoins", "https://defillama.com/stablecoins", false),
        Src("Alternative.me", "Índice Fear & Greed do mercado cripto", "https://alternative.me/crypto/fear-and-greed-index/", false),
        Src("mempool.space", "Hashrate, dificuldade e taxas da rede Bitcoin", "https://mempool.space", false),
        Src("Google News", "Manchetes por tema (conflitos, comércio, energia, Fed, fiscal, regulação cripto)", "https://news.google.com", false),
        Src("GDELT Project", "Monitor global de notícias", "https://www.gdeltproject.org", false),
        Src("Veículos (RSS)", MacroSources.FEEDS.joinToString(", ") { it.first }, "https://www.coindesk.com", false),
        Src("Google Tradutor", "Tradução automática de manchetes e textos sem tradução pronta", "https://translate.google.com", false),
    )
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
