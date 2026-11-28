package com.cunhac.btcradar.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cunhac.btcradar.data.net.Net
import com.cunhac.btcradar.engine.MacroSources
import com.cunhac.btcradar.engine.TradeMarket
import com.cunhac.btcradar.engine.TraderData
import com.cunhac.btcradar.engine.TraderSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Como o Jarvis fica mais inteligente com o tempo (atualização 14):
 *  - Checagem de números: toda resposta da IA é conferida contra os dados; número que não está nos dados derruba a resposta.
 *  - Memória: pesquisa diária na internet (Google News RSS, sem chave) vira conhecimento com fonte, data e confirmações.
 *  - Placar: cada plano do Time Machine é conferido depois com o que aconteceu (alvo, stop, nada) e vira taxa de acerto.
 *  - Ferramentas: a IA escolhe o que consultar (dados, memória, comparar ativos, notícias, plano com outro stop/alvo).
 *  - Habilidades: receitas (só dados, nunca código) propostas pelo uso e aprovadas pelo usuário; também vêm do cérebro na nuvem.
 *  - Prova: perguntas fixas que medem se cada mudança melhorou ou piorou.
 *  - Cérebro na nuvem (Supabase): guarda memória, placar e avaliações; a chave pública vem do build, nunca do código.
 */

// =====================================================================================
// Texto: palavras e números
// =====================================================================================

object JvText {
    private val STOP = setOf(
        "the", "and", "for", "with", "from", "that", "this", "are", "was", "has", "have", "will", "its", "into", "after", "over", "about",
        "como", "esta", "está", "para", "com", "uma", "que", "dos", "das", "nos", "nas", "por", "mais", "sobre", "qual", "quais", "hoje",
        "agora", "isso", "esse", "essa", "ele", "ela", "voce", "você", "meu", "minha", "seu", "sua", "pelo", "pela", "entre", "quanto",
        "what", "how", "why", "when", "where", "which", "does", "did", "can", "you", "your", "about", "now", "today", "jarvis",
        "porque", "onde", "quando", "tem", "ter", "ser", "foi", "vai", "ainda", "muito", "tudo", "nada", "algo", "fala", "diga", "mostra",
    )

    fun words(s: String): List<String> = JarvisText.fold(s).split(Regex("[^a-z0-9$]+")).filter { it.length >= 3 && it !in STOP }

    private val NUM = Regex("[-+]?\\d[\\d.,]*")

    /** Leituras possíveis de um número escrito (pt-BR "59.930,6", en "59,930.6", "0,0624", "1.500"...). */
    fun readings(raw: String): List<Double> {
        val s = raw.trim().trimEnd('.', ',').trimStart('+')
        if (s.isEmpty() || s == "-") return emptyList()
        val out = LinkedHashSet<Double>()
        val hasDot = '.' in s; val hasComma = ',' in s
        fun add(t: String) { t.toDoubleOrNull()?.let { out += it } }
        when {
            hasDot && hasComma -> if (s.lastIndexOf(',') > s.lastIndexOf('.')) add(s.replace(".", "").replace(',', '.')) else add(s.replace(",", ""))
            hasComma -> { if (Regex("-?\\d{1,3}(,\\d{3})+").matches(s)) add(s.replace(",", "")); add(s.replace(',', '.')) }
            hasDot -> { if (Regex("-?\\d{1,3}(\\.\\d{3})+").matches(s)) add(s.replace(".", "")); add(s) }
            else -> add(s)
        }
        return out.toList()
    }

    fun numbers(s: String): List<Pair<String, List<Double>>> = NUM.findAll(s).map { it.value to readings(it.value) }.filter { it.second.isNotEmpty() }.toList()
}

/** Confere se cada número da resposta aparece nos dados (com tolerância de arredondamento). */
object Grounding {
    /** Números "livres": inteiros pequenos (minutos, dias, contagens) e anos não são checados. */
    private fun free(raw: String, v: Double) = (v == Math.rint(v) && abs(v) <= 60 && !raw.contains(',') && !raw.contains('.')) || v in 2020.0..2030.0

    fun ungrounded(answer: String, facts: List<String>): List<String> {
        val known = facts.flatMap { f -> JvText.numbers(f).flatMap { it.second } }
        return JvText.numbers(answer).filter { (raw, vals) ->
            vals.none { v -> free(raw, v) || known.any { k -> abs(abs(v) - abs(k)) <= max(0.011, abs(k) * 0.006) } }
        }.map { it.first }
    }
}

// =====================================================================================
// Memória local (conhecimento com fonte), salva em arquivo
// =====================================================================================

data class Knowledge(
    val id: String, val text: String, val source: String, val url: String, val timeMs: Long, val topic: String,
    val sources: Set<String>, val kind: String, val synced: Boolean = false,
) {
    val confirmations get() = sources.size
}

object JarvisMemory {
    private val file: File get() = File(JarvisNative.modelsDir, "memoria.json")
    private val items = ArrayList<Knowledge>()
    private var loaded = false
    var size by mutableIntStateOf(0); private set
    var confirmed by mutableIntStateOf(0); private set
    private const val KEEP_MS = 30L * 86_400_000L
    private const val MAX = 3000

    private fun load() { synchronized(items) {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.exists()) Net.parse(file.readText()).jsonArray.forEach { e ->
                val o = e.jsonObject
                items += Knowledge(o.s("id"), o.s("text"), o.s("source"), o.s("url"), o.l("time"), o.s("topic"),
                    (o["sources"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: setOf(o.s("source")), o.s("kind"),
                    (o["synced"] as? JsonPrimitive)?.booleanOrNull ?: false)
            }
        }
        refresh()
    } }

    private fun refresh() { size = items.size; confirmed = items.count { it.confirmations >= 2 || it.kind == "resumo" || it.kind == "conceito" } }

    fun all(): List<Knowledge> { load(); return synchronized(items) { items.toList() } }

    fun save() = synchronized(items) {
        val now = System.currentTimeMillis()
        items.removeAll { now - it.timeMs > (if (it.kind == "conceito") 365 * 86_400_000L else KEEP_MS) }
        while (items.size > MAX) items.removeAt(0)
        runCatching {
            file.writeText(buildJsonArray {
                items.forEach { k -> add(buildJsonObject {
                    put("id", k.id); put("text", k.text); put("source", k.source); put("url", k.url); put("time", k.timeMs)
                    put("topic", k.topic); put("kind", k.kind); put("synced", k.synced)
                    put("sources", buildJsonArray { k.sources.forEach { add(JsonPrimitive(it)) } })
                }) }
            }.toString())
        }
        refresh()
    }

    private fun sim(a: Set<String>, b: Set<String>) = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    /** Guarda; se já existe algo muito parecido de outra fonte, conta como confirmação. Devolve true se for novo. */
    fun add(k: Knowledge): Boolean {
        load()
        val w = JvText.words(k.text).toSet()
        synchronized(items) {
            val i = items.indexOfFirst { it.kind == k.kind && sim(JvText.words(it.text).toSet(), w) >= 0.55 }
            if (i >= 0) {
                val old = items[i]
                if (k.source !in old.sources) items[i] = old.copy(sources = old.sources + k.source, synced = false)
                return false
            }
            items += k
        }
        return true
    }

    /** Busca por palavras em comum, com peso para confirmações e para o que é recente. */
    fun search(q: String, n: Int = 5, now: Long = System.currentTimeMillis()): List<Knowledge> {
        val qw = JvText.words(q).toSet()
        if (qw.isEmpty()) return emptyList()
        return all().map { k ->
            val kw = JvText.words(k.text + " " + k.topic).toSet()
            val hit = qw.intersect(kw).size.toDouble()
            val days = (now - k.timeMs) / 86_400_000.0
            k to hit * (1 + 0.5 * (k.confirmations - 1).coerceAtMost(3)) * (if (k.kind == "resumo") 1.3 else 1.0) / (1 + days / (if (k.kind == "conceito") 90 else 7))
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(n).map { it.first }
    }

    fun asFacts(q: String): List<Fact> = search(q, 4).map { k ->
        val d = java.text.SimpleDateFormat("dd/MM", BR).format(java.util.Date(k.timeMs))
        if (k.kind == "conceito") return@map Fact("Conceito (Wikipedia): ${k.text}", k.url.ifBlank { k.source })
        Fact("Memória ($d, ${k.confirmations} ${if (k.confirmations == 1) "fonte" else "fontes"}): ${k.text}", k.source)
    }

    fun markSynced(ids: Set<String>) = synchronized(items) {
        for (i in items.indices) if (items[i].id in ids) items[i] = items[i].copy(synced = true)
    }

    fun unsynced(): List<Knowledge> = all().filter { !it.synced }

    fun clear() = synchronized(items) { items.clear(); runCatching { file.delete() }; refresh() }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
}

// =====================================================================================
// Placar: cada plano é conferido depois com o que aconteceu
// =====================================================================================

data class ScoreEntry(
    val id: String, val symbol: String, val market: String, val date: String, val entry: Double, val stop: Double, val target: Double,
    val slot: Int, val hypothetical: Boolean, val pTarget: Double?, val pStop: Double?, val createdMs: Long,
    val outcome: String? = null, val synced: Boolean = false,
)

object ScoreBook {
    private val file: File get() = File(JarvisNative.modelsDir, "placar.json")
    private val items = ArrayList<ScoreEntry>()
    private var loaded = false
    var version by mutableIntStateOf(0); private set

    private fun load() { synchronized(items) {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.exists()) Net.parse(file.readText()).jsonArray.forEach { e ->
                val o = e.jsonObject
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                items += ScoreEntry(s("id") ?: return@forEach, s("symbol") ?: "", s("market") ?: "", s("date") ?: "", d("entry") ?: 0.0,
                    d("stop") ?: 0.0, d("target") ?: 0.0, (o["slot"] as? JsonPrimitive)?.intOrNull ?: 0, s("hyp") == "true", d("pTarget"), d("pStop"),
                    (o["created"] as? JsonPrimitive)?.longOrNull ?: 0L, s("outcome"), s("synced") == "true")
            }
        }
    } }

    private fun save() = synchronized(items) {
        while (items.size > 2000) items.removeAt(0)
        runCatching {
            file.writeText(buildJsonArray {
                items.forEach { e -> add(buildJsonObject {
                    put("id", e.id); put("symbol", e.symbol); put("market", e.market); put("date", e.date); put("entry", e.entry)
                    put("stop", e.stop); put("target", e.target); put("slot", e.slot); put("hyp", e.hypothetical.toString())
                    e.pTarget?.let { put("pTarget", it) }; e.pStop?.let { put("pStop", it) }; put("created", e.createdMs)
                    e.outcome?.let { put("outcome", it) }; put("synced", e.synced.toString())
                }) }
            }.toString())
        }
        version++
    }

    fun all(): List<ScoreEntry> { load(); return synchronized(items) { items.toList() } }

    /** Guarda o plano do cartão (um por ativo por dia; o mais recente vale). */
    fun record(c: TradeCard) {
        val p = c.plan ?: return
        load()
        val id = "${p.symbol}|${p.date}"
        synchronized(items) {
            items.removeAll { it.id == id && it.outcome == null }
            if (items.none { it.id == id }) items += ScoreEntry(id, p.symbol, p.market.name, p.date.toString(), p.entry, p.stop, p.target, p.entrySlot,
                c.hypothetical, c.mc?.pTarget, c.mc?.pStop, System.currentTimeMillis())
        }
        save()
    }

    /** Confere os planos de dias já encerrados. Devolve quantos foram resolvidos. */
    suspend fun resolve(): Int = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val pending = all().filter { it.outcome == null && LocalDate.parse(it.date) < today }
        var n = 0
        for ((sym, list) in pending.groupBy { it.symbol }) {
            val m = runCatching { TradeMarket.valueOf(list.first().market) }.getOrNull() ?: continue
            val bars = runCatching { if (m == TradeMarket.BTC) TraderData.btc5m(minOf(30, (System.currentTimeMillis() - LocalDate.parse(list.minOf { it.date }).toEpochDay() * 86_400_000L).let { (it / 86_400_000L).toInt() + 2 })) else TraderData.yahoo5m(sym, "60d").first }.getOrNull() ?: continue
            val sessions = TraderSkill.sessions(bars, m).associateBy { it.date.toString() }
            for (e in list) {
                val s = sessions[e.date] ?: continue
                val out = TraderSkill.outcome(s, m, e.entry, e.stop, e.target, e.slot, e.hypothetical) ?: continue
                synchronized(items) { val i = items.indexOfFirst { it.id == e.id }; if (i >= 0) items[i] = items[i].copy(outcome = out, synced = false) }
                n++
            }
        }
        if (n > 0) save()
        n
    }

    data class Stats(val resolved: Int, val triggered: Int, val target: Int, val stop: Int, val close: Int, val brier: Double?, val predictedTarget: Double?)

    fun stats(symbol: String? = null): Stats {
        val r = all().filter { it.outcome != null && (symbol == null || it.symbol == symbol) }
        val t = r.filter { it.outcome != "no_trigger" }
        val withP = t.filter { it.pTarget != null }
        val brier = if (withP.isEmpty()) null else withP.sumOf { val y = if (it.outcome == "target") 1.0 else 0.0; (it.pTarget!! - y) * (it.pTarget - y) } / withP.size
        return Stats(r.size, t.size, t.count { it.outcome == "target" }, t.count { it.outcome == "stop" }, t.count { it.outcome == "close" }, brier,
            if (withP.isEmpty()) null else withP.sumOf { it.pTarget!! } / withP.size)
    }

    fun fact(symbol: String): Fact? {
        val s = stats(symbol)
        if (s.triggered == 0) return null
        return Fact("Placar do Jarvis em $symbol: ${s.triggered} planos conferidos, ${s.target} no alvo, ${s.stop} no stop, ${s.close} sem nenhum" +
            (s.predictedTarget?.let { p -> "; previsto ${num(p * 100, 0)}% de alvo, real ${num(s.target * 100.0 / s.triggered, 0)}%" } ?: ""), "Placar do app")
    }

    fun unsynced() = all().filter { it.outcome != null && !it.synced }
    fun markSynced(ids: Set<String>) { synchronized(items) { for (i in items.indices) if (items[i].id in ids) items[i] = items[i].copy(synced = true) }; save() }
}

// =====================================================================================
// Avaliações do usuário (👍/👎): viram exemplos de treino e pesquisa do dia seguinte
// =====================================================================================

object JarvisFeedback {
    private val file: File get() = File(JarvisNative.modelsDir, "avaliacoes.jsonl")
    var good by mutableIntStateOf(SettingsStore.i("jv.fb.good", 0)); private set
    var bad by mutableIntStateOf(SettingsStore.i("jv.fb.bad", 0)); private set

    fun record(question: String, answer: String, engine: String, ok: Boolean, facts: List<Fact>) {
        runCatching {
            file.appendText(buildJsonObject {
                put("time", System.currentTimeMillis()); put("ok", ok); put("engine", engine); put("q", question); put("a", answer)
                put("facts", buildJsonArray { facts.forEach { add(JsonPrimitive(it.text + " [" + it.source + "]")) } })
            }.toString() + "\n")
        }
        if (ok) { good++; SettingsStore.put("jv.fb.good", good) } else { bad++; SettingsStore.put("jv.fb.bad", bad); JarvisResearch.enqueue(question) }
        CloudBrain.queueFeedback(question, answer, engine, ok)
    }

    /** Exemplos aprovados no formato de conversa (para o ajuste fino semanal). Devolve o arquivo gerado. */
    fun exportTraining(): File? = runCatching {
        val out = File(JarvisNative.modelsDir, "treino-jarvis.jsonl")
        val lines = if (file.exists()) file.readLines() else emptyList()
        out.writeText(lines.mapNotNull { l ->
            val o = runCatching { Net.parse(l).jsonObject }.getOrNull() ?: return@mapNotNull null
            if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) return@mapNotNull null
            val facts = (o["facts"] as? JsonArray)?.joinToString("\n") { "- " + it.jsonPrimitive.content } ?: ""
            buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", "You are JARVIS inside Radar BTC. Use only these FACTS:\n$facts") })
                    add(buildJsonObject { put("role", "user"); put("content", o["q"]?.jsonPrimitive?.content ?: "") })
                    add(buildJsonObject { put("role", "assistant"); put("content", o["a"]?.jsonPrimitive?.content ?: "") })
                })
            }.toString()
        }.joinToString("\n"))
        out
    }.getOrNull()
}

// =====================================================================================
// Habilidades: receitas de dados (nunca código), propostas pelo uso ou vindas da nuvem
// =====================================================================================

data class Recipe(val id: String, val name: String, val keywords: List<String>, val queries: List<String>, val enabled: Boolean, val origin: String)

object JarvisSkills {
    var list by mutableStateOf(load()); private set

    private fun load(): List<Recipe> = runCatching {
        Net.parse(SettingsStore.s("jv.skills", "[]")).jsonArray.map { e ->
            val o = e.jsonObject
            fun arr(k: String) = (o[k] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
            Recipe(o["id"]!!.jsonPrimitive.content, o["name"]?.jsonPrimitive?.content ?: "", arr("keywords"), arr("queries"),
                (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false, o["origin"]?.jsonPrimitive?.content ?: "proposta")
        }
    }.getOrDefault(emptyList())

    private fun store(l: List<Recipe>) {
        list = l
        SettingsStore.put("jv.skills", buildJsonArray {
            l.forEach { r -> add(buildJsonObject {
                put("id", r.id); put("name", r.name); put("enabled", r.enabled); put("origin", r.origin)
                put("keywords", buildJsonArray { r.keywords.forEach { add(JsonPrimitive(it)) } })
                put("queries", buildJsonArray { r.queries.forEach { add(JsonPrimitive(it)) } })
            }) }
        }.toString())
    }

    fun setEnabled(id: String, on: Boolean) = store(list.map { if (it.id == id) it.copy(enabled = on) else it })
    fun remove(id: String) = store(list.filter { it.id != id })
    fun matching(q: String): List<Recipe> { val f = " " + JarvisText.fold(q) + " "; return list.filter { r -> r.enabled && r.keywords.any { " $it" in f } } }

    /** Uma palavra que aparece em 3 perguntas sem resposta vira proposta de habilidade (pesquisar notícias sobre ela). */
    fun noteUnknown(q: String) {
        val counts = SettingsStore.s("jv.unk", "").split('|').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }.toMap().toMutableMap()
        for (w in JvText.words(q).filter { it.length >= 4 && !it.all(Char::isDigit) }.distinct()) {
            val c = (counts[w] ?: 0) + 1
            counts[w] = c
            if (c >= 3 && list.none { w in it.keywords }) store(list + Recipe("u-$w", "Notícias sobre $w", listOf(w), listOf(w), false, "proposta"))
        }
        SettingsStore.put("jv.unk", counts.entries.sortedByDescending { it.value }.take(60).joinToString("|") { "${it.key}=${it.value}" })
    }

    /** Catálogo da nuvem: entra desligado até o usuário aprovar. Só aceita palavras e buscas (dados). */
    fun mergeCloud(rs: List<Recipe>) {
        val known = list.map { it.id }.toSet()
        val add = rs.filter { it.id !in known && it.queries.all { q -> q.length <= 120 } }.map { it.copy(enabled = false, origin = "nuvem") }
        if (add.isNotEmpty()) store(list + add)
    }

    /** Executa as receitas que casam com a pergunta: busca manchetes recentes (Google News, sem chave). */
    suspend fun run(q: String): List<Fact> = matching(q).take(2).flatMap { r ->
        r.queries.take(2).flatMap { query ->
            runCatching { MacroSources.parseRss(Net.getText(MacroSources.gnewsUrl(query)), "Google News", "Google News").take(4) }.getOrDefault(emptyList())
                .map { n -> Fact("Habilidade \"${r.name}\": ${n.title}", n.source.ifBlank { "Google News" }) }
        }
    }
}

// =====================================================================================
// Pesquisa diária: o aparelho pesquisa e guarda o que aprendeu (e manda para a nuvem)
// =====================================================================================

object JarvisResearch {
    var enabled by mutableStateOf(SettingsStore.b("jv.res.on", true)); private set
    var running by mutableStateOf(false); private set
    var lastMs by mutableStateOf(SettingsStore.s("jv.res.last", "0").toLongOrNull() ?: 0L); private set
    var lastAdded by mutableIntStateOf(0); private set

    fun useEnabled(v: Boolean) { enabled = v; SettingsStore.put("jv.res.on", v) }

    private fun queue(): List<String> = SettingsStore.s("jv.res.queue", "").split('\u0001').filter { it.isNotBlank() }
    fun enqueue(q: String) = SettingsStore.put("jv.res.queue", (queue() + q.take(160)).distinct().takeLast(20).joinToString("\u0001"))

    fun due(now: Long = System.currentTimeMillis()) = enabled && !running && now - lastMs > 20 * 3_600_000L && JarvisNative.unmetered()

    /** Temas do dia: perguntas sem resposta, BTC, a lista do scanner e as habilidades ligadas. */
    fun topics(): List<Pair<String, String>> {
        val q = queue().map { it to JvText.words(it).take(5).joinToString(" ") }.filter { it.second.isNotBlank() }
        val syms = JarvisSettings.symbols().take(6).map { "$it ação" to "$it stock" }
        val skills = JarvisSkills.list.filter { it.enabled }.flatMap { r -> r.queries.take(1).map { r.name to it } }
        return (listOf("Bitcoin" to "bitcoin price", "Fed e juros" to "Federal Reserve rates") + q + syms + skills).distinctBy { it.second }.take(16)
    }

    suspend fun run(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (running || (!force && !due())) return@withContext 0
        running = true
        var added = 0
        try {
            for ((topic, query) in topics()) {
                val news = runCatching { MacroSources.parseRss(Net.getText(MacroSources.gnewsUrl(query)), "Google News", "Google News") }.getOrDefault(emptyList())
                    .filter { System.currentTimeMillis() - it.timeMs < 2 * 86_400_000L }.take(8)
                for (n in news) if (JarvisMemory.add(Knowledge("n" + Integer.toHexString((n.title + n.source).hashCode()), n.title, n.source.ifBlank { "Google News" },
                        n.url, n.timeMs, topic, setOf(n.source.ifBlank { "Google News" }), "news"))) added++
                // Com a IA pronta: um resumo curto por tema, só com as manchetes (conferido número a número).
                if (news.size >= 2 && Jarvis.brain == BrainState.READY) {
                    val facts = news.map { it.title + " [" + it.source + "]" }
                    val sum = runCatching { Jarvis.summarize(topic, facts) }.getOrNull()
                    if (!sum.isNullOrBlank() && Grounding.ungrounded(sum, facts).isEmpty())
                        JarvisMemory.add(Knowledge("r" + Integer.toHexString((topic + LocalDate.now()).hashCode()), "$topic: $sum",
                            "Resumo do Jarvis de ${news.size} manchetes", "", System.currentTimeMillis(), topic, news.map { it.source }.toSet(), "resumo"))
                }
            }
            JarvisMemory.save()
            // Curiosidade: termos novos que apareceram nas manchetes viram conceitos (Wikipedia).
            runCatching { added += JarvisCuriosity.learn().size }
            ScoreBook.resolve()
            SettingsStore.put("jv.res.queue", "")
            lastMs = System.currentTimeMillis(); SettingsStore.put("jv.res.last", lastMs.toString())
            lastAdded = added
            CloudBrain.sync()
        } finally { running = false }
        added
    }
}

// =====================================================================================
// Cérebro na nuvem (Supabase): memória, placar, avaliações e catálogo de habilidades
// =====================================================================================

object CloudBrain {
    enum class State { OFF, CONNECTING, ON, ERROR }
    var state by mutableStateOf(State.OFF); private set
    var error by mutableStateOf<String?>(null); private set
    var lastSyncMs by mutableStateOf(SettingsStore.s("jv.cloud.last", "0").toLongOrNull() ?: 0L); private set
    private val pendingFeedback = ArrayList<JsonObject>()
    private val JSON = "application/json".toMediaType()

    /** Endereço e chave PÚBLICA (anon) do projeto: vêm do build (segredos do GitHub) ou das configurações; nunca do código. */
    private val buildConfig: Pair<String, String> by lazy {
        runCatching {
            val p = java.util.Properties()
            CloudBrain::class.java.getResourceAsStream("/jarvis-cloud.properties")?.use { p.load(it) }
            (p.getProperty("url") ?: "") to (p.getProperty("key") ?: "")
        }.getOrDefault("" to "")
    }
    val url: String get() = SettingsStore.s("jv.cloud.url", "").ifBlank { buildConfig.first }.trimEnd('/')
    private val key: String get() = SettingsStore.s("jv.cloud.key", "").ifBlank { buildConfig.second }
    val configured: Boolean get() = url.startsWith("https://") && key.length > 20
    val userId: String get() = SettingsStore.s("jv.cloud.uid", "")

    fun configure(u: String, k: String) { SettingsStore.put("jv.cloud.url", u.trim()); SettingsStore.put("jv.cloud.key", k.trim()); SettingsStore.put("jv.cloud.refresh", "") }

    private fun call(method: String, path: String, body: String?, token: String?, extra: Map<String, String> = emptyMap()): String {
        val rb = Request.Builder().url(url + path).header("apikey", key).header("Content-Type", "application/json")
        rb.header("Authorization", "Bearer " + (token ?: key))
        extra.forEach { (k, v) -> rb.header(k, v) }
        rb.method(method, body?.toRequestBody(JSON))
        Net.client.newCall(rb.build()).execute().use { r ->
            val t = r.body?.string() ?: ""
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}: ${t.take(160)}")
            return t
        }
    }

    @Volatile private var access: String? = null
    @Volatile private var accessUntil = 0L

    /** Login anônimo por aparelho (cada aparelho só lê e grava os próprios dados: RLS no banco). */
    private fun token(): String {
        if (access != null && System.currentTimeMillis() < accessUntil) return access!!
        val refresh = SettingsStore.s("jv.cloud.refresh", "")
        val res = if (refresh.isNotBlank()) runCatching { call("POST", "/auth/v1/token?grant_type=refresh_token", """{"refresh_token":"$refresh"}""", null) }.getOrNull() else null
        val txt = res ?: call("POST", "/auth/v1/signup", "{}", null)
        val o = Net.parse(txt).jsonObject
        access = o["access_token"]!!.jsonPrimitive.content
        accessUntil = System.currentTimeMillis() + ((o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L) * 900L
        o["refresh_token"]?.jsonPrimitive?.contentOrNull?.let { SettingsStore.put("jv.cloud.refresh", it) }
        o["user"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull?.let { SettingsStore.put("jv.cloud.uid", it) }
        return access!!
    }

    fun queueFeedback(q: String, a: String, engine: String, ok: Boolean) = synchronized(pendingFeedback) {
        pendingFeedback += buildJsonObject { put("question", q.take(500)); put("answer", a.take(2000)); put("engine", engine); put("ok", ok) }
    }

    /** Envia o que é novo e traz o catálogo de habilidades. */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        if (!configured) { state = State.OFF; return@withContext false }
        state = State.CONNECTING
        try {
            val t = token()
            val pref = mapOf("Prefer" to "return=minimal,resolution=merge-duplicates")
            val mem = JarvisMemory.unsynced().take(500)
            if (mem.isNotEmpty()) {
                call("POST", "/rest/v1/jv_knowledge?on_conflict=user_id,id", buildJsonArray {
                    mem.forEach { k -> add(buildJsonObject {
                        put("id", k.id); put("text", k.text.take(600)); put("source", k.source); put("url", k.url.take(500)); put("kind", k.kind)
                        put("topic", k.topic); put("confirmations", k.confirmations); put("seen_at", Instant.ofEpochMilli(k.timeMs).toString())
                    }) }
                }.toString(), t, pref)
                JarvisMemory.markSynced(mem.map { it.id }.toSet()); JarvisMemory.save()
            }
            val sc = ScoreBook.unsynced()
            if (sc.isNotEmpty()) {
                call("POST", "/rest/v1/jv_scores?on_conflict=user_id,id", buildJsonArray {
                    sc.forEach { e -> add(buildJsonObject {
                        put("id", e.id); put("symbol", e.symbol); put("market", e.market); put("day", e.date); put("entry", e.entry); put("stop", e.stop)
                        put("target", e.target); put("hypothetical", e.hypothetical); e.pTarget?.let { put("p_target", it) }; put("outcome", e.outcome ?: "")
                    }) }
                }.toString(), t, pref)
                ScoreBook.markSynced(sc.map { it.id }.toSet())
            }
            val fb = synchronized(pendingFeedback) { pendingFeedback.toList().also { pendingFeedback.clear() } }
            if (fb.isNotEmpty()) call("POST", "/rest/v1/jv_feedback", JsonArray(fb).toString(), t, mapOf("Prefer" to "return=minimal"))
            // Catálogo de habilidades aprovadas no servidor.
            val skills = Net.parse(call("GET", "/rest/v1/jv_skills?select=id,name,keywords,queries&active=eq.true", null, t)).jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                fun arr(k: String) = (o[k] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
                Recipe("c-" + (o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null), o["name"]?.jsonPrimitive?.content ?: "", arr("keywords").map { JarvisText.fold(it) }, arr("queries"), false, "nuvem")
            }
            JarvisSkills.mergeCloud(skills)
            // Aparelho novo (memória vazia): recupera o que este aparelho já tinha aprendido.
            if (JarvisMemory.size == 0) Net.parse(call("GET", "/rest/v1/jv_knowledge?select=id,text,source,url,kind,topic,seen_at&order=seen_at.desc&limit=500", null, t)).jsonArray.forEach { e ->
                val o = e.jsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""
                JarvisMemory.add(Knowledge(s("id"), s("text"), s("source"), s("url"), runCatching { Instant.parse(s("seen_at")).toEpochMilli() }.getOrDefault(0L), s("topic"), setOf(s("source")), s("kind"), true))
            }
            lastSyncMs = System.currentTimeMillis(); SettingsStore.put("jv.cloud.last", lastSyncMs.toString())
            state = State.ON; error = null
            true
        } catch (e: Exception) {
            state = State.ERROR; error = e.message?.take(200)
            false
        }
    }
}

// =====================================================================================
// Ferramentas que a IA pode pedir antes de responder
// =====================================================================================

object JarvisTools {
    data class Call(val name: String, val args: List<String>)

    val HELP = """dados <assunto>   (assuntos: preco, livro, paredes, fluxo, liquidacoes, derivativos, etf, macro, noticias, agenda, horario, status)
memoria <pergunta curta>
comparar <ATIVO> <ATIVO>   (ex.: comparar BTC QQQ)
noticias <ATIVO>
plano <ATIVO> [stop%] [alvoR]   (ex.: plano TSLA 1 3)"""

    private val NAMES = setOf("dados", "memoria", "comparar", "noticias", "plano")

    /** Lê as linhas "ferramenta argumentos" que a IA escreveu (no máximo 3; ignora o resto). */
    fun parse(text: String): List<Call> = text.lines().mapNotNull { l ->
        val t = JarvisText.fold(l).replace(Regex("^[-*•\\d.)\\s]+"), "").trim()
        val parts = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val n = parts.firstOrNull()?.trimEnd(':') ?: return@mapNotNull null
        if (n !in NAMES) null else Call(n, parts.drop(1).map { it.trim(',', '(', ')', '"') }.filter { it.isNotBlank() })
    }.take(3)

    private val TOPIC = mapOf(
        "preco" to Topic.PRICE, "livro" to Topic.BOOK, "paredes" to Topic.WALLS, "fluxo" to Topic.FLOW, "liquidacoes" to Topic.LIQ,
        "derivativos" to Topic.DERIV, "etf" to Topic.ETF, "macro" to Topic.MACRO, "noticias" to Topic.NEWS, "agenda" to Topic.CALENDAR,
        "horario" to Topic.HOURS, "status" to Topic.STATUS,
    )

    suspend fun run(ctx: AppContext, c: Call, question: String): Pair<List<Fact>, TradeCard?> = when (c.name) {
        "dados" -> JarvisFacts.collect(ctx, c.args.mapNotNull { TOPIC[it] }.toSet().ifEmpty { setOf(Topic.SUMMARY) }) to null
        "memoria" -> JarvisMemory.asFacts(c.args.joinToString(" ").ifBlank { question }) to null
        "comparar" -> compare(c.args.getOrNull(0)?.uppercase() ?: "BTC", c.args.getOrNull(1)?.uppercase() ?: "QQQ") to null
        "noticias" -> {
            val s = c.args.firstOrNull()?.uppercase() ?: "BTC"
            runCatching { TraderData.headlines(s, 5).map { Fact("Notícia de $s: ${it.title}", it.source) } }.getOrDefault(emptyList()) to null
        }
        "plano" -> {
            val s = c.args.firstOrNull()?.uppercase() ?: "BTC"
            val card = JarvisTrade.run(ctx, s, c.args.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull(), c.args.getOrNull(2)?.replace(',', '.')?.trimEnd('r', 'R')?.toDoubleOrNull())
            listOf(Fact(Jarvis.cardSummary(card), "Fluxo de trade do app")) to card
        }
        else -> emptyList<Fact>() to null
    }

    private fun yahoo(s: String) = if (s == "BTC") "BTC-USD" else if (s == "ETH") "ETH-USD" else s

    /** Variação em 5 e 20 dias e correlação dos retornos diários (60 dias). */
    suspend fun compare(a: String, b: String): List<Fact> {
        val da = runCatching { TraderData.yahooDaily(yahoo(a)) }.getOrDefault(emptyList())
        val db = runCatching { TraderData.yahooDaily(yahoo(b)) }.getOrDefault(emptyList())
        if (da.size < 22 || db.size < 22) return listOf(Fact("Não consegui os fechamentos diários de $a e $b agora.", "Yahoo Finance"))
        fun ch(d: List<TraderSkill.Daily>, n: Int) = (d.last().c / d[d.size - 1 - n].c - 1) * 100
        val corr = correlation(da, db)
        return listOf(
            Fact("$a: ${pct(ch(da, 5))} em 5 pregões, ${pct(ch(da, 20))} em 20", "Yahoo Finance"),
            Fact("$b: ${pct(ch(db, 5))} em 5 pregões, ${pct(ch(db, 20))} em 20", "Yahoo Finance"),
        ) + listOfNotNull(corr?.let { Fact("Correlação dos retornos diários $a × $b (até 60 dias): ${num(it, 2)}", "Cálculo do app com dados do Yahoo") })
    }

    fun correlation(a: List<TraderSkill.Daily>, b: List<TraderSkill.Daily>): Double? {
        fun rets(d: List<TraderSkill.Daily>) = d.zipWithNext().associate { (p, c) -> (c.t / 86_400_000L) to (c.c / p.c - 1) }
        val ra = rets(a); val rb = rets(b)
        val days = ra.keys.intersect(rb.keys).sorted().takeLast(60)
        if (days.size < 15) return null
        val x = days.map { ra[it]!! }; val y = days.map { rb[it]!! }
        val mx = x.average(); val my = y.average()
        val cov = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val vx = sqrt(x.sumOf { (it - mx) * (it - mx) }); val vy = sqrt(y.sumOf { (it - my) * (it - my) })
        return if (vx == 0.0 || vy == 0.0) null else cov / (vx * vy)
    }
}

// =====================================================================================
// Prova: mede se o Jarvis melhorou (roteamento, dados encontrados e números conferidos)
// =====================================================================================

object JarvisEval {
    data class Case(val q: String, val route: String, val topic: Topic? = null, val symbol: String? = null)

    val CASES = listOf(
        Case("Como está o BTC agora?", "facts", Topic.SUMMARY, "BTC"), Case("Qual o preço do bitcoin?", "facts", Topic.PRICE, "BTC"),
        Case("Quanto está o funding?", "facts", Topic.DERIV), Case("Onde estão as paredes de compra?", "facts", Topic.WALLS),
        Case("Como está o livro de ofertas?", "facts", Topic.BOOK), Case("O fluxo está comprador ou vendedor?", "facts", Topic.FLOW),
        Case("Liquidações da última hora", "facts", Topic.LIQ), Case("Onde estão as zonas ímã?", "facts", Topic.LIQ),
        Case("Como foram os ETFs ontem?", "facts", Topic.ETF), Case("O que o Fed fez com os juros?", "facts", Topic.MACRO),
        Case("Como está o dólar (DXY)?", "facts", Topic.MACRO), Case("Qual o índice de medo e ganância?", "facts", Topic.MACRO),
        Case("Quais as notícias de hoje?", "facts", Topic.NEWS), Case("O que tem na agenda econômica da semana?", "facts", Topic.CALENDAR),
        Case("A bolsa está aberta?", "facts", Topic.HOURS), Case("As corretoras estão conectadas?", "facts", Topic.STATUS),
        Case("Resumo macro de hoje", "facts", Topic.MACRO), Case("What is the bitcoin price?", "facts", Topic.PRICE, "BTC"),
        Case("How is the order flow?", "facts", Topic.FLOW), Case("Show me the liquidations", "facts", Topic.LIQ),
        Case("Plano de trade BTC", "trade", Topic.TRADE, "BTC"), Case("Time Machine TSLA", "trade", Topic.TRADE, "TSLA"),
        Case("Monta um plano para a Nvidia", "trade", Topic.TRADE, "NVDA"), Case("Quais as chances de bater o alvo na AAPL?", "trade", Topic.TRADE, "AAPL"),
        Case("Quantas ações da AMD eu compro?", "trade", Topic.TRADE, "AMD"), Case("Simula um trade em \$META", "trade", Topic.TRADE, "META"),
        Case("Backtest do rompimento no SPY", "trade", Topic.TRADE, "SPY"), Case("Plano de trade da Tesla com stop de 1%", "trade", Topic.TRADE, "TSLA"),
        Case("Scanner de ações", "scan", Topic.SCAN), Case("Quais ações estão rompendo?", "scan", Topic.SCAN),
        Case("Roda o scanner da minha watchlist", "scan", Topic.SCAN), Case("Ajuda", "help", Topic.HELP),
        Case("O que você faz?", "help", Topic.HELP), Case("Como está o open interest?", "facts", Topic.DERIV),
        Case("Tem iceberg no livro?", "facts", Topic.WALLS), Case("CVD está subindo?", "facts", Topic.FLOW),
        Case("Qual a correlação do BTC com o ouro?", "facts", Topic.MACRO), Case("Quando abre a bolsa?", "facts", Topic.HOURS),
        Case("Por que o bitcoin caiu?", "facts", Topic.NEWS, "BTC"), Case("Como está a inflação nos EUA?", "facts", Topic.MACRO),
    )

    /** Parte fixa da prova (sem rede): roteamento, assunto e ativo. */
    fun routing(): Pair<Int, Int> {
        var ok = 0
        for (c in CASES) {
            val r = Jarvis.route(c.q)
            if (r.kind == c.route && (c.topic == null || c.topic in r.topics || (c.route != "facts" && r.kind == c.route)) && (c.symbol == null || r.symbol == c.symbol)) ok++
        }
        return ok to CASES.size
    }

    data class Result(val timeMs: Long, val routing: Int, val answered: Int, val grounded: Int, val llmAnswers: Int, val total: Int, val avgMs: Long) {
        /** Nota 0–100: roteamento 40, dados encontrados 30, números conferidos 30. */
        val score: Int get() = ((routing * 40.0 + answered * 30.0) / total + (if (llmAnswers == 0) 30.0 else grounded * 30.0 / llmAnswers)).toInt()
    }

    var running by mutableStateOf(false); private set
    var last by mutableStateOf<Result?>(null); private set
    val history: List<Int> get() = SettingsStore.s("jv.eval.hist", "").split(',').mapNotNull { it.toIntOrNull() }

    /** Prova completa: roda as perguntas de dados de verdade (sem trade, que usa a internet), confere números e tempo. */
    suspend fun run(ctx: AppContext): Result {
        running = true
        try {
            val (rOk, total) = routing()
            var answered = 0; var grounded = 0; var llm = 0; var ms = 0L
            val cases = CASES.filter { it.route == "facts" }
            for (c in cases) {
                val t0 = System.currentTimeMillis()
                val a = Jarvis.answerFacts(ctx, c.q, Jarvis.route(c.q).topics)
                ms += System.currentTimeMillis() - t0
                if (a.facts.isNotEmpty()) answered++
                if (a.engine == "IA local") { llm++; if (Grounding.ungrounded(a.text, a.facts.map { it.text }).isEmpty()) grounded++ }
                else if (a.engine.startsWith("dados do app (a IA")) llm++
            }
            val res = Result(System.currentTimeMillis(), rOk, answered * total / cases.size.coerceAtLeast(1), grounded, llm, total, ms / cases.size.coerceAtLeast(1))
            last = res
            SettingsStore.put("jv.eval.hist", (history + res.score).takeLast(20).joinToString(","))
            return res
        } finally { running = false }
    }
}

// =====================================================================================
// Curiosidade: aprende coisas novas sozinho a partir do que aparece nas pesquisas
// =====================================================================================

/**
 * Lê as manchetes guardadas, acha nomes e termos que começaram a aparecer (em 3+ manchetes de 2+ fontes) e que o Jarvis
 * ainda não conhece, e pesquisa cada um na Wikipedia. O resumo vira conhecimento "conceito" com fonte e link, e o termo
 * entra na pesquisa de notícias do dia seguinte. Nada é inventado: sem página na Wikipedia, o termo é ignorado.
 */
object JarvisCuriosity {
    data class Concept(val term: String, val title: String, val text: String, val url: String, val lang: String)

    var running by mutableStateOf(false); private set
    var lastMs by mutableStateOf(SettingsStore.s("jv.cur.last", "0").toLongOrNull() ?: 0L); private set
    /** Últimos termos aprendidos (mais novo primeiro). */
    var recent by mutableStateOf(SettingsStore.s("jv.cur.recent", "").split('\u0001').filter { it.isNotBlank() }); private set

    private fun done(): Set<String> = SettingsStore.s("jv.cur.done", "").split('\u0001').filter { it.isNotBlank() }.toSet()
    private fun markDone(keys: Collection<String>) =
        SettingsStore.put("jv.cur.done", (done() + keys).toList().takeLast(300).joinToString("\u0001"))

    /** Palavras que começam com maiúscula em manchete mas não são nomes. */
    private val COMMON = setOf(
        "the", "a", "an", "and", "or", "of", "in", "on", "at", "to", "for", "with", "by", "from", "as", "is", "are", "was", "be", "its",
        "new", "how", "why", "what", "who", "when", "where", "will", "can", "may", "after", "before", "over", "under", "up", "down",
        "says", "said", "report", "reports", "week", "today", "news", "live", "update", "updates", "analysis", "opinion", "video",
        "price", "prices", "stock", "stocks", "shares", "market", "markets", "rate", "rates", "cut", "cuts", "hike", "high", "low",
        "record", "rally", "falls", "rises", "jumps", "drops", "surges", "slides", "gains", "losses", "trading", "trade", "deal",
        "first", "last", "next", "more", "most", "less", "top", "big", "this", "that", "these", "here", "now", "amid", "than", "into",
        "o", "a", "os", "as", "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas", "e", "ou", "um", "uma", "por", "para",
        "com", "sem", "sobre", "apos", "após", "ante", "mais", "menos", "novo", "nova", "hoje", "semana", "ano", "mes", "mês", "dia",
        "alta", "queda", "sobe", "cai", "dispara", "despenca", "recorde", "preco", "preço", "precos", "preços", "acoes", "ações",
        "mercado", "mercados", "juros", "taxa", "bolsa", "dolar", "dólar", "real", "veja", "entenda", "saiba", "diz", "afirma",
        "january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "q1", "q2", "q3", "q4",
    )
    /** O que o Jarvis já conhece pelos próprios dados (não precisa pesquisar). */
    private val BASE = setOf("bitcoin", "btc", "crypto", "cripto", "fed", "federal reserve", "etf", "etfs", "google news", "yahoo", "reuters",
        "bloomberg", "cnbc", "us", "eua", "u.s.", "wall street", "nasdaq", "s&p", "s&p 500", "dow", "dow jones", "nyse", "sec")

    private val CAP = Regex("^[\\p{Lu}][\\p{L}\\p{N}&'.\\-]*$")

    /** Tira o " - Fonte" do fim das manchetes do Google News. */
    fun cleanTitle(t: String): String = t.replace(Regex("\\s+[-–|]\\s+[^-–|]{2,40}$"), "").trim()

    /** Nomes de 1 a 3 palavras com inicial maiúscula (fora a primeira palavra da manchete). */
    fun phrases(title: String): Set<String> {
        val words = cleanTitle(title).split(Regex("[\\s,:;!?()\"“”‘’]+")).filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        var run = ArrayList<String>()
        fun flush() {
            if (run.isNotEmpty()) {
                for (len in 1..minOf(3, run.size)) for (i in 0..run.size - len) {
                    val p = run.subList(i, i + len).joinToString(" ").trimEnd('.', '\'').removeSuffix("'s").removeSuffix("’s")
                    val f = JarvisText.fold(p)
                    if (p.length >= 3 && f !in BASE && run.subList(i, i + len).none { JarvisText.fold(it.trimEnd('.')) in COMMON } && !p.any { it.isDigit() }) out += p
                }
            }
            run = ArrayList()
        }
        words.forEachIndexed { i, w -> if (i > 0 && CAP.matches(w) && w.length >= 2) run += w else flush() }
        flush()
        return out
    }

    /**
     * Termos novos: aparecem em pelo menos [minTitles] manchetes de [minSources] fontes diferentes e não estão em [known].
     * Entre um termo e um pedaço dele (ex.: "Jerome Powell" e "Powell"), fica o mais completo quando tem a mesma força.
     */
    fun candidates(news: List<Pair<String, String>>, known: Set<String>, max: Int = 3, minTitles: Int = 3, minSources: Int = 2): List<String> {
        val titles = HashMap<String, MutableSet<String>>(); val sources = HashMap<String, MutableSet<String>>(); val shown = HashMap<String, String>()
        for ((title, source) in news) for (p in phrases(title)) {
            val k = JarvisText.fold(p)
            titles.getOrPut(k) { HashSet() } += JarvisText.fold(cleanTitle(title)); sources.getOrPut(k) { HashSet() } += source
            shown.putIfAbsent(k, p)
        }
        val knownF = known.map { JarvisText.fold(it) }.toSet()
        val ok = titles.keys.filter { k -> titles[k]!!.size >= minTitles && sources[k]!!.size >= minSources && k !in knownF && knownF.none { it == k || it.contains(k) } }
        val kept = ok.filter { k -> ok.none { o -> o != k && o.contains(k) && titles[o]!!.size >= titles[k]!!.size } }
        return kept.sortedWith(compareByDescending<String> { titles[it]!!.size }.thenByDescending { it.length }).take(max).map { shown[it]!! }
    }

    private fun lang() = when (I18n.lang.code) { "pt", "es", "fr", "de", "it", "ja", "zh", "ko", "ru" -> I18n.lang.code; else -> "en" }

    fun searchUrl(lang: String, q: String) = "https://$lang.wikipedia.org/w/rest.php/v1/search/title?limit=3&q=" + java.net.URLEncoder.encode(q, "UTF-8")
    fun summaryUrl(lang: String, key: String) = "https://$lang.wikipedia.org/api/rest_v1/page/summary/" + java.net.URLEncoder.encode(key, "UTF-8").replace("+", "%20")

    /** Primeira página cujo título bate com o termo (sem página parecida, devolve null: nada de chute). */
    fun parseSearch(json: String, term: String): String? = runCatching {
        val t = JarvisText.fold(term)
        Net.parse(json).jsonObject["pages"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { p ->
            val title = JarvisText.fold((p["title"] as? JsonPrimitive)?.contentOrNull ?: "")
            title == t || title.startsWith("$t ") || title.startsWith("$t (") || (t.length >= 6 && title.contains(t))
        }?.let { (it["key"] as? JsonPrimitive)?.contentOrNull }
    }.getOrNull()

    /** Resumo da página: 2 primeiras frases, com link. Página de desambiguação não serve. */
    fun parseSummary(json: String, term: String, lang: String): Concept? {
        val o = runCatching { Net.parse(json).jsonObject }.getOrNull() ?: return null
        if ((o["type"] as? JsonPrimitive)?.contentOrNull == "disambiguation") return null
        val extract = (o["extract"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (extract.length < 40) return null
        val text = extract.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ").take(420)
        val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: term
        val url = ((o["content_urls"] as? JsonObject)?.get("desktop") as? JsonObject)?.get("page")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: ("https://$lang.wikipedia.org/wiki/" + title.replace(' ', '_'))
        return Concept(term, title, text, url, lang)
    }

    suspend fun lookup(term: String): Concept? {
        for (l in listOf(lang(), "en").distinct()) {
            val key = runCatching { parseSearch(Net.getText(searchUrl(l, term)), term) }.getOrNull() ?: continue
            val c = runCatching { parseSummary(Net.getText(summaryUrl(l, key)), term, l) }.getOrNull()
            if (c != null) return c
        }
        return null
    }

    /** Uma rodada: acha até [max] termos novos nas manchetes dos últimos 3 dias e aprende o que a Wikipedia diz. */
    suspend fun learn(max: Int = 3): List<String> = withContext(Dispatchers.IO) {
        if (running) return@withContext emptyList()
        running = true
        try {
            val now = System.currentTimeMillis()
            val all = JarvisMemory.all()
            val news = all.filter { it.kind == "news" && now - it.timeMs < 3 * 86_400_000L }.map { it.text to it.source }
            val known = done() + all.filter { it.kind == "conceito" }.map { it.topic } + JarvisSettings.symbols()
            val terms = candidates(news, known, max)
            val learned = ArrayList<String>()
            for (t in terms) {
                val c = lookup(t)
                if (c != null && JarvisMemory.add(Knowledge("c" + Integer.toHexString(JarvisText.fold(t).hashCode()), "${c.title}: ${c.text}",
                        "Wikipedia", c.url, now, t, setOf("Wikipedia"), "conceito"))) {
                    learned += c.title
                    JarvisResearch.enqueue(t) // amanhã a pesquisa de notícias inclui o termo novo
                }
            }
            markDone(terms) // pesquisado (achou ou não): não repete todo dia
            if (learned.isNotEmpty()) JarvisMemory.save()
            recent = (learned + recent).distinct().take(12); SettingsStore.put("jv.cur.recent", recent.joinToString("\u0001"))
            lastMs = now; SettingsStore.put("jv.cur.last", now.toString())
            runCatching { FileLog.info("jarvis curiosidade: ${terms.size} termos novos, aprendeu ${learned.joinToString(", ")}") }
            learned
        } finally { running = false }
    }
}
