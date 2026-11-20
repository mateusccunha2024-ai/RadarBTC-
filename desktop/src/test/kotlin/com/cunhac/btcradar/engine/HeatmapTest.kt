package com.cunhac.btcradar.engine

import com.cunhac.btcradar.data.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapTest {
    @Test fun oiIncreaseCreatesBandsAndCrossingRemovesThem() {
        val step = 3_600_000L
        val now = System.currentTimeMillis()
        val t0 = now - 30 * step
        val candles = (0 until 30).map { i -> Candle(t0 + i * step, 60_000.0, 60_100.0, 59_900.0, 60_000.0) }
        val oi = mapOf(Venue.OKX to (0 until 30).map { i -> OiBtc(t0 + i * step, if (i < 10) 1_000.0 else 1_100.0) })
        val d = HeatmapModel.build(HeatRange.D3, candles, oi, mapOf(Venue.OKX to "teste"), now)
        assertNotNull(d)
        val sm = HeatmapModel.summary(d!!, setOf(Venue.OKX), 60_000.0, d.pStep * 2)
        assertEquals(100.0, sm.longsBelowBtc, 1e-6)  // 100 BTC novos de OI -> 100 BTC em longs e 100 em shorts
        assertEquals(100.0, sm.shortsAboveBtc, 1e-6)
        assertTrue(sm.above.isNotEmpty() && sm.below.isNotEmpty())
        val (l, s) = HeatmapModel.combine(d, setOf(Venue.OKX))
        assertTrue(l.sumOf { it.sum().toDouble() } > 0 && s.sumOf { it.sum().toDouble() } > 0)
    }

    @Test fun consolidationGroupingKeepsSpread() {
        fun view(v: Venue, b: List<Level>, a: List<Level>) = BookView(v, true, b, a, b.size, a.size, 0, 0, 0, 0, "")
        val c = Consolidate.build(listOf(view(Venue.OKX, listOf(Level(59_089.0, 1.0, 7)), listOf(Level(59_090.0, 2.0, 3)))),
            null, 100.0, 10, 0.0, Venue.entries.toSet(), false)
        assertEquals(59_000.0, c.bids.single().price, 0.0)
        assertEquals(59_100.0, c.asks.single().price, 0.0)
        assertEquals(7, c.bids.single().okxOrders)
    }
}
