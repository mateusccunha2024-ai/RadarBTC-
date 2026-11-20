package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.data.net.o
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures = mensagens REAIS capturadas em 2026-11-20 (não são dados de produção). */
class ParseTest {
    @Test fun binanceAggTrade_mTrueIsAggressiveSell() {
        val (s, d) = Parse.binanceCombined("""{"stream":"btcusdt@aggTrade","data":{"e":"aggTrade","E":1795210080897,"a":3537104612,"s":"BTCUSDT","p":"58673.40","q":"0.058","nq":"0.058","f":8308392352,"l":8308392352,"T":1795210080747,"m":true,"st":1}}""")!!
        assertEquals("btcusdt@aggTrade", s)
        val t = Parse.binanceAggTrade(d)
        assertEquals(Aggressor.SELL, t.aggressor); assertEquals(0.058, t.qtyBtc, 1e-12); assertEquals(58673.4, t.price, 1e-9)
    }

    @Test fun binanceMark() {
        val (_, d) = Parse.binanceCombined("""{"stream":"btcusdt@markPrice@1s","data":{"e":"markPriceUpdate","E":1795210081001,"s":"BTCUSDT","p":"58675.13811844","P":"58435.34424553","i":"58700.29956522","r":"-0.00004394","T":1795219200000}}""")!!
        val m = Parse.binanceMark(d)
        assertEquals(58675.13811844, m.markPrice!!, 1e-6); assertEquals(-0.00004394, m.fundingRate!!, 1e-12)
    }

    @Test fun binanceForceOrder_sellIsLongLiquidated() {
        val d = Net.parse("""{"e":"forceOrder","E":1,"o":{"s":"BTCUSDT","S":"SELL","o":"LIMIT","q":"0.014","p":"9910","ap":"9910","X":"FILLED","z":"0.014","T":2}}""").o
        val l = Parse.binanceForceOrder(d)!!
        assertEquals(LiqSide.LONG, l.side); assertEquals(0.014, l.qtyBtc, 1e-12)
    }

    @Test fun bybitTrade_takerSide() {
        val j = Net.parse("""{"topic":"publicTrade.BTCUSDT","type":"snapshot","ts":1795210108305,"data":[{"T":1795210108303,"s":"BTCUSDT","S":"Buy","v":"0.001","p":"58680.80","L":"PlusTick","i":"x","BT":false,"RPI":false,"seq":851321900341}]}""").o
        val t = Parse.bybitTrades(j).single()
        assertEquals(Aggressor.BUY, t.aggressor); assertEquals(0.001, t.qtyBtc, 1e-12)
    }

    @Test fun bybitBook_snapshotAndDelta() {
        val s = Parse.bybitBook(Net.parse("""{"topic":"orderbook.1000.BTCUSDT","type":"snapshot","ts":1,"data":{"s":"BTCUSDT","b":[["58681.30","3.084"]],"a":[["58681.40","1.839"]],"u":683556,"seq":851321875010}}""").o)
        assertTrue(s.snapshot); assertEquals(683556, s.u)
        val d = Parse.bybitBook(Net.parse("""{"topic":"orderbook.1000.BTCUSDT","type":"delta","ts":2,"data":{"s":"BTCUSDT","b":[["58681.30","0"]],"a":[],"u":683557,"seq":851321875339}}""").o)
        assertFalse(d.snapshot); assertEquals(0.0, d.bids.single().qtyBtc, 0.0)
    }

    @Test fun bybitRpi_takesOnlyRpiPart() {
        val j = Net.parse("""{"a":[["58678.60","0.878","0.250"],["58691.30","0","0"]]}""").o
        val lv = Parse.bybitRpiLevels(j["a"]!!.let { it as kotlinx.serialization.json.JsonArray })
        assertEquals(0.25, lv[0].qtyBtc, 1e-12); assertEquals(0.0, lv[1].qtyBtc, 0.0)
    }

    @Test fun bybitLiquidation_buyIsLong() {
        val j = Net.parse("""{"topic":"allLiquidation.BTCUSDT","data":[{"T":1,"s":"BTCUSDT","S":"Buy","v":"0.5","p":"58000"}]}""").o
        assertEquals(LiqSide.LONG, Parse.bybitLiquidations(j).single().side)
    }

    @Test fun okx_contractsConvertedWithCtVal() {
        val tr = Parse.okxTrades(Net.parse("""{"arg":{"channel":"trades","instId":"BTC-USDT-SWAP"},"data":[{"instId":"BTC-USDT-SWAP","tradeId":"3107560693","px":"58667.1","sz":"10","side":"buy","ts":"1795210138295","count":"1","source":"0","seqId":355931627500}]}""").o, 0.01).single()
        assertEquals(0.1, tr.qtyBtc, 1e-12); assertEquals(Aggressor.BUY, tr.aggressor)
        val b = Parse.okxBook(Net.parse("""{"arg":{"channel":"books","instId":"BTC-USDT-SWAP"},"action":"snapshot","data":[{"asks":[["58667.7","43.17","0","8"]],"bids":[["58667.6","919.88","0","57"]],"ts":"1795210138501","checksum":0,"seqId":355931629776,"prevSeqId":-1}]}""").o, 0.01)
        assertTrue(b.snapshot); assertEquals(-1, b.prevSeqId); assertEquals(9.1988, b.bids.single().qtyBtc, 1e-9)
    }

    @Test fun okxLiquidation_filtersOtherInstruments() {
        val j = Net.parse("""{"arg":{"channel":"liquidation-orders","instType":"SWAP"},"data":[{"details":[{"bkLoss":"0","bkPx":"31.49","posSide":"long","side":"sell","sz":"655.04","ts":"1"}],"instId":"SOXL-USDT-SWAP"},{"details":[{"bkPx":"58000","posSide":"short","side":"buy","sz":"25","ts":"2"}],"instId":"BTC-USDT-SWAP"}]}""").o
        val l = Parse.okxLiquidations(j, 0.01).single()
        assertEquals(LiqSide.SHORT, l.side); assertEquals(0.25, l.qtyBtc, 1e-12); assertEquals(25.0, l.origQty, 0.0); assertEquals("contratos", l.origUnit)
    }
}

class SequenceTest {
    private fun ev(U: Long, u: Long, pu: Long) = Parse.BinanceDepth(U, u, pu, emptyList(), emptyList(), 0)

    @Test fun binanceSyncRules() {
        val s = BinanceSync()
        assertEquals(BinanceSync.Action.BUFFER, s.check(ev(1, 5, 0)))
        s.onSnapshot(100)
        assertEquals(BinanceSync.Action.DROP, s.check(ev(80, 99, 79)))
        assertEquals(BinanceSync.Action.APPLY, s.check(ev(95, 110, 94)))
        assertEquals(BinanceSync.Action.APPLY, s.check(ev(111, 120, 110)))
        assertEquals(BinanceSync.Action.GAP, s.check(ev(125, 130, 124)))
    }

    @Test fun binanceFirstEventMustCoverSnapshot() {
        val s = BinanceSync(); s.onSnapshot(100)
        assertEquals(BinanceSync.Action.GAP, s.check(ev(105, 110, 104)))
    }

    @Test fun localBook_deleteAndCrossedInvalidates() {
        val b = LocalBook(Venue.BYBIT)
        b.reset(listOf(Level(100.0, 1.0), Level(99.0, 2.0)), listOf(Level(101.0, 1.0)), 1, 0)
        b.apply(listOf(Level(100.0, 0.0)), emptyList(), 2, 0)
        assertEquals(1, b.view().bidLevels); assertTrue(b.valid)
        b.apply(listOf(Level(102.0, 1.0)), emptyList(), 3, 0)
        assertFalse(b.valid); assertEquals(1, b.resyncs)
    }
}

class IntelligenceTest {
    private fun view(v: Venue, bids: List<Level>, asks: List<Level>, valid: Boolean = true) =
        BookView(v, valid, bids, asks, bids.size, asks.size, 0, 0, 0, 0, "")

    @Test fun wallLifecycle_newThenCorroboratedOrDisappeared() {
        val flow = FlowEngine()
        val wt = WallTracker(WallThresholds())
        wt.scan(listOf(view(Venue.BINANCE, listOf(Level(100.0, 6.0), Level(99.0, 2.0)), listOf(Level(101.0, 0.5)))), 100.5, flow, 1_000)
        val top = wt.top(BookSide.BID, null)
        assertEquals(2, top.size); assertEquals(WallStatus.NEW, top[0].status); assertEquals(WallClass.LARGE, top[0].cls)
        flow.add(TradeEvent(Venue.BINANCE, 100.0, 4.0, Aggressor.SELL, 0, recvMs = System.currentTimeMillis()))
        wt.scan(listOf(view(Venue.BINANCE, listOf(Level(99.0, 2.5)), listOf(Level(101.0, 0.5)))), 100.5, flow, System.currentTimeMillis())
        val h = wt.history()
        assertEquals(WallStatus.EXECUTION_CORROBORATED, h.single().status)
        assertEquals(WallStatus.INCREASED, wt.top(BookSide.BID, null).single().status)
    }

    @Test fun invalidBookNeverConcludesDisappearance() {
        val flow = FlowEngine(); val wt = WallTracker(WallThresholds())
        wt.scan(listOf(view(Venue.OKX, listOf(Level(100.0, 6.0)), listOf(Level(101.0, 0.1)))), 100.5, flow, 1)
        wt.scan(listOf(view(Venue.OKX, emptyList(), emptyList(), valid = false)), 100.5, flow, 2)
        assertEquals(0, wt.history().size); assertEquals(1, wt.activeCount())
    }

    @Test fun signals() {
        fun f(b: Double, s: Double) = FlowStats(60_000, b, s, 10, 10)
        assertEquals(FlowClass.LONG_ACCUMULATION, Signals.classify(f(10.0, 2.0), 0.1, 0.3, 0.0001).cls)
        assertEquals(FlowClass.SHORT_COVERING, Signals.classify(f(10.0, 2.0), 0.2, -0.3, null).cls)
        assertEquals(FlowClass.BUYING_PRESSURE, Signals.classify(f(10.0, 2.0), 0.1, null, null).cls)
        assertEquals(FlowClass.SHORT_ACCUMULATION, Signals.classify(f(2.0, 10.0), -0.1, 0.3, null).cls)
        assertEquals(FlowClass.LONG_CLOSING, Signals.classify(f(2.0, 10.0), -0.1, -0.3, null).cls)
        assertEquals(FlowClass.NEUTRAL, Signals.classify(f(5.0, 5.2), 0.0, 0.0, null).cls)
        assertEquals("ALTA", Signals.classify(f(10.0, 2.0), 0.1, 0.3, null).confidence)
    }

    @Test fun consolidation_sumsPerVenueAndReportsMissing() {
        val c = Consolidate.build(listOf(
            view(Venue.BINANCE, listOf(Level(96500.0, 4.2)), listOf(Level(96501.0, 1.0))),
            view(Venue.BYBIT, listOf(Level(96500.0, 3.8)), listOf(Level(96501.0, 1.0))),
            view(Venue.OKX, listOf(Level(96500.0, 2.5)), emptyList(), valid = false),
        ), null, 0.0, 50, 0.0, Venue.entries.toSet(), false)
        assertEquals(8.0, c.bids.single().total, 1e-9)
        assertEquals(listOf(Venue.OKX), c.missing)
        val g = Consolidate.build(listOf(view(Venue.BINANCE, listOf(Level(96503.0, 1.0), Level(96507.0, 2.0)), emptyList())),
            null, 10.0, 50, 0.0, Venue.entries.toSet(), false)
        assertEquals(3.0, g.bids.single().total, 1e-9)
    }

    @Test fun liquidationRankingsAndHeatmap() {
        val r = LiqRadar(LiqThresholds())
        val now = System.currentTimeMillis()
        r.add(LiqEvent(Venue.BYBIT, "BTCUSDT", 58000.0, 0.5, 0.5, "BTC", LiqSide.LONG, now, "t", now))
        r.add(LiqEvent(Venue.OKX, "BTC-USDT-SWAP", 59000.0, 2.0, 200.0, "contratos", LiqSide.SHORT, now, "t", now))
        assertEquals(2.0, r.top(60_000, null).first().qtyBtc, 0.0)
        assertEquals(0.5, r.totals(60_000).longBtc, 0.0)
        val h = r.heatmap(60_000, 10, 10, 57000.0, 60000.0)
        assertEquals(2.0, h.max, 0.0)
        assertEquals(LiqClass.LARGE, r.thresholds.classify(2.0))
    }
}
