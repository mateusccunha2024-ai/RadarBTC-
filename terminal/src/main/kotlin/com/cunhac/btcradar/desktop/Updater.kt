package com.cunhac.btcradar.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cunhac.btcradar.data.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File

/**
 * Atualização pelo próprio app: consulta as versões publicadas no GitHub (releases públicas do repositório),
 * baixa o instalador da plataforma (.msi no Windows, .apk no Android) e abre a instalação.
 * Windows: instala em modo silencioso com barra de progresso e reabre o app. Android: o sistema pede a confirmação.
 */
object Updater {
    private const val API = "https://api.github.com/repos/mateusccunha2024-ai/RadarBTC-/releases?per_page=30"
    var latest by mutableStateOf(0)
        private set
    private var url: String? = null
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    /** Número do build instalado (0 = versão de desenvolvimento: nunca oferece atualização). */
    val current: Int by lazy { runCatching { Platform.buildNumber() }.getOrDefault(0) }
    val available: Boolean get() = current in 1 until latest

    suspend fun check() {
        runCatching {
            val prefix = if (Platform.isMobile) "build-" else "windows-"
            val ext = if (Platform.isMobile) ".apk" else ".msi"
            var best = 0; var bestUrl: String? = null
            Net.getJson(API).jsonArray.forEach { r ->
                val o = r.jsonObject
                val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@forEach
                if (!tag.startsWith(prefix)) return@forEach
                val n = tag.removePrefix(prefix).toIntOrNull() ?: return@forEach
                val a = o["assets"]?.jsonArray?.map { it.jsonObject }
                    ?.firstOrNull { (it["name"]?.jsonPrimitive?.content ?: "").endsWith(ext) } ?: return@forEach
                if (n > best) { best = n; bestUrl = a["browser_download_url"]?.jsonPrimitive?.content }
            }
            if (best > 0 && bestUrl != null) { latest = best; url = bestUrl }
        }
    }

    suspend fun install() {
        val u = url ?: return
        if (busy) return
        busy = true
        status = "Baixando a versão $latest…"
        val r = runCatching {
            val f = withContext(Dispatchers.IO) {
                val dir = Platform.updateDir().apply { mkdirs() }
                val out = File(dir, if (Platform.isMobile) "RadarBTC-$latest.apk" else "RadarBTC-$latest.msi")
                Net.client.newCall(Request.Builder().url(u).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("download vazio")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L; var lastPct = -1
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                o.write(buf, 0, n); done += n
                                if (total > 0) { val p = (done * 100 / total).toInt(); if (p != lastPct) { lastPct = p; status = "Baixando a versão $latest… $p%" } }
                            }
                        }
                    }
                }
                out
            }
            status = if (Platform.isMobile) "Abrindo a instalação…" else "Instalando… o app fecha e abre de novo sozinho."
            withContext(Dispatchers.IO) { Platform.installUpdate(f) }
        }
        busy = false
        r.exceptionOrNull()?.let { status = "Não deu para atualizar: ${it.message ?: it::class.simpleName}" }
    }
}

/** Faixa no topo: aparece só quando há versão nova (ou durante/depois de uma tentativa de atualização). */
@Composable
fun UpdateBar() {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { Updater.check(); delay(3 * 3_600_000L) } }
    val st = Updater.status
    if (!Updater.available && st == null) return
    Row(Modifier.fillMaxWidth().background(Q.Panel2).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (Updater.available) {
            T("Nova versão disponível (${Updater.latest}; você está na ${Updater.current})", Q.Accent, 12, bold = true, wrap = true,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            if (!Updater.busy) Tap({ scope.launch { Updater.install() } }) {
                Text(L("Atualizar agora"), color = Q.Bg, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
                    modifier = Modifier.background(Q.Accent).border(1.dp, Q.Accent).padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        st?.let { Spacer(Modifier.width(12.dp)); T(it, Q.Muted, 11, wrap = true, modifier = Modifier.weight(1f, fill = false)) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Q.Line))
}
