package com.cunhac.btcradar.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.engine.NumLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Tradução na hora de desenhar: todo texto da interface passa por [L]. */
fun L(s: String): String = I18n.tr(s)

/**
 * Idiomas do app. O código-fonte fica em português do Brasil; cada texto é traduzido ao ser desenhado:
 *  1. tabela pronta do idioma (`i18n/<código>.tsv`, gerada a partir do código e revisada);
 *  2. moldes com lacunas ({0}, {1}…) para textos com valores no meio;
 *  3. o que não estiver na tabela (manchetes, textos montados na hora) é traduzido por tradução automática uma vez
 *     e guardado no aparelho (`i18n-cache-<código>.tsv`). Enquanto não chega, aparece o original.
 * Números são mascarados antes da busca, então preços que mudam não geram textos novos.
 */
object I18n {
    data class Lang(val code: String, val label: String, val tag: String, val mt: String)

    val LANGS = listOf(
        Lang("pt", "Português (Brasil)", "pt-BR", "pt"), Lang("en", "English", "en-US", "en"), Lang("es", "Español", "es-ES", "es"),
        Lang("fr", "Français", "fr-FR", "fr"), Lang("de", "Deutsch", "de-DE", "de"), Lang("it", "Italiano", "it-IT", "it"),
        Lang("ru", "Русский", "ru-RU", "ru"), Lang("zh", "中文（简体）", "zh-CN", "zh-CN"), Lang("ja", "日本語", "ja-JP", "ja"),
        Lang("ko", "한국어", "ko-KR", "ko"),
    )

    /** Título fixo do seletor (legível em qualquer idioma, para achar o caminho de volta). */
    const val LANG_TITLE = "Idioma · Language · 语言"
    private val PASS by lazy { LANGS.map { it.label }.toSet() + LANG_TITLE }

    private var code by mutableStateOf(runCatching { SettingsStore.s("ui.lang", "pt") }.getOrDefault("pt"))
    /** Aumenta quando chegam traduções automáticas: quem desenha texto relê. */
    private var version by mutableIntStateOf(0)

    val lang: Lang get() = LANGS.firstOrNull { it.code == code } ?: LANGS[0]
    val locale: Locale get() = Locale.forLanguageTag(lang.tag)

    init { NumLocale.current = locale }

    fun use(c: String) {
        if (LANGS.none { it.code == c }) return
        code = c
        runCatching { SettingsStore.put("ui.lang", c) }
        NumLocale.current = locale
        version++
    }

    // ------------------------------------------------------------------ tabelas

    private class Table(val exact: HashMap<String, String>, val prefixed: HashMap<String, MutableList<Tpl>>, val loose: List<Tpl>)
    private class Tpl(val regex: Regex, val out: String)

    private val tables = ConcurrentHashMap<String, Table>()
    private val memo = ConcurrentHashMap<String, String>()      // "<lang>|<texto mascarado>" → tradução mascarada ("" = sem tradução)
    private val mtCache = ConcurrentHashMap<String, String>()   // "<lang>|<texto mascarado>" → tradução automática
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Lê "origem<TAB>tradução" (\n e \t escapados). */
    fun parseTsv(text: String): List<Pair<String, String>> = text.lineSequence().mapNotNull { line ->
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val i = line.indexOf('\t'); if (i <= 0) return@mapNotNull null
        unesc(line.substring(0, i)) to unesc(line.substring(i + 1))
    }.toList()

    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")
    private fun esc(s: String) = s.replace("\t", "\\t").replace("\n", "\\n")

    private val PH = Regex("\\{(\\d+)\\}")

    private fun build(rows: List<Pair<String, String>>): Table {
        val exact = HashMap<String, String>(); val pre = HashMap<String, MutableList<Tpl>>(); val loose = ArrayList<Tpl>()
        for ((k, v) in rows) {
            if (!hasWords(PH.replace(k, ""))) continue   // molde sem texto fixo ("{0} · {1}") casaria com tudo
            if (!PH.containsMatchIn(k)) { exact[k] = v; continue }
            // Lacunas numeradas viram grupos; o texto fixo entre elas precisa bater exatamente.
            val parts = PH.split(k)
            val idx = PH.findAll(k).map { it.groupValues[1] }.toList()
            val sb = StringBuilder("^")
            parts.forEachIndexed { i, p -> sb.append(Regex.escape(p)); if (i < idx.size) sb.append("(?<g").append(idx[i]).append(">.+?)") }
            sb.append("$")
            val tpl = runCatching { Tpl(Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL), v) }.getOrNull() ?: continue
            val head = parts[0]
            if (head.length >= 3) pre.getOrPut(head.take(3)) { ArrayList() }.add(tpl) else loose.add(tpl)
        }
        return Table(exact, pre, loose)
    }

    private fun table(c: String): Table = tables.getOrPut(c) {
        val rows = runCatching {
            I18n::class.java.getResourceAsStream("/i18n/$c.tsv")?.bufferedReader(Charsets.UTF_8)?.use { parseTsv(it.readText()) }
        }.getOrNull() ?: emptyList()
        loadCache(c)
        build(rows)
    }

    /** Para testes: tabela a partir de texto. */
    fun loadForTest(c: String, tsv: String) { tables[c] = build(parseTsv(tsv)); memo.clear() }

    // ------------------------------------------------------------------ máscara de números

    private val NUM = Regex("(?<![\\p{L}\\d{.,⟦])[-+−]?\\d+(?:[.,\\u00A0\\u202F]\\d+)*")

    /** "Delta 15 min -117,3 BTC" → ("Delta ⟦0⟧ min ⟦1⟧ BTC", ["15", "-117,3"]). */
    fun mask(s: String): Pair<String, List<String>> {
        val nums = ArrayList<String>()
        val m = NUM.replace(s) { nums += it.value; "⟦${nums.size - 1}⟧" }
        return m to nums
    }

    private val TOK = Regex("⟦(\\d+)⟧")
    private fun unmask(s: String, nums: List<String>) = TOK.replace(s) { nums.getOrNull(it.groupValues[1].toInt()) ?: it.value }

    private fun hasWords(s: String): Boolean {
        var run = 0
        for (ch in s) { if (ch.isLetter()) { run++; if (run >= 2) return true } else run = 0 }
        return false
    }

    // ------------------------------------------------------------------ tradução

    fun tr(s: String): String {
        val c = code
        @Suppress("UNUSED_VARIABLE") val v = version   // relê quando chegam traduções automáticas
        if (c == "pt" || s.isEmpty() || !hasWords(s) || s in PASS) return s
        val (m, nums) = mask(s)
        val out = trm(c, m, 0) ?: return s
        return unmask(out, nums)
    }

    /**
     * Texto já mascarado → tradução mascarada, ou null se ainda não há (pedida à tradução automática).
     * Frases montadas com " · " são traduzidas por partes, e o que entra nas lacunas de um molde também.
     */
    private fun trm(c: String, m: String, depth: Int): String? {
        val key = "$c|$m"
        memo[key]?.let { return it.ifEmpty { null } }
        val found = lookup(c, m, depth) ?: mtCache[key]
        if (found != null) { memo[key] = found; return found }
        if (depth < 3 && m.contains(" · ")) {
            val parts = m.split(" · ")
            var all = true
            val joined = parts.joinToString(" · ") { p -> if (!hasWords(p)) p else trm(c, p, depth + 1) ?: run { all = false; p } }
            if (all) memo[key] = joined
            return joined
        }
        memo[key] = ""
        request(c, m)
        return null
    }

    private fun lookup(c: String, m: String, depth: Int = 0): String? {
        val t = table(c)
        t.exact[m]?.let { return it }
        // Texto em maiúsculas (títulos de seção): traduz a forma normal e devolve em maiúsculas.
        if (m.any { it.isLetter() } && m.none { it.isLowerCase() }) {
            val low = m.lowercase(Locale.ROOT)
            t.exact.entries.firstOrNull { it.key.lowercase(Locale.ROOT) == low }?.let { return it.value.uppercase(locale) }
        }
        val cands = (if (m.length >= 3) t.prefixed[m.take(3)] else null).orEmpty() + t.loose
        for (tp in cands) {
            val r = tp.regex.matchEntire(m) ?: continue
            return PH.replace(tp.out) { g ->
                val v = runCatching { r.groups["g" + g.groupValues[1]]?.value }.getOrNull() ?: return@replace g.value
                if (depth < 3 && hasWords(v)) trm(c, v, depth + 1) ?: v else v
            }
        }
        return null
    }

    /** Manchete (em inglês ou outra língua) no idioma do app, inclusive português. */
    fun news(title: String): String {
        val c = code
        @Suppress("UNUSED_VARIABLE") val v = version
        val (m, nums) = mask(title)
        val key = "news-$c|$m"
        mtCache[key]?.let { return unmask(it, nums) }
        request("news-$c", m)
        return title
    }

    // ------------------------------------------------------------------ tradução automática (em lote, com cache)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker = false
    private var failUntil = 0L

    private fun request(c: String, m: String) {
        val k = "$c|$m"
        if (!pending.add(k)) return
        synchronized(this) {
            if (worker) return
            worker = true
            scope.launch { pump() }
        }
    }

    private suspend fun pump() {
        while (true) {
            delay(400)
            if (pending.isEmpty()) { synchronized(this) { if (pending.isEmpty()) { worker = false; return } } }
            if (System.currentTimeMillis() < failUntil) { delay(30_000); continue }
            val byLang = pending.groupBy { it.substringBefore('|') }
            var got = 0
            for ((c, keys) in byLang) {
                val news = c.startsWith("news-")
                val lc = c.removePrefix("news-")
                val target = LANGS.firstOrNull { it.code == lc }?.mt
                if (target == null) { pending.removeAll(keys.toSet()); continue }
                // Lotes de até ~3500 caracteres, uma linha por texto.
                val batch = ArrayList<String>(); var len = 0
                for (k in keys) { val t = k.substringAfter('|'); if (len + t.length > 3500 && batch.isNotEmpty()) break; batch += k; len += t.length + 1 }
                val texts = batch.map { it.substringAfter('|').replace("\n", " ") }
                val out = runCatching { machine(texts, if (news) "auto" else "pt", target) }.getOrNull()
                if (out == null) { failUntil = System.currentTimeMillis() + 120_000; continue }
                batch.forEachIndexed { i, k ->
                    val t = out.getOrNull(i)?.trim()
                    if (!t.isNullOrEmpty() && TOK.findAll(t).count() == TOK.findAll(k).count()) { mtCache[k] = t; saveCache(lc, k, t) }
                    else mtCache[k] = k.substringAfter('|')   // não reenvia o mesmo texto sem fim
                    pending.remove(k)
                }
                got += batch.size
            }
            if (got > 0) { memo.clear(); version++ }
        }
    }

    /** Google Translate (endpoint público, sem chave). Uma linha de entrada = uma linha de saída. */
    private fun machine(texts: List<String>, from: String, to: String): List<String> {
        val q = texts.joinToString("\n")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=" + URLEncoder.encode(q, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val body = Net.client.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
        val segs = Net.parse(body).jsonArray[0] as JsonArray
        val joined = segs.joinToString("") { ((it as JsonArray)[0] as? JsonPrimitive)?.content ?: "" }
        val lines = joined.split("\n")
        if (lines.size == texts.size) return lines
        // Contagem diferente: traduz um por um.
        return texts.map { t -> runCatching { machine1(t, from, to) }.getOrDefault("") }
    }

    private fun machine1(t: String, from: String, to: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=" + URLEncoder.encode(t, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val body = Net.client.newCall(req).execute().use { r -> r.body!!.string() }
        return (Net.parse(body).jsonArray[0] as JsonArray).joinToString("") { ((it as JsonArray)[0] as? JsonPrimitive)?.content ?: "" }
    }

    private fun cacheFile(c: String) = runCatching { File(AppDirs.logs.parentFile, "i18n-cache-$c.tsv") }.getOrNull()

    private val loadedCache = ConcurrentHashMap.newKeySet<String>()
    private fun loadCache(c: String) {
        if (!loadedCache.add(c)) return
        val f = cacheFile(c) ?: return
        if (!f.exists()) return
        runCatching { parseTsv(f.readText()).forEach { (k, v) -> mtCache[k] = v } }
    }

    private fun saveCache(lang: String, k: String, v: String) {
        runCatching { cacheFile(lang)?.appendText(esc(k) + "\t" + esc(v) + "\n") }
    }
}
