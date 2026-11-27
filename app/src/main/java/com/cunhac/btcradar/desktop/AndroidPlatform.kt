package com.cunhac.btcradar.desktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cunhac.btcradar.engine.HistorySink
import com.cunhac.btcradar.engine.LiqEvent
import com.cunhac.btcradar.engine.LiqSide
import com.cunhac.btcradar.engine.LogLine
import com.cunhac.btcradar.engine.TradeEvent
import com.cunhac.btcradar.engine.Venue
import com.cunhac.btcradar.engine.Wall
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Versão Android das peças de plataforma usadas pelo terminal comum. */
object Platform {
    lateinit var app: Context
        private set
    const val isMobile = true
    private const val CH_ALERTS = "alerts"
    const val CH_SERVICE = "monitor"

    fun init(c: Context) {
        app = c.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alertas do mercado", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Monitoramento em segundo plano", NotificationManager.IMPORTANCE_LOW))
    }

    val notifySupported: Boolean get() = true

    fun canNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
        app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private var nextId = 100
    fun notify(title: String, text: String): Boolean {
        if (!canNotify()) return false
        val open = PendingIntent.getActivity(app, 0, app.packageManager.getLaunchIntentForPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(app, CH_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        app.getSystemService(NotificationManager::class.java).notify(nextId++, n)
        return true
    }

    /** Mantém conexões e alertas ativos com o app fechado (serviço em primeiro plano). */
    const val backgroundSupported = true
    val backgroundOn: Boolean get() = SettingsStore.b("bg.on", false)
    fun setBackground(on: Boolean) {
        SettingsStore.put("bg.on", on)
        val i = Intent(app, RadarService::class.java)
        if (on) app.startForegroundService(i) else app.stopService(i)
    }
    val deviceLabel = "celular"

    /** Build instalado (versionCode = número do build do GitHub). */
    fun buildNumber(): Int = runCatching {
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)
    fun updateDir(): File = File(app.cacheDir, "update")
    /** Entrega o APK novo ao instalador do Android (mesma assinatura = atualização); o sistema pede a confirmação. */
    fun installUpdate(apk: File) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + app.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            throw IllegalStateException("libere \"Instalar apps desconhecidos\" para o Radar BTC, volte e toque em Atualizar agora de novo")
        }
        val pi = app.packageManager.packageInstaller
        val id = pi.createSession(android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite("radarbtc.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
            s.commit(done.intentSender)
        }
    }
}

object AppDirs {
    val logs: File get() = File(Platform.app.filesDir, "logs").apply { mkdirs() }
    val db: File get() = Platform.app.getDatabasePath("radarbtc-v8.db")
}

/** Log no Logcat e em arquivo (2 arquivos de 1 MB). */
object FileLog {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()
    private fun append(line: String) = synchronized(lock) {
        runCatching {
            val f = File(AppDirs.logs, "radarbtc-0.log")
            if (f.length() > 1_000_000) f.renameTo(File(AppDirs.logs, "radarbtc-1.log"))
            File(AppDirs.logs, "radarbtc-0.log").appendText(line + "\n")
        }
    }
    fun write(l: LogLine) {
        val t = "[${l.venue?.label ?: "app"}] ${l.text}"
        when (l.level.name) { "ERROR" -> Log.e("RadarBTC", t); "WARN" -> Log.w("RadarBTC", t); else -> Log.i("RadarBTC", t) }
        append("${fmt.format(Date(l.timeMs))} ${l.level} $t")
    }
    fun info(text: String) { Log.i("RadarBTC", text); append("${fmt.format(Date())} INFO [app] $text") }
}

/** Configurações salvas entre sessões (SharedPreferences). */
object SettingsStore {
    private val p by lazy { Platform.app.getSharedPreferences("radarbtc-v8", Context.MODE_PRIVATE) }
    fun d(k: String, def: Double) = p.getString(k, null)?.toDoubleOrNull() ?: def
    fun i(k: String, def: Int) = runCatching { p.getInt(k, def) }.getOrDefault(def)
    fun b(k: String, def: Boolean) = runCatching { p.getBoolean(k, def) }.getOrDefault(def)
    fun s(k: String, def: String) = runCatching { p.getString(k, def) }.getOrNull() ?: def
    fun put(k: String, v: Any) {
        p.edit().apply {
            when (v) { is Double -> putString(k, v.toString()); is Int -> putInt(k, v); is Boolean -> putBoolean(k, v); else -> putString(k, v.toString()) }
        }.apply()
    }
}

fun argbImage(width: Int, height: Int, argb: IntArray): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

fun processStats(): List<Pair<String, String>> {
    val rt = Runtime.getRuntime()
    return listOf(
        "Memória (usada / máx.)" to "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} / ${rt.maxMemory() / 1_048_576} MB",
        "Threads" to Thread.activeCount().toString(),
        "Aparelho" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
    )
}

/** Histórico próprio em SQLite do Android, gravado em lote numa thread separada. */
class SqliteHistory : HistorySink {
    private val queue = LinkedBlockingQueue<(SQLiteDatabase) -> Unit>(200_000)
    @Volatile var error: String? = null; private set
    @Volatile var written = 0L; private set
    @Volatile var dropped = 0L; private set
    private val helper = object : SQLiteOpenHelper(Platform.app, "radarbtc-v8.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS liquidations(recv_ms INTEGER, exch_ms INTEGER, venue TEXT, symbol TEXT, side TEXT, price REAL, qty_btc REAL, orig_qty REAL, orig_unit TEXT, source TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_liq_t ON liquidations(recv_ms)")
            db.execSQL("CREATE TABLE IF NOT EXISTS large_trades(recv_ms INTEGER, exch_ms INTEGER, venue TEXT, aggressor TEXT, price REAL, qty_btc REAL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_lt_t ON large_trades(recv_ms)")
            db.execSQL("CREATE TABLE IF NOT EXISTS wall_events(ts_ms INTEGER, venue TEXT, side TEXT, price REAL, status TEXT, class TEXT, qty_initial REAL, qty_current REAL, qty_peak REAL, first_seen_ms INTEGER, last_seen_ms INTEGER, traded_near_btc REAL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_we_t ON wall_events(ts_ms)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}
    }
    val db: SQLiteDatabase? = runCatching { helper.writableDatabase }.onFailure { error = "banco indisponível: ${it.message}" }.getOrNull()

    init {
        Thread({ loop() }, "sqlite-writer").apply { isDaemon = true; start() }
    }

    private fun loop() {
        val d = db ?: return
        val batch = ArrayList<(SQLiteDatabase) -> Unit>()
        while (true) {
            val first = queue.poll(1, TimeUnit.SECONDS) ?: continue
            batch += first; queue.drainTo(batch, 5_000)
            try {
                d.beginTransaction()
                try { batch.forEach { it(d) }; d.setTransactionSuccessful() } finally { d.endTransaction() }
                written += batch.size
            } catch (e: Exception) { error = "falha de gravação: ${e.message}" }
            batch.clear()
        }
    }

    private fun enqueue(op: (SQLiteDatabase) -> Unit) { if (db != null && !queue.offer(op)) dropped++ }

    override fun liquidation(e: LiqEvent) = enqueue { d ->
        d.insert("liquidations", null, ContentValues().apply {
            put("recv_ms", e.recvMs); put("exch_ms", e.timeMs); put("venue", e.venue.name); put("symbol", e.symbol); put("side", e.side.name)
            put("price", e.price); put("qty_btc", e.qtyBtc); put("orig_qty", e.origQty); put("orig_unit", e.origUnit); put("source", e.source)
        })
    }

    override fun largeTrade(t: TradeEvent) = enqueue { d ->
        d.insert("large_trades", null, ContentValues().apply {
            put("recv_ms", t.recvMs); put("exch_ms", t.timeMs); put("venue", t.venue.name); put("aggressor", t.aggressor.name)
            put("price", t.price); put("qty_btc", t.qtyBtc)
        })
    }

    override fun wallEvent(w: Wall) = enqueue { d ->
        d.insert("wall_events", null, ContentValues().apply {
            put("ts_ms", w.statusMs); put("venue", w.venue.name); put("side", w.side.name); put("price", w.price); put("status", w.status.name)
            put("class", w.cls.name); put("qty_initial", w.initialQty); put("qty_current", w.currentQty); put("qty_peak", w.peakQty)
            put("first_seen_ms", w.firstSeenMs); put("last_seen_ms", w.lastSeenMs); put("traded_near_btc", w.tradedNearBtc)
        })
    }

    fun counts(): Map<String, Long> = runCatching {
        val d = db ?: return emptyMap()
        listOf("liquidations", "large_trades", "wall_events").associateWith { t ->
            d.rawQuery("SELECT COUNT(*) FROM $t", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }
    }.getOrDefault(emptyMap())

    val pending: Int get() = queue.size

    fun liquidations(fromMs: Long, toMs: Long, venues: Set<Venue>): List<LiqEvent> = runCatching {
        val d = db ?: return emptyList()
        d.rawQuery("SELECT recv_ms, exch_ms, venue, symbol, side, price, qty_btc, orig_qty, orig_unit, source FROM liquidations WHERE recv_ms BETWEEN ? AND ? ORDER BY qty_btc DESC LIMIT 20000",
            arrayOf(fromMs.toString(), toMs.toString())).use { c ->
            val out = ArrayList<LiqEvent>()
            while (c.moveToNext()) {
                val v = runCatching { Venue.valueOf(c.getString(2)) }.getOrNull() ?: continue
                if (v !in venues) continue
                out += LiqEvent(v, c.getString(3), c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getString(8),
                    LiqSide.valueOf(c.getString(4)), c.getLong(1), c.getString(9), c.getLong(0))
            }
            out
        }
    }.getOrDefault(emptyList())
}

fun SqliteHistory.wallEvents(fromMs: Long, toMs: Long, limit: Int = 20_000): List<WallRecord> = runCatching {
    val d = db ?: return emptyList()
    d.rawQuery("SELECT ts_ms, venue, side, price, status, class, qty_peak, qty_current, first_seen_ms, last_seen_ms, traded_near_btc FROM wall_events WHERE ts_ms BETWEEN ? AND ? ORDER BY ts_ms DESC LIMIT $limit",
        arrayOf(fromMs.toString(), toMs.toString())).use { c ->
        val out = ArrayList<WallRecord>()
        while (c.moveToNext()) {
            val v = runCatching { Venue.valueOf(c.getString(1)) }.getOrNull() ?: continue
            out += WallRecord(c.getLong(0), v, c.getString(2) == "BID", c.getDouble(3), c.getString(4), c.getString(5),
                c.getDouble(6), c.getDouble(7), c.getLong(8), c.getLong(9), c.getDouble(10))
        }
        out
    }
}.getOrDefault(emptyList())
