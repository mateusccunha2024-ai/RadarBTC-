package com.cunhac.btcradar.desktop

import com.cunhac.btcradar.engine.HistorySink
import com.cunhac.btcradar.engine.LiqEvent
import com.cunhac.btcradar.engine.LogLine
import com.cunhac.btcradar.engine.TradeEvent
import com.cunhac.btcradar.engine.Wall
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.prefs.Preferences

object AppDirs {
    val root = File(System.getProperty("user.home"), ".radarbtc").apply { mkdirs() }
    val logs = File(root, "logs").apply { mkdirs() }
    val db = File(root, "radarbtc-v8.db")
}

/** Log em arquivo com rotação (5 arquivos de 2 MB). */
object FileLog {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    private val logger: Logger = Logger.getLogger("RadarBTC").apply {
        useParentHandlers = false
        runCatching {
            addHandler(FileHandler(File(AppDirs.logs, "radarbtc-%g.log").path, 2_000_000, 5, true).apply {
                formatter = object : Formatter() {
                    override fun format(r: LogRecord) = "${fmt.format(Date(r.millis))} ${r.level} ${r.message}\n"
                }
            })
        }
    }

    fun write(l: LogLine) {
        val lvl = when (l.level.name) { "ERROR" -> Level.SEVERE; "WARN" -> Level.WARNING; else -> Level.INFO }
        logger.log(lvl, "[${l.venue?.label ?: "app"}] ${l.text}")
    }

    fun info(text: String) = logger.info("[app] $text")
}

/**
 * Histórico próprio em SQLite. Gravação em lote numa thread separada (não atrasa a captura).
 * Nada é apagado automaticamente.
 */
class SqliteHistory : HistorySink {
    private val queue = LinkedBlockingQueue<(Connection) -> Unit>(200_000)
    @Volatile var error: String? = null; private set
    @Volatile var written = 0L; private set
    @Volatile var dropped = 0L; private set
    private var conn: Connection? = null
    private val thread: Thread

    init {
        conn = try {
            DriverManager.getConnection("jdbc:sqlite:${AppDirs.db.path}").also { c ->
                c.createStatement().use { s ->
                    s.executeUpdate("PRAGMA journal_mode=WAL")
                    s.executeUpdate("CREATE TABLE IF NOT EXISTS liquidations(recv_ms INTEGER, exch_ms INTEGER, venue TEXT, symbol TEXT, side TEXT, price REAL, qty_btc REAL, orig_qty REAL, orig_unit TEXT, source TEXT)")
                    s.executeUpdate("CREATE INDEX IF NOT EXISTS ix_liq_t ON liquidations(recv_ms)")
                    s.executeUpdate("CREATE TABLE IF NOT EXISTS large_trades(recv_ms INTEGER, exch_ms INTEGER, venue TEXT, aggressor TEXT, price REAL, qty_btc REAL)")
                    s.executeUpdate("CREATE INDEX IF NOT EXISTS ix_lt_t ON large_trades(recv_ms)")
                    s.executeUpdate("CREATE TABLE IF NOT EXISTS wall_events(ts_ms INTEGER, venue TEXT, side TEXT, price REAL, status TEXT, class TEXT, qty_initial REAL, qty_current REAL, qty_peak REAL, first_seen_ms INTEGER, last_seen_ms INTEGER, traded_near_btc REAL)")
                    s.executeUpdate("CREATE INDEX IF NOT EXISTS ix_we_t ON wall_events(ts_ms)")
                }
                c.autoCommit = false
            }
        } catch (e: Exception) {
            error = "banco indisponível: ${e.message}"; null
        }
        thread = Thread({ loop() }, "sqlite-writer").apply { isDaemon = true; start() }
    }

    private fun loop() {
        val c = conn ?: return
        val batch = ArrayList<(Connection) -> Unit>()
        while (true) {
            val first = queue.poll(1, TimeUnit.SECONDS) ?: continue
            batch += first; queue.drainTo(batch, 5_000)
            try {
                batch.forEach { it(c) }
                c.commit(); written += batch.size
            } catch (e: Exception) {
                error = "falha de gravação: ${e.message}"
                runCatching { c.rollback() }
            }
            batch.clear()
        }
    }

    private fun enqueue(op: (Connection) -> Unit) { if (conn != null && !queue.offer(op)) dropped++ }

    override fun liquidation(e: LiqEvent) = enqueue { c ->
        c.prepareStatement("INSERT INTO liquidations VALUES(?,?,?,?,?,?,?,?,?,?)").use { p ->
            p.setLong(1, e.recvMs); p.setLong(2, e.timeMs); p.setString(3, e.venue.name); p.setString(4, e.symbol); p.setString(5, e.side.name)
            p.setDouble(6, e.price); p.setDouble(7, e.qtyBtc); p.setDouble(8, e.origQty); p.setString(9, e.origUnit); p.setString(10, e.source)
            p.executeUpdate()
        }
    }

    override fun largeTrade(t: TradeEvent) = enqueue { c ->
        c.prepareStatement("INSERT INTO large_trades VALUES(?,?,?,?,?,?)").use { p ->
            p.setLong(1, t.recvMs); p.setLong(2, t.timeMs); p.setString(3, t.venue.name); p.setString(4, t.aggressor.name)
            p.setDouble(5, t.price); p.setDouble(6, t.qtyBtc); p.executeUpdate()
        }
    }

    override fun wallEvent(w: Wall) = enqueue { c ->
        c.prepareStatement("INSERT INTO wall_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?)").use { p ->
            p.setLong(1, w.statusMs); p.setString(2, w.venue.name); p.setString(3, w.side.name); p.setDouble(4, w.price)
            p.setString(5, w.status.name); p.setString(6, w.cls.name); p.setDouble(7, w.initialQty); p.setDouble(8, w.currentQty)
            p.setDouble(9, w.peakQty); p.setLong(10, w.firstSeenMs); p.setLong(11, w.lastSeenMs); p.setDouble(12, w.tradedNearBtc)
            p.executeUpdate()
        }
    }

    /** Contagem de linhas por tabela (conexão separada, só leitura). */
    fun counts(): Map<String, Long> = try {
        DriverManager.getConnection("jdbc:sqlite:${AppDirs.db.path}").use { c ->
            listOf("liquidations", "large_trades", "wall_events").associateWith { t ->
                c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $t").use { r -> if (r.next()) r.getLong(1) else 0L } }
            }
        }
    } catch (e: Exception) { emptyMap() }

    val pending: Int get() = queue.size

    /** Liquidações reais gravadas num intervalo (para marcar no heatmap). */
    fun liquidations(fromMs: Long, toMs: Long, venues: Set<com.cunhac.btcradar.engine.Venue>): List<LiqEvent> = try {
        DriverManager.getConnection("jdbc:sqlite:${AppDirs.db.path}").use { c ->
            c.prepareStatement("SELECT recv_ms, exch_ms, venue, symbol, side, price, qty_btc, orig_qty, orig_unit, source FROM liquidations WHERE recv_ms BETWEEN ? AND ? ORDER BY qty_btc DESC LIMIT 20000").use { p ->
                p.setLong(1, fromMs); p.setLong(2, toMs)
                p.executeQuery().use { r ->
                    val out = ArrayList<LiqEvent>()
                    while (r.next()) {
                        val v = runCatching { com.cunhac.btcradar.engine.Venue.valueOf(r.getString(3)) }.getOrNull() ?: continue
                        if (v !in venues) continue
                        out += LiqEvent(v, r.getString(4), r.getDouble(6), r.getDouble(7), r.getDouble(8), r.getString(9),
                            com.cunhac.btcradar.engine.LiqSide.valueOf(r.getString(5)), r.getLong(2), r.getString(10), r.getLong(1))
                    }
                    out
                }
            }
        }
    } catch (e: Exception) { emptyList() }
}

/** Evento de parede gravado (aba LIQUIDITY HISTORY). */
data class WallRecord(val tsMs: Long, val venue: com.cunhac.btcradar.engine.Venue, val bid: Boolean, val price: Double, val status: String,
                      val cls: String, val peak: Double, val current: Double, val firstSeenMs: Long, val lastSeenMs: Long, val tradedNear: Double)

/** Eventos de paredes num intervalo (mais recentes primeiro). */
fun SqliteHistory.wallEvents(fromMs: Long, toMs: Long, limit: Int = 20_000): List<WallRecord> = try {
    DriverManager.getConnection("jdbc:sqlite:${AppDirs.db.path}").use { c ->
        c.prepareStatement("SELECT ts_ms, venue, side, price, status, class, qty_peak, qty_current, first_seen_ms, last_seen_ms, traded_near_btc FROM wall_events WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms DESC LIMIT ?").use { p ->
            p.setLong(1, fromMs); p.setLong(2, toMs); p.setInt(3, limit)
            p.executeQuery().use { r ->
                val out = ArrayList<WallRecord>()
                while (r.next()) {
                    val v = runCatching { com.cunhac.btcradar.engine.Venue.valueOf(r.getString(2)) }.getOrNull() ?: continue
                    out += WallRecord(r.getLong(1), v, r.getString(3) == "BID", r.getDouble(4), r.getString(5), r.getString(6),
                        r.getDouble(7), r.getDouble(8), r.getLong(9), r.getLong(10), r.getDouble(11))
                }
                out
            }
        }
    }
} catch (e: Exception) { emptyList() }

/** Configurações salvas entre sessões (registro do usuário no Windows). */
object SettingsStore {
    private val p: Preferences = Preferences.userRoot().node("radarbtc-v8")
    fun d(k: String, def: Double) = p.getDouble(k, def)
    fun i(k: String, def: Int) = p.getInt(k, def)
    fun b(k: String, def: Boolean) = p.getBoolean(k, def)
    fun s(k: String, def: String) = p.get(k, def) ?: def
    fun put(k: String, v: Any) {
        when (v) { is Double -> p.putDouble(k, v); is Int -> p.putInt(k, v); is Boolean -> p.putBoolean(k, v); else -> p.put(k, v.toString()) }
        runCatching { p.flush() }
    }
}
