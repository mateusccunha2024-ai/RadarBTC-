package com.cunhac.btcradar.data.model

import java.time.LocalDate

enum class Exchange(val label: String) { BINANCE("Binance"), BYBIT("Bybit"), OKX("OKX") }

/** Lado da POSIÇÃO que foi liquidada. */
enum class Side { LONG, SHORT }

data class Liquidation(
    val exchange: Exchange,
    val side: Side,
    val price: Double,
    val qtyBtc: Double,
    val timeMs: Long,
) {
    val usd: Double get() = price * qtyBtc
}

data class BookLevel(val price: Double, val sizeBtc: Double)

data class OrderBookSnapshot(
    val exchange: Exchange,
    val bids: List<BookLevel>,
    val asks: List<BookLevel>,
)

data class Candle(val timeMs: Long, val open: Double, val high: Double, val low: Double, val close: Double)

data class OiPoint(val timeMs: Long, val oiUsd: Double)

data class MarketStats(
    val price: Double,
    val change24hPct: Double,
    val fundingRate: Double,
    val openInterestBtc: Double,
)

/** Uma faixa de preço do mapa de liquidez. */
data class PriceBin(
    val low: Double,
    val high: Double,
    val longLiqUsd: Double,   // liquidações estimadas de longs (ficam abaixo do preço)
    val shortLiqUsd: Double,  // liquidações estimadas de shorts (ficam acima do preço)
    val bidUsd: Double,       // paredes de compra somadas das corretoras
    val askUsd: Double,       // paredes de venda somadas das corretoras
) {
    val mid: Double get() = (low + high) / 2
    val liqUsd: Double get() = longLiqUsd + shortLiqUsd
    val bookUsd: Double get() = bidUsd + askUsd
    fun contains(p: Double) = p >= low && p < high
}

/** Fluxo diário dos ETFs spot de Bitcoin nos EUA, em milhões de US$. */
data class EtfDay(
    val date: LocalDate,
    val flows: Map<String, Double>,
    val total: Double,
)
