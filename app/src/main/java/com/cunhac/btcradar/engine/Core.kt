package com.cunhac.btcradar.engine

/** Textos para a tela sempre no padrão brasileiro (vírgula decimal), seja qual for o idioma do aparelho. */
val PT_BR: java.util.Locale = java.util.Locale.forLanguageTag("pt-BR")

/**
 * Corretoras suportadas (perpétuos de BTC).
 * core = livro, trades e open interest completos. As demais entram só com liquidações reais.
 */
enum class Venue(val label: String, val instrument: String, val core: Boolean = true) {
    BINANCE("Binance", "BTCUSDT perp (USD-M)"),
    BYBIT("Bybit", "BTCUSDT linear"),
    OKX("OKX", "BTC-USDT-SWAP"),
    GATE("Gate", "BTC_USDT perp", core = false),
    BITGET("Bitget", "BTCUSDT perp (USDT-M)", core = false),
    DERIBIT("Deribit", "BTC-PERPETUAL (inverso, USD)", core = false),
}

/** Corretoras com livro de ofertas, trades e open interest. */
val CORE_VENUES: List<Venue> = Venue.entries.filter { it.core }

/** Quem iniciou a execução (lado agressor / taker). */
enum class Aggressor { BUY, SELL }

/** Lado da POSIÇÃO liquidada. */
enum class LiqSide { LONG, SHORT }

data class TradeEvent(
    val venue: Venue,
    val price: Double,
    val qtyBtc: Double,
    val aggressor: Aggressor,
    val timeMs: Long,
    val recvMs: Long = System.currentTimeMillis(),
)

/** Liquidação reportada pela corretora. origQty/origUnit preservam o valor recebido. */
data class LiqEvent(
    val venue: Venue,
    val symbol: String,
    val price: Double,
    val qtyBtc: Double,
    val origQty: Double,
    val origUnit: String,
    val side: LiqSide,
    val timeMs: Long,
    val source: String,
    val recvMs: Long = System.currentTimeMillis(),
)

/** Dados de derivativos por corretora (campos nulos = ainda não recebidos). */
data class DerivTicker(
    val markPrice: Double? = null,
    val indexPrice: Double? = null,
    val fundingRate: Double? = null,
    val oiBtc: Double? = null,
    val timeMs: Long = 0,
)

enum class ConnState { CONNECTING, SUBSCRIBED, LIVE, RESYNCING, DOWN }

/** Estado observável de uma conexão/canal. Atualizado pelas threads de rede. */
class SourceStatus(val venue: Venue, val channel: String) {
    @Volatile var state: ConnState = ConnState.CONNECTING
    @Volatile var lastMsgMs: Long = 0
    @Volatile var messages: Long = 0
    @Volatile var reconnects: Int = 0
    @Volatile var lastError: String? = null

    fun onMessage() {
        messages++
        lastMsgMs = System.currentTimeMillis()
        if (state == ConnState.SUBSCRIBED || state == ConnState.CONNECTING) state = ConnState.LIVE
    }

    /** LIVE só vale se chegou mensagem nos últimos 15 s. */
    fun effectiveState(now: Long = System.currentTimeMillis()): ConnState =
        if (state == ConnState.LIVE && now - lastMsgMs > 15_000) ConnState.DOWN else state
}

enum class LogLevel { INFO, WARN, ERROR }
data class LogLine(val timeMs: Long, val level: LogLevel, val venue: Venue?, val text: String)
