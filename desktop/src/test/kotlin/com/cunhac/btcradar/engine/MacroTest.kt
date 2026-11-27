package com.cunhac.btcradar.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Radar macro: parsers com amostras reais (formato), regras, score, deduplicação, correlação e confluência. Sem rede. */
class MacroTest {
    private val now = System.currentTimeMillis()
    private val H = 3_600_000L
    private val D = 86_400_000L

    @Test fun parsesYahooChart() {
        val t0 = now / 1000 - 7200
        val json = """{"chart":{"result":[{"meta":{"regularMarketPrice":101.5,"previousClose":100.0,"regularMarketTime":${t0 + 7200}},
            "timestamp":[$t0,${t0 + 3600},${t0 + 7200}],"indicators":{"quote":[{"close":[100.0,null,101.5]}]}}],"error":null}}"""
        val s = MacroSources.parseYahoo("DXY", "Dólar", "", json, now)
        assertEquals(101.5, s.last!!, 1e-9)
        assertEquals(1.5, s.dayPct!!, 1e-9)
        assertEquals(2, s.closes.size) // barra sem fechamento (null) é ignorada, nunca vira zero
    }

    @Test fun parsesTreasuryCsvNewestFirst() {
        val csv = "Date,\"1 Mo\",\"2 Yr\",\"10 Yr\"\n11/25/2026,4.1,3.50,4.20\n11/26/2026,4.1,3.55,N/A\n"
        val rows = MacroSources.parseTreasury(csv)
        assertEquals("2026-11-26", rows[0].first)
        assertEquals(3.55, rows[0].second!!, 1e-9)
        assertNull(rows[0].third)
        val c = TreasuryCurve(rows, now)
        assertEquals(4.20, c.last10y!!, 1e-9) // primeiro valor disponível
    }

    @Test fun parsesBlsAndIgnoresAnnualAverage() {
        val json = """{"status":"REQUEST_SUCCEEDED","Results":{"series":[{"seriesID":"LNS14000000","data":[
            {"year":"2026","period":"M10","value":"4.3"},{"year":"2026","period":"M09","value":"4.2"},{"year":"2025","period":"M13","value":"4.0"}]}]}}"""
        val m = MacroSources.parseBls(json, now)
        val u = m["UNEMP"]!!
        assertEquals(listOf("2026-09", "2026-10"), u.points.map { it.first })
        assertEquals(0.1, u.diff(1)!!, 1e-9)
    }

    @Test fun parsesFedRateTgaCalendarFomcAndRss() {
        val fed = MacroSources.parseEffr("""{"refRates":[{"effectiveDate":"2026-11-25","type":"EFFR","percentRate":3.88,"targetRateFrom":3.75,"targetRateTo":4.00}]}""", now)
        assertEquals(3.88, fed.effr, 1e-9); assertEquals(4.0, fed.targetHigh, 1e-9)

        val tga = MacroSources.parseTga("""{"data":[
            {"record_date":"2026-11-25","account_type":"Treasury General Account (TGA) Closing Balance","open_today_bal":"null","close_today_bal":"850000"},
            {"record_date":"2026-11-24","account_type":"Treasury General Account (TGA) Opening Balance","open_today_bal":"800000","close_today_bal":"null"}]}""", now)
        assertEquals(850_000.0, tga.last!!, 1e-9); assertEquals(50_000.0, tga.change(1)!!, 1e-9)

        val cal = MacroSources.parseCalendar("""[{"title":"CPI m/m","country":"USD","date":"2026-12-10T08:30:00-05:00","impact":"High","forecast":"0.3%","previous":"0.2%"},
            {"title":"German CPI","country":"EUR","date":"2026-12-10T08:00:00+01:00","impact":"High","forecast":"","previous":""}]""")
        assertEquals(1, cal.size); assertTrue(cal[0].high)

        val html = """<h4>2026 FOMC Meetings</h4><div class="fomc-meeting__month"><strong>December</strong></div><div class="fomc-meeting__date">8-9*</div>"""
        val f = MacroSources.parseFomc(html)
        assertEquals(1, f.size)
        assertEquals(LocalDate.of(2026, 12, 9), java.time.Instant.ofEpochMilli(f[0]).atZone(ZoneOffset.UTC).toLocalDate())

        val rss = """<rss><channel><item><title>US slaps new tariffs on chips - Reuters</title><link>https://x/1</link>
            <pubDate>Thu, 26 Nov 2026 10:00:00 GMT</pubDate><source url="https://reuters.com">Reuters</source></item></channel></rss>"""
        val n = MacroSources.parseRss(rss, "Google News")
        assertEquals("US slaps new tariffs on chips", n[0].title); assertEquals("Reuters", n[0].source)
    }

    @Test fun classifiesOnlyHeadlinesWithEconomicLink() {
        assertEquals(NewsCategory.TRADE, MacroRules.classify("US announces new tariffs on Chinese goods")!!.category)
        assertEquals(MacroDir.FAVORABLE, MacroRules.classify("Fed signals rate cut in December")!!.dir)
        assertEquals(MacroDir.UNFAVORABLE, MacroRules.classify("Missile strike hits port near Strait of Hormuz")!!.dir)
        assertEquals(MacroDir.FAVORABLE, MacroRules.classify("Ceasefire agreed after weeks of fighting")!!.dir)
        assertNull(MacroRules.classify("Local team wins championship after overtime"))
        assertNull(MacroRules.classify("Celebrity wedding draws crowds"))
        assertEquals(Reliability.HIGH, MacroRules.reliability("Reuters"))
        assertEquals(Reliability.MEDIUM, MacroRules.reliability("random-blog.net"))
    }

    private fun news(title: String, src: String, ageMs: Long, dir: MacroDir = MacroDir.UNFAVORABLE, rel: Reliability = Reliability.HIGH) =
        MacroNews(title.hashCode().toString() + src, title, src, "", now - ageMs, NewsCategory.TRADE, dir, MacroIntensity.HIGH, MacroHorizon.SHORT, rel, 0.8, "", emptyList(), "t")

    @Test fun dedupesSameStoryAndDropsOldOrFutureNews() {
        val out = MacroRules.dedupe(listOf(
            news("US imposes new tariffs on Chinese electric vehicles", "Reuters", H),
            news("US imposes new tariffs on Chinese electric vehicles - CNBC", "CNBC", 2 * H, rel = Reliability.MEDIUM),
            news("Old story about tariffs", "Reuters", 3 * D),
            news("Future dated tariffs story", "Reuters", -2 * H),
        ), now)
        assertEquals(1, out.size)
        assertEquals("Reuters", out[0].source)
    }

    @Test fun surpriseFollowsIndicatorDirection() {
        val hot = MacroEvents.surprise(CalEvent("CPI m/m", "EUA", now - H, "High", "0.2%", "0.2%", actual = "0.4%"))!!
        assertEquals(MacroDir.UNFAVORABLE, hot.dir); assertEquals("Acima do esperado", hot.label)
        val cool = MacroEvents.surprise(CalEvent("CPI m/m", "EUA", now - H, "High", "0.3%", "0.2%", actual = "0.1%"))!!
        assertEquals(MacroDir.FAVORABLE, cool.dir)
        val inline = MacroEvents.surprise(CalEvent("CPI m/m", "EUA", now - H, "High", "0.3%", "0.2%", actual = "0.3%"))!!
        assertEquals(MacroDir.NEUTRAL, inline.dir)
        val claims = MacroEvents.surprise(CalEvent("Unemployment Claims", "EUA", now - H, "High", "220K", "215K", actual = "250K"))!!
        assertEquals(MacroDir.FAVORABLE, claims.dir)
        assertNull(MacroEvents.surprise(CalEvent("CPI m/m", "EUA", now - H, "High", "0.3%", "0.2%"))) // sem resultado: nada inventado
        assertEquals(245_000.0, MacroEvents.parseNum("245K")!!, 1e-9)
    }

    @Test fun actualOnlyFilledWhenOfficialReferenceMonthMatches() {
        val nov = java.time.ZonedDateTime.of(2026, 11, 13, 8, 30, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val cpi = EconSeries("CPI", "CPI", listOf("2026-09" to 100.0, "2026-10" to 100.3), "BLS", now)
        val e = CalEvent("CPI m/m", "EUA", nov, "High", "0.2%", "0.3%")
        val filled = MacroActuals.fill(listOf(e), mapOf("CPI" to cpi), nov + H)[0]
        assertEquals("0.3%", filled.actual)
        val stale = EconSeries("CPI", "CPI", listOf("2026-08" to 100.0, "2026-09" to 100.3), "BLS", now)
        assertNull(MacroActuals.fill(listOf(e), mapOf("CPI" to stale), nov + H)[0].actual)
        assertNull(MacroActuals.fill(listOf(e), mapOf("CPI" to cpi), nov - H)[0].actual) // ainda não saiu
    }

    private fun series(key: String, values: List<Double>, stepMs: Long = D): MarketSeries {
        val t = values.indices.map { now - (values.size - 1 - it) * stepMs }
        return MarketSeries(key, key, values.last(), values.getOrNull(values.size - 2), t.last(), t, values, now)
    }

    @Test fun scoreSkipsMissingFactorsAndStaysInRange() {
        val empty = MacroScoring.compute(MacroInputs(), MacroFactor.entries.associateWith { it.defaultWeight }, now)
        assertNull(empty.score); assertEquals(0, empty.confidence); assertEquals(10, empty.missing.size)

        // Dólar e Nasdaq a favor, VIX baixo: score positivo, confiança parcial.
        val inp = MacroInputs(dxy = series("DXY", listOf(100.0, 100.0, 99.5, 99.0, 98.8, 98.5)), ndx = series("NDX", listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0)),
            vix = series("VIX", listOf(13.0, 13.0)), fed = FedRate("2026-11-25", 3.88, 3.75, 4.0, now),
            curve = TreasuryCurve(listOf(Triple("2026-11-26", 3.40, 4.10)) + (1..6).map { Triple("2026-11-${26 - it}", 3.5, 4.2) }, now))
        val s = MacroScoring.compute(inp, MacroFactor.entries.associateWith { it.defaultWeight }, now)
        assertNotNull(s.score)
        assertTrue(s.score!! in 1..100)
        assertTrue(s.confidence in 1..99)
        assertTrue(s.missing.contains(MacroFactor.INFLATION.label))
        assertEquals(s.score!!.toDouble(), s.factors.filter { it.value != null }.sumOf { it.points }, 1.0)

        // Pesos zerados para o que está a favor: o resultado muda (configurável).
        val w = MacroFactor.entries.associateWith { if (it == MacroFactor.DOLLAR || it == MacroFactor.EQUITIES) 0 else it.defaultWeight }
        val s2 = MacroScoring.compute(inp, w, now)
        assertTrue((s2.score ?: 0) < s.score!!)
    }

    @Test fun staleEconDataIsNotUsed() {
        val old = EconSeries("CORE_CPI", "x", (0 until 15).map { String.format("2024-%02d", it % 12 + 1) to 100.0 + it }, "BLS", now)
        assertTrue(old.isStale(now))
        val s = MacroScoring.compute(MacroInputs(coreCpi = old), MacroFactor.entries.associateWith { it.defaultWeight }, now)
        assertNull(s.factors.first { it.factor == MacroFactor.INFLATION }.value)
        assertTrue(s.missing.any { it.contains("desatualizado") })
    }

    @Test fun correlationOfIdenticalSeriesIsOne() {
        val v = (0 until 40).map { 100.0 + it + (if (it % 3 == 0) 2.0 else 0.0) }
        val a = series("BTC", v, H); val b = series("NDX", v.map { it * 2 }, H)
        val c = MacroCorr.all(a, listOf(b), now)[0]
        assertEquals(1.0, c.r24h!!, 1e-9)
        assertNull(MacroCorr.pearson(listOf(1.0, 2.0), listOf(1.0, 2.0))) // poucas amostras: sem número
    }

    @Test fun confluenceScenarios() {
        val a = MacroConfluence.evaluate(MacroDir.UNFAVORABLE, FlowSnapshot(-300.0, 1000.0, 40.0, 2.0, 0, 0, -1.2))
        assertTrue(a.reading.startsWith("Cenário A")); assertEquals(Confluence.HIGH, a.confluence)
        val b = MacroConfluence.evaluate(MacroDir.UNFAVORABLE, FlowSnapshot(-20.0, 1000.0, 0.0, 0.0, 3, 0, 0.1))
        assertTrue(b.reading.startsWith("Cenário B"))
        val c = MacroConfluence.evaluate(MacroDir.FAVORABLE, FlowSnapshot(300.0, 1000.0, 1.0, 30.0, 0, 0, 1.0))
        assertTrue(c.reading.startsWith("Cenário C")); assertEquals(LiqState.SHORT, c.liq)
        val d = MacroConfluence.evaluate(MacroDir.FAVORABLE, FlowSnapshot(-300.0, 1000.0, 0.0, 0.0, 0, 0, -0.5))
        assertEquals(Confluence.LOW, d.confluence)
        assertTrue(d.reading.contains("não permitem identificar participantes individuais"))
        assertFalse(a.reading.contains("baleia"))
        val e = MacroConfluence.evaluate(MacroDir.UNFAVORABLE, FlowSnapshot(-300.0, 1000.0, 0.0, 0.0, 0, 0, -0.5))
        assertEquals(Confluence.HIGH, e.confluence)
        assertFalse(e.reading.startsWith("Sem confirmação"))
    }

    @Test fun parsesNewMacroSources() {
        val k = MacroSources.parseKalshi("""{"markets":[
            {"event_ticker":"KXFEDDECISION-26DEC","close_time":"2099-12-09T18:59:00Z","yes_sub_title":"Hike 25bps","last_price_dollars":"0.9300"},
            {"event_ticker":"KXFEDDECISION-26DEC","close_time":"2099-12-09T18:59:00Z","yes_sub_title":"Fed maintains rate","last_price_dollars":"0.0500"},
            {"event_ticker":"KXFEDDECISION-27JAN","close_time":"2100-01-27T18:59:00Z","yes_sub_title":"Cut 25bps","last_price_dollars":"0.2"}]}""", now)
        assertEquals(2, k.size); assertEquals("KXFEDDECISION-26DEC", k[0].event); assertEquals(0.93, k[0].outcomes[0].second, 1e-9)

        val h = MacroSources.parseH41("<p>Week ended Nov 18, 2026</p><td>Reserve balances with Federal Reserve Banks &#xa0; 2,979,656</td><td>+ 19,053</td><td>+ 91,013</td>" +
            "<td>Total assets (0)</td><td>6,741,452</td><td>- 17,415</td><td>+ 186,169</td>")
        assertEquals("2026-11-18", h.week); assertEquals(6_741_452.0, h.totalAssets, 1e-9); assertEquals(-17_415.0, h.weekChange, 1e-9)
        assertEquals(186_169.0, h.yearChange!!, 1e-9); assertEquals(2_979_656.0, h.reserves!!, 1e-9)

        val g = MacroSources.parseFng("""{"data":[{"value":"12","value_classification":"Extreme Fear","timestamp":"1795737600"},{"value":"11"},{"value":"10"},{"value":"9"},{"value":"8"},{"value":"7"},{"value":"6"},{"value":"30"}]}""")
        assertEquals(12, g.value); assertEquals(30, g.weekAgo); assertNull(g.monthAgo)

        val cg = MacroSources.parseCgGlobal("""{"data":{"total_market_cap":{"usd":2.1e12},"market_cap_percentage":{"btc":58.2,"eth":9.1},"market_cap_change_percentage_24h_usd":-1.5,"total_volume":{"usd":8e10},"updated_at":1795800000}}""")
        assertEquals(58.2, cg.btcDominance, 1e-9)

        val st = MacroSources.parseStablecoins("""[{"date":"1700000000","totalCirculatingUSD":{"peggedUSD":100.0}},{"date":"1700086400","totalCirculatingUSD":{"peggedUSD":110.0}}]""")
        assertEquals(110.0, st[0].second, 1e-9)
        val ms = com.cunhac.btcradar.desktop.MacroState(stablecoins = (0..40).map { (now - it * D) to 200.0 - it })
        assertEquals((200.0 - 170.0) / 170.0 * 100, ms.stablePct(30)!!, 1e-6)

        val real = MacroSources.parseTreasuryColumn("Date,\"5 YR\",\"10 YR\"\n11/25/2026,2.52,2.73\n11/24/2026,2.58,2.79\n", "10 YR")
        assertEquals("2026-11-25" to 2.73, real[0])

        val rrp = MacroSources.parseRrp("""{"repo":{"operations":[{"operationDate":"2026-11-25","totalAmtAccepted":300000000},{"operationDate":"2026-11-24","totalAmtAccepted":5000000000}]}}""")
        assertEquals(300.0, rrp[0].second, 1e-9)

        val ecb = MacroSources.parseEcb("""{"dataSets":[{"series":{"0:0:0:0:0:0:0":{"observations":{"0":[2.0]}}}}],"structure":{"dimensions":{"observation":[{"values":[{"id":"2026-11-26"}]}]}}}""")
        assertEquals("2026-11-26" to 2.0, ecb)

        val debt = MacroSources.parseDebt("""{"data":[{"record_date":"2026-11-24","tot_pub_debt_out_amt":"40793305704796.70"}]}""")
        assertEquals(4.07933057047967e13, debt[0].second, 1.0)

        val syms = MacroSources.fedFuturesSymbols(java.time.ZonedDateTime.of(2026, 11, 27, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), 3)
        assertEquals(listOf("2026-11" to "ZQX26.CBT", "2026-12" to "ZQZ26.CBT", "2027-01" to "ZQF27.CBT"), syms)

        assertNotNull(MacroSources.parseDate("Thu, 26 Nov 2026 10:00:00 GMT"))
        assertNotNull(MacroSources.parseDate("Thu, 26 Nov 2026 10:00:00 EST"))
        assertNotNull(MacroSources.parseDate("2026-11-26T10:00:00Z"))
        assertEquals(MacroSources.parseDate("Thu, 26 Nov 2026 10:00:00 +0000"), MacroSources.parseDate("2026-11-26T10:00:00Z"))
    }

    @Test fun newSubSignalsEnterTheScore() {
        val w = MacroFactor.entries.associateWith { it.defaultWeight }
        val fed = FedRate("2026-11-25", 3.88, 3.75, 4.0, now)
        val base = MacroScoring.compute(MacroInputs(fed = fed, futures3m = 3.88), w, now)
        val cuts = MacroScoring.compute(MacroInputs(fed = fed, futures3m = 3.38), w, now)
        val mb = base.factors.first { it.factor == MacroFactor.MONETARY }.value!!
        val mc = cuts.factors.first { it.factor == MacroFactor.MONETARY }.value!!
        assertTrue(mc > mb)
        val liq = MacroScoring.compute(MacroInputs(stable30dPct = 3.0, fedAssetsWeekChg = 25_000.0), w, now).factors.first { it.factor == MacroFactor.LIQUIDITY }
        assertEquals(1.0, liq.value!!, 1e-9)
        val vol = MacroScoring.compute(MacroInputs(move = 140.0), w, now).factors.first { it.factor == MacroFactor.VOLATILITY }
        assertTrue(vol.value!! < 0)
    }
}

