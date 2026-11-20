package com.cunhac.btcradar.domain

import com.cunhac.btcradar.data.etf.FarsideEtfSource
import com.cunhac.btcradar.data.model.Candle
import com.cunhac.btcradar.data.model.OiPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidityMapTest {

    private fun c(t: Long, lo: Double, hi: Double) = Candle(t, lo, hi, lo, (lo + hi) / 2)

    @Test
    fun oiUp_createsLevelsAroundEntry() {
        val candles = listOf(c(0, 100_000.0, 100_000.0), c(1, 100_000.0, 100_000.0))
        val oi = listOf(OiPoint(0, 1e9), OiPoint(1, 1.1e9))
        val lv = LiquidityMap.estimateLevels(candles, oi)
        assertEquals(8, lv.size)
        assertTrue(lv.filter { it.isLong }.all { it.price < 100_000 })
        assertTrue(lv.filter { !it.isLong }.all { it.price > 100_000 })
    }

    @Test
    fun priceCrossing_removesLevel() {
        val candles = listOf(
            c(0, 100_000.0, 100_000.0),
            c(1, 100_000.0, 100_000.0),
            c(2, 98_000.0, 100_000.0),
        )
        val oi = listOf(OiPoint(0, 1e9), OiPoint(1, 1.1e9), OiPoint(2, 1.1e9))
        val longs = LiquidityMap.estimateLevels(candles, oi).filter { it.isLong }
        assertEquals(2, longs.size)
    }

    @Test
    fun farsideNumbers() {
        assertEquals(-12.3, FarsideEtfSource.parseNumber("(12.3)")!!, 1e-9)
        assertEquals(1234.5, FarsideEtfSource.parseNumber("1,234.5")!!, 1e-9)
        assertEquals(0.0, FarsideEtfSource.parseNumber("-")!!, 1e-9)
    }
}
