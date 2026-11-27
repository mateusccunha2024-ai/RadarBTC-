package com.cunhac.btcradar.desktop

import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.engine.AlertKind
import com.cunhac.btcradar.engine.CORE_VENUES
import com.cunhac.btcradar.engine.HeatmapModel
import com.cunhac.btcradar.engine.Hub
import com.cunhac.btcradar.engine.MarketAlert
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.Request
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Configuração dos alertas externos. Guardada no registro do usuário do Windows (só nesta máquina). */
data class AlertConfig(
    val kinds: Set<AlertKind> = setOf(AlertKind.LIQ_EXTREME, AlertKind.WALL_PULLED, AlertKind.HEAT_ZONE, AlertKind.SIGNAL_CHANGE, AlertKind.MACRO_EVENT, AlertKind.MACRO_SURPRISE),
    val telegramOn: Boolean = false,
    val telegramToken: String = "",
    val telegramChat: String = "",
    val mailOn: Boolean = false,
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 465,
    val smtpUser: String = "",
    val smtpPass: String = "",
    val mailTo: String = "",
    val cooldownMin: Double = 5.0,
    val zonePct: Double = 0.3,
    val zoneMinBtc: Double = 200.0,
    /** Notificação no próprio aparelho (celular ou bandeja do Windows). */
    val deviceOn: Boolean = Platform.isMobile,
) {
    fun save() {
        SettingsStore.put("al.kinds", kinds.joinToString("|") { it.name })
        SettingsStore.put("al.tg.on", telegramOn); SettingsStore.put("al.tg.token", telegramToken); SettingsStore.put("al.tg.chat", telegramChat)
        SettingsStore.put("al.mail.on", mailOn); SettingsStore.put("al.smtp.host", smtpHost); SettingsStore.put("al.smtp.port", smtpPort)
        SettingsStore.put("al.smtp.user", smtpUser); SettingsStore.put("al.smtp.pass", smtpPass); SettingsStore.put("al.mail.to", mailTo)
        SettingsStore.put("al.device", deviceOn)
        SettingsStore.put("al.cool", cooldownMin); SettingsStore.put("al.zone.pct", zonePct); SettingsStore.put("al.zone.min", zoneMinBtc)
    }

    companion object {
        fun load(): AlertConfig {
            val d = AlertConfig()
            val k = SettingsStore.s("al.kinds", d.kinds.joinToString("|") { it.name }).split('|')
                .mapNotNull { n -> AlertKind.entries.firstOrNull { it.name == n } }.toSet()
            return AlertConfig(k, SettingsStore.b("al.tg.on", false), SettingsStore.s("al.tg.token", ""), SettingsStore.s("al.tg.chat", ""),
                SettingsStore.b("al.mail.on", false), SettingsStore.s("al.smtp.host", d.smtpHost), SettingsStore.i("al.smtp.port", d.smtpPort),
                SettingsStore.s("al.smtp.user", ""), SettingsStore.s("al.smtp.pass", ""), SettingsStore.s("al.mail.to", ""),
                SettingsStore.d("al.cool", d.cooldownMin), SettingsStore.d("al.zone.pct", d.zonePct), SettingsStore.d("al.zone.min", d.zoneMinBtc),
                SettingsStore.b("al.device", d.deviceOn))
        }
    }
}

data class SentAlert(val timeMs: Long, val kind: AlertKind, val text: String, val result: String, val ok: Boolean)

/**
 * Recebe eventos do hub, aplica filtros e intervalo mínimo por evento, e envia para Telegram e/ou e-mail.
 * Também vigia a aproximação do preço às maiores zonas do heatmap de liquidações.
 */
class AlertEngine(private val hub: Hub, private val heat: HeatLoader) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val config = MutableStateFlow(AlertConfig.load())
    val sent = MutableStateFlow<List<SentAlert>>(emptyList())
    private val lastByKey = ConcurrentHashMap<String, Long>()
    private val recentSends = ArrayDeque<Long>()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        hub.listeners.add { onAlert(it) }
        scope.launch { while (isActive) { delay(15_000); runCatching { checkZones() } } }
    }

    fun update(c: AlertConfig) { config.value = c; c.save() }

    private fun checkZones() {
        val c = config.value
        if (AlertKind.HEAT_ZONE !in c.kinds) return
        val d = heat.state.value.data ?: return
        val price = hub.price() ?: return
        val sm = HeatmapModel.summary(d, CORE_VENUES.toSet(), price, d.pStep * 2)
        for (k in sm.above + sm.below) {
            if (k.btc < c.zoneMinBtc || abs(k.distancePct) > c.zonePct) continue
            val side = if (k.price > price) "acima (shorts a liquidar)" else "abaixo (longs a liquidar)"
            onAlert(MarketAlert(AlertKind.HEAT_ZONE, "zone|${(k.price / 50).toLong()}",
                String.format(BR, "Preço %,.1f a %.2f%% da zona %,.0f %s com ~%,.0f BTC estimados (%s)", price, abs(k.distancePct), k.price, side, k.btc, d.range.label)))
        }
    }

    private fun onAlert(a: MarketAlert) {
        val c = config.value
        if (a.kind !in c.kinds || (!c.telegramOn && !c.mailOn && !c.deviceOn)) return
        val now = System.currentTimeMillis()
        val last = lastByKey[a.key]
        if (last != null && now - last < c.cooldownMin * 60_000) return
        // Proteção contra rajadas: no máximo 20 envios por minuto.
        synchronized(recentSends) {
            while (recentSends.isNotEmpty() && now - recentSends.first() > 60_000) recentSends.removeFirst()
            if (recentSends.size >= 20) return
            recentSends.addLast(now)
        }
        lastByKey[a.key] = now
        if (lastByKey.size > 5_000) lastByKey.entries.removeIf { now - it.value > 3_600_000 }
        scope.launch { dispatch(a, c) }
    }

    /** Envia uma mensagem de teste pelos canais ligados (mesmo sem eventos). */
    fun test() {
        val c = config.value
        scope.launch { dispatch(MarketAlert(AlertKind.LIQ_EXTREME, "test", "Teste de alerta do Radar BTC: canal configurado corretamente."), c, force = true) }
    }

    private fun dispatch(a: MarketAlert, c: AlertConfig, force: Boolean = false) {
        val text = "Radar BTC · ${a.kind.label}\n${a.text}\n${hhmmss(a.timeMs)}"
        val results = ArrayList<String>()
        var ok = true
        if (c.deviceOn || force) {
            val ok = runCatching { Platform.notify("Radar BTC · ${a.kind.label}", a.text) }.getOrDefault(false)
            results += (if (Platform.isMobile) "Celular: " else "Windows: ") + if (ok) "notificado" else "notificações bloqueadas ou indisponíveis"
        }
        if (c.telegramOn || (force && c.telegramToken.isNotBlank())) {
            val r = runCatching { telegram(c, text) }
            results += "Telegram: " + (r.exceptionOrNull()?.message?.take(80) ?: "enviado")
            ok = ok && r.isSuccess
        }
        if (c.mailOn || (force && c.smtpUser.isNotBlank())) {
            val r = runCatching { mail(c, "Radar BTC: ${a.kind.label}", text) }
            results += "E-mail: " + (r.exceptionOrNull()?.message?.take(80) ?: "enviado")
            ok = ok && r.isSuccess
        }
        if (results.isEmpty()) results += "nenhum canal configurado"
        val entry = SentAlert(a.timeMs, a.kind, a.text, results.joinToString(" · "), ok && results.none { it.startsWith("nenhum") })
        sent.update { (listOf(entry) + it).take(200) }
        FileLog.info("alerta ${a.kind}: ${entry.result}")
    }

    private fun telegram(c: AlertConfig, text: String) {
        require(c.telegramToken.isNotBlank() && c.telegramChat.isNotBlank()) { "token ou chat ID vazio" }
        val body = FormBody.Builder().add("chat_id", c.telegramChat.trim()).add("text", text).build()
        val req = Request.Builder().url("https://api.telegram.org/bot${c.telegramToken.trim()}/sendMessage").post(body).build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}: ${r.body?.string()?.take(120) ?: ""}")
        }
    }

    private fun mail(c: AlertConfig, subject: String, text: String) {
        require(c.smtpHost.isNotBlank() && c.smtpUser.isNotBlank() && c.mailTo.isNotBlank()) { "servidor, usuário ou destinatário vazio" }
        val props = Properties().apply {
            put("mail.smtp.host", c.smtpHost.trim()); put("mail.smtp.port", c.smtpPort.toString()); put("mail.smtp.auth", "true")
            if (c.smtpPort == 465) put("mail.smtp.ssl.enable", "true") else put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "10000"); put("mail.smtp.timeout", "15000")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(c.smtpUser.trim(), c.smtpPass)
        })
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(c.smtpUser.trim()))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(c.mailTo.trim()))
            setSubject(subject, "UTF-8")
            setText(text, "UTF-8")
        }
        Transport.send(msg)
    }
}
