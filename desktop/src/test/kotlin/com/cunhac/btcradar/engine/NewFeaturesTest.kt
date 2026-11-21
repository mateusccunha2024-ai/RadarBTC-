package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.model.Candle
import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.data.net.o
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mensagens reais capturadas em 2026-11-21 nos canais públicos de cada corretora. */
class NewFeaturesTest {
    @Test fun gate_negativeSizeIsLongAndContractsConverted() {
        val j = Net.parse("""{"time":1795270699,"time_ms":1795270699455,"channel":"futures.public_liquidates","event":"update","result":[{"contract":"BTC_USDT","price":"60097.8","time":1795270698493,"size":-21}]}""").o
        val e = Parse.gateLiquidations(j, 0.0001).single()
        assertEquals(LiqSide.LONG, e.side)
        assertEquals(0.0021, e.qtyBtc, 1e-12)
        assertEquals(60097.8, e.price, 0.0)
        assertEquals(Venue.GATE, e.venue)
        val sub = Net.parse("""{"channel":"futures.public_liquidates","event":"subscribe","result":{"status":"success"}}""").o
        assertTrue(Parse.gateLiquidations(sub, 0.0001).isEmpty())
    }

    @Test fun bitget_amountIsUsdtAndSideIsPosition() {
        val j = Net.parse("""{"action":"update","arg":{"instType":"usdt-futures","topic":"liquidation"},"data":[{"symbol":"BTCUSDT","side":"sell","price":"60707.6334792744743358","amount":"65661.3763711832714416","ts":"1795270874059"},{"symbol":"ETHUSDT","side":"sell","price":"1701.02","amount":"7892.7328","ts":"1795270873760"}],"ts":1795270875167}""").o
        val e = Parse.bitgetLiquidations(j).single()
        assertEquals(LiqSide.SHORT, e.side)
        assertEquals(65661.376 / 60707.633, e.qtyBtc, 1e-4)
        assertEquals("USDT", e.origUnit)
    }

    @Test fun deribit_onlyFlaggedTradesBecomeLiquidations() {
        val j = Net.parse("""{"jsonrpc":"2.0","method":"subscription","params":{"channel":"trades.BTC-PERPETUAL.100ms","data":[
            {"trade_id":"1","price":60000.0,"amount":60000.0,"direction":"sell","timestamp":1795270000000,"liquidation":"T"},
            {"trade_id":"2","price":60010.0,"amount":30000.0,"direction":"buy","timestamp":1795270000001,"liquidation":"M"},
            {"trade_id":"3","price":60010.0,"amount":99999.0,"direction":"buy","timestamp":1795270000002}]}}""").o
        val l = Parse.deribitLiquidations(j)
        assertEquals(2, l.size)
        assertEquals(LiqSide.LONG, l[0].side); assertEquals(1.0, l[0].qtyBtc, 1e-9)   // agressor vendendo foi liquidado
        assertEquals(LiqSide.LONG, l[1].side)                                          // passivo vendedor de uma compra = long liquidado
        assertEquals(30000.0 / 60010.0, l[1].qtyBtc, 1e-9)
    }

    @Test fun volumeProfile_pocAndValueArea() {
        val p = buildProfile(listOf(ProfileRow(100.0, 0.5, 0.5), ProfileRow(105.0, 6.0, 4.0), ProfileRow(110.0, 1.0, 2.0), ProfileRow(115.0, 0.5, 0.5)))
        assertEquals(105.0, p.poc!!, 0.0)
        assertEquals(110.0, p.vah!!, 0.0)
        assertEquals(105.0, p.val_!!, 0.0)
        assertEquals(15.0, p.total, 1e-9)
    }

    @Test fun footprint_aggregatesBySideAndStep() {
        val fp = FootprintStore()
        val t = System.currentTimeMillis() / 60_000 * 60_000 + 1_000
        fp.add(TradeEvent(Venue.BINANCE, 60_003.0, 1.0, Aggressor.BUY, t, t))
        fp.add(TradeEvent(Venue.OKX, 60_008.0, 2.0, Aggressor.SELL, t, t + 1))
        fp.add(TradeEvent(Venue.GATE, 60_008.0, 9.0, Aggressor.SELL, t, t + 2)) // fora do footprint (sem fluxo)
        val c = fp.candles(60_000, 10.0, 1, CORE_VENUES.toSet(), t + 10).single()
        assertEquals(1, c.cells.size)
        assertEquals(1.0, c.buy, 1e-9); assertEquals(2.0, c.sell, 1e-9)
        assertEquals(60_003.0, c.open, 0.0); assertEquals(60_008.0, c.close, 0.0)
        val onlyBin = fp.candles(60_000, 10.0, 1, setOf(Venue.BINANCE), t + 10).single()
        assertEquals(0.0, onlyBin.sell, 1e-9)
    }

    private fun view(bids: List<Level>) = BookView(Venue.BINANCE, true, bids, listOf(Level(101.0, 0.2)), bids.size, 1, 0, 0, 0, 0, "")

    @Test fun walls_absorptionThenIceberg() {
        val flow = FlowEngine()
        val wt = WallTracker(WallThresholds())
        var signal: WallSignal? = null
        wt.onSignal = { signal = it.signal }
        val t = System.currentTimeMillis()
        wt.scan(listOf(view(listOf(Level(100.0, 5.0)))), 100.5, flow, t)
        flow.add(TradeEvent(Venue.BINANCE, 100.0, 3.0, Aggressor.SELL, t, t + 10))
        wt.scan(listOf(view(listOf(Level(100.0, 5.0)))), 100.5, flow, t + 20)
        assertEquals(WallSignal.ABSORPTION, wt.top(BookSide.BID, null).single().signal)
        flow.add(TradeEvent(Venue.BINANCE, 100.0, 3.0, Aggressor.SELL, t, t + 30))
        wt.scan(listOf(view(listOf(Level(100.0, 5.0)))), 100.5, flow, t + 40)
        val w = wt.top(BookSide.BID, null).single()
        assertEquals(WallSignal.ICEBERG, w.signal)
        assertEquals(6.0, w.executedBtc, 1e-9)
        assertEquals(6.0, w.replenishedBtc, 1e-9)
        assertEquals(WallSignal.ICEBERG, signal)
    }

    @Test fun walls_consumedLevelIsNotIceberg() {
        val flow = FlowEngine()
        val wt = WallTracker(WallThresholds())
        val t = System.currentTimeMillis()
        wt.scan(listOf(view(listOf(Level(100.0, 5.0)))), 100.5, flow, t)
        flow.add(TradeEvent(Venue.BINANCE, 100.0, 3.0, Aggressor.SELL, t, t + 10))
        wt.scan(listOf(view(listOf(Level(100.0, 2.0)))), 100.5, flow, t + 20)
        assertNull(wt.top(BookSide.BID, null).single().signal)
    }

    @Test fun divergence_bullishWhenPriceLowerLowAndCvdHigherLow() {
        val now = 10_000_000L
        val w = 600_000L
        val prices = (0 until 600).map { i -> val tt = now - w + i * 1000L; tt to if (i < 300) 100.0 + (i % 50) * 0.01 else 99.8 + (i % 50) * 0.01 }
        val cvd = (0 until 600).map { i -> val tt = now - w + i * 1000L; tt to if (i < 300) -10.0 + (i % 50) * 0.1 else -2.0 + (i % 50) * 0.1 }
        val d = Analytics.divergence(prices, cvd, w, now)
        assertNotNull(d); assertEquals(DivKind.BULLISH, d!!.kind)
        val flat = (0 until 600).map { i -> (now - w + i * 1000L) to 100.0 }
        assertNull(Analytics.divergence(flat, cvd, w, now))
    }

    @Test fun pearson_basic() {
        val x = (1..10).map { it.toDouble() as Double? }
        assertEquals(1.0, Analytics.pearson(x, x)!!, 1e-9)
        assertEquals(-1.0, Analytics.pearson(x, x.map { -it!! })!!, 1e-9)
        assertNull(Analytics.pearson(x.take(5), x.take(5)))
    }

    @Test fun heatmap_leverageFilterAndEntries() {
        val step = 3_600_000L
        val now = System.currentTimeMillis()
        val t0 = now - 30 * step
        val candles = (0 until 30).map { i -> Candle(t0 + i * step, 60_000.0, 60_100.0, 59_900.0, 60_000.0) }
        val oi = mapOf(Venue.OKX to (0 until 30).map { i -> OiBtc(t0 + i * step, if (i < 10) 1_000.0 else 1_100.0) })
        val d = HeatmapModel.build(HeatRange.D3, candles, oi, mapOf(Venue.OKX to "teste"), now)!!
        val only10 = HeatmapModel.summary(d, setOf(Venue.OKX), 60_000.0, d.pStep * 2, tiers = setOf(0))
        assertEquals(30.0, only10.longsBelowBtc, 1e-6)
        val (l, s) = HeatmapModel.combine(d, setOf(Venue.OKX), setOf(0))
        val lastCol = d.cols - 1
        assertEquals(30.0, l.sumOf { it[lastCol].toDouble() }, 1e-3)
        assertEquals(30.0, s.sumOf { it[lastCol].toDouble() }, 1e-3)
        val e = HeatmapModel.entries(d, setOf(Venue.OKX))
        assertEquals(200.0, e.sumOf { it[lastCol].toDouble() }, 1e-3) // 100 BTC longs + 100 shorts, todos com entrada perto de 60.000
        val delta = HeatmapModel.levelDelta(HeatmapModel.combine(d, setOf(Venue.OKX)))
        assertEquals(0.0, delta[lastCol].toDouble(), 1e-3)
    }
}
