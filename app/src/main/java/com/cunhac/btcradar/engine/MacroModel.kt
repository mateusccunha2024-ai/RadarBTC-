package com.cunhac.btcradar.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * RADAR MACRO-GEOPOLÍTICO BTC — modelo de dados e regras (sem rede; tudo aqui é testável offline).
 *
 * Princípios:
 *  - Nada é inventado: dado ausente fica null e aparece como "sem dado"; o score só usa fatores com dado válido.
 *  - Todo dado carrega a data a que se refere (e não só a hora da coleta), para marcar o que está desatualizado.
 *  - Toda regra de direção/peso está escrita aqui e documentada em docs/MACRO.md. Nada disso é previsão de preço.
 */

// ---------------------------------------------------------------- classificação

enum class MacroDir(val label: String) { FAVORABLE("Favorável ao BTC"), UNFAVORABLE("Desfavorável ao BTC"), NEUTRAL("Neutro"), UNDETERMINED("Indeterminado") }
enum class MacroIntensity(val label: String, val weight: Double) { LOW("Baixa", 0.25), MODERATE("Moderada", 0.5), HIGH("Alta", 0.8), CRITICAL("Crítica", 1.0) }
enum class MacroHorizon(val label: String) { IMMEDIATE("Imediato (minutos a horas)"), SHORT("Curto prazo (1 a 3 dias)"), MEDIUM("Médio prazo (1 a 4 semanas)"), LONG("Longo prazo (acima de 1 mês)") }
enum class Reliability(val label: String, val weight: Double) { OFFICIAL("Fonte oficial", 1.0), HIGH("Alta", 0.9), MEDIUM("Média", 0.6), LOW("Baixa", 0.3) }

enum class NewsCategory(val label: String) {
    CONFLICT("Conflitos e segurança"), TRADE("Comércio internacional"), ENERGY("Energia e commodities"),
    MONETARY("Federal Reserve"), FISCAL("Política fiscal e dívida"), CRYPTO_REG("Regulação cripto"), US_POLITICS("Política americana"),
}

/** Notícia já classificada. [chain] = cadeia causal: evento → indicador afetado → mercado americano → BTC. */
data class MacroNews(
    val id: String, val title: String, val source: String, val url: String, val timeMs: Long,
    val category: NewsCategory, val dir: MacroDir, val intensity: MacroIntensity, val horizon: MacroHorizon,
    val reliability: Reliability, val relevance: Double, val rationale: String, val chain: List<String>, val origin: String,
)

/** Regra de classificação de manchete. Só manchetes que casam com alguma regra entram (ligação econômica explícita). */
data class NewsRule(
    val category: NewsCategory, val any: List<String>, val dir: MacroDir, val intensity: MacroIntensity,
    val horizon: MacroHorizon, val rationale: String, val chain: List<String>, val none: List<String> = emptyList(),
)

object MacroRules {
    /**
     * Ordem importa: a primeira regra que casar vence (as mais específicas vêm antes).
     * Direções descrevem o efeito mais comum observado em ativos de risco, nunca uma certeza.
     */
    val NEWS: List<NewsRule> = listOf(
        // Regulação cripto (efeito mais direto)
        NewsRule(NewsCategory.CRYPTO_REG, listOf("bitcoin reserve", "strategic bitcoin", "etf approv", "approves bitcoin", "approves crypto", "stablecoin bill pass",
            "market structure bill pass", "clarity act pass", "genius act"), MacroDir.FAVORABLE, MacroIntensity.HIGH, MacroHorizon.MEDIUM,
            "Avanço regulatório ou institucional reduz risco jurídico e pode ampliar a demanda por BTC.",
            listOf("Decisão regulatória", "Acesso institucional / segurança jurídica", "Demanda por cripto", "Possível alta do BTC")),
        NewsRule(NewsCategory.CRYPTO_REG, listOf("crypto ban", "bans crypto", "bitcoin ban", "crackdown on crypto", "sec sues", "sec charges", "lawsuit against coinbase",
            "lawsuit against binance", "exchange hack", "hacked exchange", "stablecoin depeg", "depeg"), MacroDir.UNFAVORABLE, MacroIntensity.HIGH, MacroHorizon.SHORT,
            "Ação restritiva, processo ou incidente no setor aumenta o risco percebido e pode gerar vendas.",
            listOf("Ação contra o setor", "Risco regulatório / de contraparte", "Saída de capital de cripto", "Possível pressão no BTC")),
        NewsRule(NewsCategory.CRYPTO_REG, listOf("sec ", "secs ", "cftc", "crypto regulation", "crypto bill", "stablecoin", "crypto rule"), MacroDir.UNDETERMINED, MacroIntensity.MODERATE,
            MacroHorizon.MEDIUM, "Tema regulatório em andamento: o efeito depende do conteúdo final.",
            listOf("Debate regulatório", "Incerteza sobre regras", "Posicionamento do mercado", "Efeito no BTC depende do desfecho")),
        // Federal Reserve
        NewsRule(NewsCategory.MONETARY, listOf("rate cut", "cuts rates", "cut interest rates", "lowers rates", "dovish", "rate cuts"), MacroDir.FAVORABLE, MacroIntensity.HIGH,
            MacroHorizon.SHORT, "Juros menores (ou expectativa de cortes) reduzem o custo do dinheiro e tendem a favorecer ativos de risco.",
            listOf("Corte / sinal de corte de juros", "Treasury yields e dólar tendem a cair", "Apetite por risco nas bolsas", "Ambiente favorável ao BTC")),
        NewsRule(NewsCategory.MONETARY, listOf("rate hike", "raises rates", "hikes rates", "hawkish", "higher for longer", "no rate cut", "rules out cut"), MacroDir.UNFAVORABLE,
            MacroIntensity.HIGH, MacroHorizon.SHORT, "Juros mais altos por mais tempo fortalecem o dólar e encarecem o risco.",
            listOf("Sinal de juros altos", "Yields e dólar sobem", "Aversão a risco nas bolsas", "Pressão sobre o BTC")),
        NewsRule(NewsCategory.MONETARY, listOf("fomc", "federal reserve", "powell", "fed chair", "fed governor", "feds", "the fed "), MacroDir.UNDETERMINED, MacroIntensity.MODERATE,
            MacroHorizon.SHORT, "Comunicação do Fed muda expectativas de juros; a direção depende do tom.",
            listOf("Comunicação do Fed", "Expectativa de juros", "Yields / dólar", "BTC reage à mudança de expectativa")),
        // Comércio
        NewsRule(NewsCategory.TRADE, listOf("tariff pause", "pauses tariffs", "tariff truce", "trade deal", "trade agreement", "lifts tariffs", "removes tariffs", "cuts tariffs",
            "lifts sanctions", "eases sanctions"), MacroDir.FAVORABLE, MacroIntensity.MODERATE, MacroHorizon.SHORT,
            "Alívio comercial reduz risco de inflação e de desaceleração; costuma melhorar o apetite por risco.",
            listOf("Alívio comercial", "Menos pressão em preços e cadeias", "Bolsas tendem a subir", "Apoio ao BTC")),
        NewsRule(NewsCategory.TRADE, listOf("tariff", "tariffs", "trade war", "export control", "export ban", "sanction", "retaliat", "import duty", "duties on"),
            MacroDir.UNFAVORABLE, MacroIntensity.MODERATE, MacroHorizon.SHORT,
            "Tarifas e sanções elevam custos e incerteza: pressão inflacionária, dólar mais forte e aversão a risco.",
            listOf("Tarifa / sanção", "Inflação e incerteza sobem", "Dólar forte, bolsas pressionadas", "Pressão de curto prazo no BTC")),
        // Conflitos
        NewsRule(NewsCategory.CONFLICT, listOf("ceasefire", "peace deal", "peace talks", "truce", "de-escalat", "withdraws troops"), MacroDir.FAVORABLE, MacroIntensity.MODERATE,
            MacroHorizon.SHORT, "Redução de tensão diminui a busca por proteção (dólar/ouro) e alivia petróleo.",
            listOf("Redução de conflito", "Petróleo e VIX tendem a cair", "Volta do apetite por risco", "Apoio ao BTC")),
        NewsRule(NewsCategory.CONFLICT, listOf("nuclear", "invasion", "invades", "declares war", "war with", "missile strike", "airstrike", "air strike", "attack on", "drone attack",
            "military escalat", "escalation", "troops", "blockade", "strait of hormuz", "red sea"), MacroDir.UNFAVORABLE, MacroIntensity.HIGH, MacroHorizon.IMMEDIATE,
            "Escalada militar gera aversão a risco imediata (dólar, ouro e petróleo sobem). No longo prazo o efeito sobre o BTC é ambíguo.",
            listOf("Escalada militar", "Petróleo, ouro, dólar e VIX sobem", "Venda de ativos de risco", "Pressão imediata no BTC")),
        // Energia
        NewsRule(NewsCategory.ENERGY, listOf("oil prices fall", "oil falls", "oil drops", "opec+ raise output", "opec raises output", "output increase", "oil slump"),
            MacroDir.FAVORABLE, MacroIntensity.LOW, MacroHorizon.MEDIUM, "Petróleo mais barato alivia a inflação e abre espaço para juros menores.",
            listOf("Petróleo em queda", "Inflação esperada menor", "Juros/yields podem cair", "Leve apoio ao BTC")),
        NewsRule(NewsCategory.ENERGY, listOf("oil surge", "oil jumps", "oil soars", "oil spikes", "opec cut", "output cut", "supply disruption", "pipeline", "refinery attack",
            "gas prices surge", "energy crisis"), MacroDir.UNFAVORABLE, MacroIntensity.MODERATE, MacroHorizon.SHORT,
            "Choque de energia pressiona a inflação e pode adiar cortes de juros.",
            listOf("Petróleo / gás sobem", "Inflação esperada maior", "Yields e dólar sobem", "Pressão no BTC")),
        // Fiscal / dívida
        NewsRule(NewsCategory.FISCAL, listOf("government shutdown", "shutdown", "debt ceiling", "default risk", "downgrade", "credit rating"), MacroDir.UNDETERMINED, MacroIntensity.MODERATE,
            MacroHorizon.SHORT, "Impasse fiscal aumenta volatilidade; pode favorecer ativos fora do sistema, mas no curto prazo costuma haver aversão a risco.",
            listOf("Impasse fiscal", "Prêmio de risco nos Treasuries", "Volatilidade nas bolsas", "Efeito no BTC incerto")),
        NewsRule(NewsCategory.FISCAL, listOf("stimulus", "tax cut", "spending bill", "fiscal package", "treasury refunding", "bond issuance", "deficit"), MacroDir.UNDETERMINED,
            MacroIntensity.LOW, MacroHorizon.MEDIUM, "Mais gasto/dívida pode aumentar liquidez e também os yields; o saldo depende da reação dos juros.",
            listOf("Política fiscal", "Liquidez × oferta de títulos", "Yields", "Efeito no BTC depende dos juros")),
        // Dados econômicos (manchetes)
        NewsRule(NewsCategory.US_POLITICS, listOf("inflation hotter", "inflation accelerat", "prices rose more than expected", "cpi hotter", "inflation jumps"), MacroDir.UNFAVORABLE,
            MacroIntensity.HIGH, MacroHorizon.SHORT, "Inflação acima do esperado reduz a chance de cortes de juros.",
            listOf("Inflação acima do esperado", "Menos cortes de juros", "Yields e dólar sobem", "Pressão no BTC")),
        NewsRule(NewsCategory.US_POLITICS, listOf("inflation cools", "inflation slows", "inflation eases", "cpi cooler", "prices rose less than expected"), MacroDir.FAVORABLE,
            MacroIntensity.HIGH, MacroHorizon.SHORT, "Inflação desacelerando aumenta a chance de cortes de juros.",
            listOf("Inflação abaixo do esperado", "Mais cortes de juros precificados", "Yields e dólar caem", "Apoio ao BTC")),
        NewsRule(NewsCategory.US_POLITICS, listOf("recession", "layoffs surge", "jobless claims jump", "unemployment rises"), MacroDir.UNDETERMINED, MacroIntensity.MODERATE,
            MacroHorizon.MEDIUM, "Desaceleração aumenta chance de cortes de juros (positivo), mas também o risco de queda das bolsas (negativo).",
            listOf("Sinais de desaceleração", "Expectativa de cortes ↑ / lucros ↓", "Bolsas oscilam", "Efeito no BTC ambíguo")),
    )

    /** Domínios conhecidos: confiabilidade da fonte. Outros = média. */
    val RELIABLE = mapOf(
        "federalreserve.gov" to Reliability.OFFICIAL, "treasury.gov" to Reliability.OFFICIAL, "bls.gov" to Reliability.OFFICIAL, "bea.gov" to Reliability.OFFICIAL,
        "whitehouse.gov" to Reliability.OFFICIAL, "sec.gov" to Reliability.OFFICIAL,
        "reuters" to Reliability.HIGH, "associated press" to Reliability.HIGH, "ap news" to Reliability.HIGH, "apnews" to Reliability.HIGH, "bloomberg" to Reliability.HIGH,
        "financial times" to Reliability.HIGH, "ft.com" to Reliability.HIGH, "wsj" to Reliability.HIGH, "wall street journal" to Reliability.HIGH, "cnbc" to Reliability.HIGH,
        "bbc" to Reliability.HIGH, "the economist" to Reliability.HIGH, "new york times" to Reliability.HIGH, "nytimes" to Reliability.HIGH, "washington post" to Reliability.HIGH,
        "marketwatch" to Reliability.HIGH, "barron" to Reliability.HIGH, "axios" to Reliability.HIGH, "politico" to Reliability.HIGH, "al jazeera" to Reliability.HIGH,
        "coindesk" to Reliability.HIGH, "the block" to Reliability.HIGH,
    )

    fun reliability(source: String): Reliability {
        val s = source.lowercase()
        return RELIABLE.entries.firstOrNull { s.contains(it.key) }?.value ?: Reliability.MEDIUM
    }

    /** Classifica uma manchete; null = sem ligação econômica clara com o BTC (fica de fora). */
    /** Minúsculas, sem aspas/apóstrofos, pontuação vira espaço (hífen fica), espaços simples, com espaço nas pontas. */
    fun normText(x: String): String = " " + x.lowercase().replace(Regex("[\"“”‘’'`]"), "").replace(Regex("[^a-z0-9\\- ]"), " ")
        .replace(Regex("\\s+"), " ").trim() + " "

    /** Palavra-chave casa só no começo de palavra ("tariff" casa "tariffs", "sec " não casa "second"). */
    private fun has(t: String, k: String) = t.contains(" " + normText(k).trimStart().let { if (k.endsWith(" ")) it else it.trimEnd() })

    fun classify(title: String): NewsRule? {
        val t = normText(title)
        return NEWS.firstOrNull { r -> r.any.any { has(t, it) } && r.none.none { has(t, it) } }
    }

    /** Chave para deduplicar a mesma notícia publicada por vários veículos. */
    fun normTitle(t: String): String = t.lowercase()
        .replace(Regex("\\s+-\\s+[^-]{2,60}$"), "")          // " - Reuters" no fim (Google News)
        .replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.length > 2 }.sorted().joinToString(" ")

    fun similar(a: String, b: String): Boolean {
        val x = a.split(' ').toSet(); val y = b.split(' ').toSet()
        if (x.isEmpty() || y.isEmpty()) return false
        return x.intersect(y).size.toDouble() / x.union(y).size >= 0.6
    }

    /** Remove repetidas (mesma manchete em vários sites), velhas e sem relevância; mais recentes primeiro. */
    fun dedupe(items: List<MacroNews>, nowMs: Long, maxAgeMs: Long = 48 * 3_600_000L): List<MacroNews> {
        val out = ArrayList<MacroNews>()
        val keys = ArrayList<String>()
        for (n in items.sortedWith(compareByDescending<MacroNews> { it.reliability.weight }.thenByDescending { it.timeMs })) {
            if (n.timeMs > nowMs + 10 * 60_000L) continue           // data no futuro = inválida
            if (nowMs - n.timeMs > maxAgeMs) continue                // antiga: não vira "notícia de agora"
            val k = normTitle(n.title)
            if (keys.any { it == k || similar(it, k) }) continue
            keys += k; out += n
        }
        return out.sortedByDescending { it.timeMs }
    }
}

// ---------------------------------------------------------------- mercado e indicadores

/** Série de um ativo (Yahoo): fechamentos por barra (UTC ms) e cotação atual. */
data class MarketSeries(
    val key: String, val label: String, val last: Double?, val prevClose: Double?, val lastTimeMs: Long,
    val times: List<Long>, val closes: List<Double>, val fetchedMs: Long, val unit: String = "",
) {
    val dayPct: Double? get() = if (last != null && prevClose != null && prevClose != 0.0) (last - prevClose) / prevClose * 100 else null
    /** Variação em pontos (para juros: em pontos-base = ×100). */
    val dayChange: Double? get() = if (last != null && prevClose != null) last - prevClose else null

    /** Variação desde o fechamento de [days] pregões atrás (por data UTC das barras). */
    fun pctOverSessions(days: Int): Double? {
        val l = last ?: closes.lastOrNull() ?: return null
        val byDay = times.zip(closes).groupBy { it.first / 86_400_000L }.toSortedMap()
        val dayCloses = byDay.values.map { it.last().second }
        if (dayCloses.size <= days) return null
        val ref = dayCloses[dayCloses.size - 1 - days]
        return if (ref != 0.0) (l - ref) / ref * 100 else null
    }

    fun changeOverSessions(days: Int): Double? {
        val l = last ?: closes.lastOrNull() ?: return null
        val byDay = times.zip(closes).groupBy { it.first / 86_400_000L }.toSortedMap()
        val dayCloses = byDay.values.map { it.last().second }
        if (dayCloses.size <= days) return null
        return l - dayCloses[dayCloses.size - 1 - days]
    }

    /** Valor da barra mais próxima de [tMs] (para variações intradiárias). */
    fun valueAt(tMs: Long): Double? {
        var best: Double? = null
        for (i in times.indices) { if (times[i] <= tMs) best = closes[i] else break }
        return best
    }
}

/** Dado econômico mensal (ou semanal) com período de referência. */
data class EconSeries(val id: String, val label: String, val points: List<Pair<String, Double>>, val source: String, val fetchedMs: Long) {
    /** Período (AAAA-MM) mais recente. */
    val lastPeriod: String? get() = points.lastOrNull()?.first
    fun v(back: Int): Double? = points.getOrNull(points.size - 1 - back)?.second
    fun pctChange(back: Int): Double? { val a = v(0); val b = v(back); return if (a != null && b != null && b != 0.0) (a - b) / b * 100 else null }
    fun diff(back: Int): Double? { val a = v(0); val b = v(back); return if (a != null && b != null) a - b else null }
    /** Meses entre o último período e [nowMs]; dado mensal normal tem 1–2 meses de defasagem. */
    fun lagMonths(nowMs: Long): Int? {
        val p = lastPeriod ?: return null
        val y = p.take(4).toIntOrNull() ?: return null; val m = p.drop(5).take(2).toIntOrNull() ?: return null
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneOffset.UTC)
        return (now.year - y) * 12 + (now.monthValue - m)
    }
    fun isStale(nowMs: Long, maxLag: Int = 3): Boolean = (lagMonths(nowMs) ?: 99) > maxLag
}

/** Taxa de juros do Fed (NY Fed): efetiva e intervalo-alvo. */
data class FedRate(val date: String, val effr: Double, val targetLow: Double, val targetHigh: Double, val fetchedMs: Long)

/** Rendimentos oficiais do Tesouro (fechamento diário). */
data class TreasuryCurve(val rows: List<Triple<String, Double?, Double?>>, val fetchedMs: Long) { // data ISO, 2Y, 10Y (mais recente primeiro)
    val last2y get() = rows.firstOrNull { it.second != null }?.second
    val last10y get() = rows.firstOrNull { it.third != null }?.third
    val date get() = rows.firstOrNull()?.first
    fun change10y(days: Int): Double? { val a = rows.getOrNull(0)?.third; val b = rows.getOrNull(days)?.third; return if (a != null && b != null) a - b else null }
    fun change2y(days: Int): Double? { val a = rows.getOrNull(0)?.second; val b = rows.getOrNull(days)?.second; return if (a != null && b != null) a - b else null }
}

/** Saldo da conta do Tesouro no Fed (TGA), em US$ milhões, mais recente primeiro. */
data class TgaSeries(val rows: List<Pair<String, Double>>, val fetchedMs: Long) {
    val last get() = rows.firstOrNull()?.second
    fun change(back: Int): Double? { val a = rows.getOrNull(0)?.second; val b = rows.getOrNull(back)?.second; return if (a != null && b != null) a - b else null }
}

/** Evento do calendário econômico. [actual] só quando há fonte oficial para o resultado. */
data class CalEvent(
    val title: String, val country: String, val timeMs: Long, val impact: String, val forecast: String, val previous: String,
    val actual: String? = null, val actualSource: String? = null,
) {
    val key get() = "$country|$title|$timeMs"
    val high get() = impact.equals("High", true)
}

// ---------------------------------------------------------------- análise de dados divulgados

/** Como um indicador afeta o BTC quando vem ACIMA do esperado (o contrário vale abaixo). */
data class EventProfile(val match: List<String>, val aboveDir: MacroDir, val chain: List<String>, val note: String, val belowChain: List<String> = emptyList())

object MacroEvents {
    val PROFILES = listOf(
        EventProfile(listOf("cpi", "pce", "ppi", "price index", "inflation"), MacroDir.UNFAVORABLE,
            listOf("Inflação acima do esperado", "Menos cortes de juros / yields sobem", "Dólar sobe, bolsas caem", "Pressão vendedora no BTC"),
            "O impacto depende do que já estava precificado e da reação dos Treasury yields.",
            listOf("Inflação abaixo do esperado", "Mais cortes de juros / yields caem", "Dólar cai, bolsas sobem", "Alívio para o BTC")),
        EventProfile(listOf("average hourly earnings", "wage"), MacroDir.UNFAVORABLE,
            listOf("Salários acima do esperado", "Pressão inflacionária", "Yields sobem", "Pressão no BTC"), "Salários fortes mantêm a inflação de serviços.",
            listOf("Salários abaixo do esperado", "Menos pressão inflacionária", "Yields caem", "Alívio para o BTC")),
        EventProfile(listOf("non-farm", "nonfarm", "employment change", "payroll"), MacroDir.UNFAVORABLE,
            listOf("Empregos acima do esperado", "Economia aquecida: menos cortes", "Yields e dólar sobem", "Pressão de curto prazo no BTC"),
            "Um número muito fraco também pode assustar (risco de recessão).",
            listOf("Empregos abaixo do esperado", "Esfriamento: mais cortes", "Yields e dólar caem", "Alívio para o BTC (se não virar medo de recessão)")),
        EventProfile(listOf("unemployment rate", "unemployment claims", "jobless claims"), MacroDir.FAVORABLE,
            listOf("Desemprego/pedidos acima do esperado", "Trabalho esfriando: mais cortes", "Yields caem", "Apoio ao BTC (se não virar medo de recessão)"),
            "Alta forte do desemprego pode virar aversão a risco.",
            listOf("Desemprego/pedidos abaixo do esperado", "Trabalho firme: menos cortes", "Yields sobem", "Pressão no BTC")),
        EventProfile(listOf("federal funds rate", "fomc", "interest rate decision"), MacroDir.UNFAVORABLE,
            listOf("Juros acima do esperado", "Política mais restritiva", "Dólar sobe, bolsas caem", "Pressão no BTC"), "O comunicado e a coletiva costumam pesar mais que a decisão.",
            listOf("Juros abaixo do esperado", "Política mais frouxa", "Dólar cai, bolsas sobem", "Apoio ao BTC")),
        EventProfile(listOf("gdp", "ism", "pmi", "retail sales"), MacroDir.UNDETERMINED,
            listOf("Atividade acima do esperado", "Lucros melhores, mas juros podem subir", "Bolsas e yields sobem juntos", "Efeito no BTC depende da reação dos juros"),
            "Crescimento forte é bom para risco, mas pode adiar cortes de juros.",
            listOf("Atividade abaixo do esperado", "Mais cortes, mas lucros piores", "Yields caem, bolsas oscilam", "Efeito no BTC depende da reação dos juros")),
    )

    fun profile(title: String): EventProfile? { val t = title.lowercase(); return PROFILES.firstOrNull { p -> p.match.any { t.contains(it) } } }

    /** Converte "0.3%", "245K", "4.1%", "-1.2M" em número (mesma unidade da expectativa). */
    fun parseNum(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().replace(",", "").replace("<", "").replace(">", "")
        val m = Regex("^([-+]?\\d*\\.?\\d+)\\s*([KMB%]?)").find(t) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) { "K" -> v * 1e3; "M" -> v * 1e6; "B" -> v * 1e9; else -> v }
    }

    /** Resultado vs expectativa: "Acima/Abaixo/Em linha do esperado" e o efeito provável no BTC. */
    data class Surprise(val label: String, val dir: MacroDir, val diff: Double, val chain: List<String>, val note: String)

    fun surprise(e: CalEvent): Surprise? {
        val a = parseNum(e.actual) ?: return null
        val f = parseNum(e.forecast) ?: return null
        val p = profile(e.title) ?: return null
        val tol = if (e.forecast.contains("%")) 0.05 else abs(f) * 0.02 + 1e-9
        val d = a - f
        if (abs(d) <= tol) return Surprise("Em linha com o esperado", MacroDir.NEUTRAL, d, p.chain, "Sem surpresa: o impacto tende a ser pequeno. ${p.note}")
        val above = d > 0
        val dir = when (p.aboveDir) {
            MacroDir.FAVORABLE -> if (above) MacroDir.FAVORABLE else MacroDir.UNFAVORABLE
            MacroDir.UNFAVORABLE -> if (above) MacroDir.UNFAVORABLE else MacroDir.FAVORABLE
            else -> MacroDir.UNDETERMINED
        }
        val chain = if (above || p.belowChain.isEmpty()) p.chain else p.belowChain
        return Surprise(if (above) "Acima do esperado" else "Abaixo do esperado", dir, d, chain, p.note)
    }
}

// ---------------------------------------------------------------- score

/**
 * Fatores do BTC MACRO SENTIMENT e pesos padrão (somam 100; configuráveis no app).
 * Pesos refletem a sensibilidade frequentemente observada do BTC a juros reais, dólar, liquidez e apetite por risco
 * (BTC negociado como ativo de risco de alta beta). São uma escolha documentada, não um modelo estimado.
 */
enum class MacroFactor(val label: String, val defaultWeight: Int, val rule: String) {
    MONETARY("Política monetária", 15, "Treasury 2 anos abaixo da taxa efetiva do Fed = mercado precificando cortes (favorável). Escala: ±0,75 pp = ±1."),
    INFLATION("Inflação", 12, "Núcleo do CPI (3 meses anualizado) contra a variação em 12 meses: desacelerando = favorável; acelerando = desfavorável. Escala: ±1 pp = ±1."),
    LABOR("Mercado de trabalho", 8, "Desemprego subindo 0,3–0,5 pp em 6 meses = esfriamento (mais cortes, +0,3); acima de 0,5 pp = risco de recessão (−0,5); salários > 4% a/a = −0,3."),
    DOLLAR("Dólar (DXY)", 12, "Variação em 5 pregões: dólar caindo = favorável. Escala: ±1,5% = ±1."),
    YIELDS("Treasury yields", 12, "Variação do Treasury 10 anos em 5 dias: yields caindo = favorável. Escala: ±20 bp = ±1."),
    LIQUIDITY("Liquidez", 8, "Saldo do TGA (conta do Tesouro no Fed) em ~4 semanas: TGA caindo devolve liquidez ao sistema (favorável). Escala: ±US$ 150 bi = ±1."),
    EQUITIES("Mercado acionário", 10, "Nasdaq 100 em 5 pregões: alta = apetite por risco (favorável). Escala: ±3% = ±1."),
    VOLATILITY("Volatilidade (VIX)", 8, "VIX < 15: +0,5 · 15–20: 0 · 20–30: −0,5 · > 30: −1; salto > 15% no dia soma −0,3."),
    GEOPOLITICS("Eventos geopolíticos", 7, "Soma das notícias classificadas nas últimas 24 h (direção × intensidade × confiabilidade), limitada a ±1."),
    INSTITUTIONAL("Fluxo institucional (ETFs)", 8, "Soma dos fluxos dos ETFs spot nos últimos 5 pregões: ±US$ 1 bi = ±1."),
}

data class FactorScore(val factor: MacroFactor, val value: Double?, val weight: Int, val reason: String, val asOf: String?, val stale: Boolean = false) {
    /** Contribuição em pontos do score (−100..+100), já ponderada pelo peso relativo. */
    var points: Double = 0.0
}

data class MacroScore(val score: Int?, val factors: List<FactorScore>, val confidence: Int, val missing: List<String>, val computedMs: Long) {
    val label: String get() = when {
        score == null -> "Sem dados suficientes"
        score <= -60 -> "Extremamente desfavorável"
        score <= -20 -> "Desfavorável"
        score < 20 -> "Neutro / misto"
        score < 60 -> "Favorável"
        else -> "Extremamente favorável"
    }
    val state: MacroDir get() = when { score == null -> MacroDir.UNDETERMINED; score >= 20 -> MacroDir.FAVORABLE; score <= -20 -> MacroDir.UNFAVORABLE; else -> MacroDir.NEUTRAL }
}

/** Tudo o que o score usa. Campos null = sem dado (o fator sai do cálculo e é listado como ausente). */
data class MacroInputs(
    val fed: FedRate? = null, val curve: TreasuryCurve? = null, val tnx: MarketSeries? = null,
    val coreCpi: EconSeries? = null, val unemployment: EconSeries? = null, val wages: EconSeries? = null,
    val dxy: MarketSeries? = null, val ndx: MarketSeries? = null, val vix: MarketSeries? = null,
    val tga: TgaSeries? = null, val news: List<MacroNews> = emptyList(), val etfNet5d: Double? = null, val etfAsOf: String? = null,
)

object MacroScoring {
    private fun clamp(v: Double) = max(-1.0, min(1.0, v))

    fun compute(inp: MacroInputs, weights: Map<MacroFactor, Int>, nowMs: Long): MacroScore {
        val f = ArrayList<FactorScore>()
        fun w(x: MacroFactor) = weights[x] ?: x.defaultWeight

        // 1. Política monetária
        val two = inp.curve?.last2y
        f += if (inp.fed != null && two != null) {
            val gap = inp.fed!!.effr - two
            FactorScore(MacroFactor.MONETARY, clamp(gap / 0.75), w(MacroFactor.MONETARY),
                String.format(PT_BR, "Fed efetivo %.2f%% × Treasury 2 anos %.2f%% → %s", inp.fed!!.effr, two,
                    if (gap > 0.1) "mercado espera cortes" else if (gap < -0.1) "mercado espera altas" else "sem mudança precificada"), inp.fed!!.date)
        } else FactorScore(MacroFactor.MONETARY, null, w(MacroFactor.MONETARY), "Sem taxa do Fed ou Treasury 2 anos", null)

        // 2. Inflação (núcleo)
        val c = inp.coreCpi
        f += if (c != null && c.points.size >= 13 && !c.isStale(nowMs)) {
            val yoy = c.pctChange(12)!!; val m3 = c.pctChange(3)!!; val ann3 = (Math.pow(1 + m3 / 100, 4.0) - 1) * 100
            FactorScore(MacroFactor.INFLATION, clamp((yoy - ann3) / 1.0), w(MacroFactor.INFLATION),
                String.format(PT_BR, "Núcleo do CPI: %.1f%% em 12 meses, %.1f%% anualizado em 3 meses (%s)", yoy, ann3, if (ann3 < yoy) "desacelerando" else "acelerando"), c.lastPeriod)
        } else FactorScore(MacroFactor.INFLATION, null, w(MacroFactor.INFLATION), if (c?.isStale(nowMs) == true) "Núcleo do CPI desatualizado (${c.lastPeriod})" else "Sem núcleo do CPI", c?.lastPeriod, c?.isStale(nowMs) == true)

        // 3. Trabalho
        val u = inp.unemployment
        f += if (u != null && u.points.size >= 7 && !u.isStale(nowMs)) {
            val du = u.diff(6)!!
            val wageYoy = inp.wages?.takeIf { it.points.size >= 13 && !it.isStale(nowMs) }?.pctChange(12)
            var v = when { du > 0.5 -> -0.5; du >= 0.3 -> 0.3; du <= -0.3 -> -0.2; else -> 0.0 }
            if (wageYoy != null && wageYoy > 4.0) v -= 0.3
            FactorScore(MacroFactor.LABOR, clamp(v), w(MacroFactor.LABOR),
                String.format(PT_BR, "Desemprego %.1f%% (%+.1f pp em 6 meses)%s", u.v(0), du, wageYoy?.let { String.format(PT_BR, " · salários %+.1f%% a/a", it) } ?: ""), u.lastPeriod)
        } else FactorScore(MacroFactor.LABOR, null, w(MacroFactor.LABOR), if (u?.isStale(nowMs) == true) "Desemprego desatualizado (${u.lastPeriod})" else "Sem dado de desemprego", u?.lastPeriod, u?.isStale(nowMs) == true)

        // 4. Dólar
        val d5 = inp.dxy?.pctOverSessions(5)
        f += if (d5 != null) FactorScore(MacroFactor.DOLLAR, clamp(-d5 / 1.5), w(MacroFactor.DOLLAR), String.format(PT_BR, "DXY %+.2f%% em 5 pregões", d5), isoDay(inp.dxy!!.lastTimeMs))
        else FactorScore(MacroFactor.DOLLAR, null, w(MacroFactor.DOLLAR), "Sem DXY", null)

        // 5. Yields (oficial; se faltar, Yahoo ^TNX)
        val y5 = inp.curve?.change10y(5) ?: inp.tnx?.changeOverSessions(5)
        f += if (y5 != null) FactorScore(MacroFactor.YIELDS, clamp(-y5 * 100 / 20), w(MacroFactor.YIELDS), String.format(PT_BR, "Treasury 10 anos %+.0f bp em 5 dias", y5 * 100), inp.curve?.date ?: isoDay(inp.tnx?.lastTimeMs ?: 0L))
        else FactorScore(MacroFactor.YIELDS, null, w(MacroFactor.YIELDS), "Sem Treasury 10 anos", null)

        // 6. Liquidez
        val dt = inp.tga?.change(20)
        f += if (dt != null) FactorScore(MacroFactor.LIQUIDITY, clamp(-dt / 150_000.0), w(MacroFactor.LIQUIDITY),
            String.format(PT_BR, "TGA US$ %,.0f bi (%+,.0f bi em ~4 semanas)", inp.tga!!.last!! / 1000, dt / 1000), inp.tga!!.rows.firstOrNull()?.first)
        else FactorScore(MacroFactor.LIQUIDITY, null, w(MacroFactor.LIQUIDITY), "Sem saldo do TGA", null)

        // 7. Ações
        val n5 = inp.ndx?.pctOverSessions(5)
        f += if (n5 != null) FactorScore(MacroFactor.EQUITIES, clamp(n5 / 3.0), w(MacroFactor.EQUITIES), String.format(PT_BR, "Nasdaq 100 %+.2f%% em 5 pregões", n5), isoDay(inp.ndx!!.lastTimeMs))
        else FactorScore(MacroFactor.EQUITIES, null, w(MacroFactor.EQUITIES), "Sem Nasdaq", null)

        // 8. VIX
        val vx = inp.vix?.last
        f += if (vx != null) {
            var v = when { vx < 15 -> 0.5; vx < 20 -> 0.0; vx < 30 -> -0.5; else -> -1.0 }
            val jump = inp.vix!!.dayPct
            if (jump != null && jump > 15) v -= 0.3
            FactorScore(MacroFactor.VOLATILITY, clamp(v), w(MacroFactor.VOLATILITY), String.format(PT_BR, "VIX %.1f%s", vx, jump?.let { String.format(PT_BR, " (%+.1f%% no dia)", it) } ?: ""), isoDay(inp.vix!!.lastTimeMs))
        } else FactorScore(MacroFactor.VOLATILITY, null, w(MacroFactor.VOLATILITY), "Sem VIX", null)

        // 9. Geopolítica (últimas 24 h)
        val recent = inp.news.filter { nowMs - it.timeMs <= 24 * 3_600_000L }
        val directional = recent.filter { it.dir == MacroDir.FAVORABLE || it.dir == MacroDir.UNFAVORABLE }
        f += if (recent.isNotEmpty()) {
            val sum = directional.sumOf { (if (it.dir == MacroDir.FAVORABLE) 1.0 else -1.0) * it.intensity.weight * it.reliability.weight * 0.35 }
            val fav = directional.count { it.dir == MacroDir.FAVORABLE }; val unf = directional.count { it.dir == MacroDir.UNFAVORABLE }
            FactorScore(MacroFactor.GEOPOLITICS, clamp(sum), w(MacroFactor.GEOPOLITICS), "$unf notícias desfavoráveis · $fav favoráveis · ${recent.size - directional.size} indeterminadas (24 h)", null)
        } else FactorScore(MacroFactor.GEOPOLITICS, null, w(MacroFactor.GEOPOLITICS), "Sem notícias classificadas nas últimas 24 h", null)

        // 10. ETFs
        f += if (inp.etfNet5d != null) FactorScore(MacroFactor.INSTITUTIONAL, clamp(inp.etfNet5d / 1000.0), w(MacroFactor.INSTITUTIONAL),
            String.format(PT_BR, "ETFs spot: US$ %+,.0f mi em 5 pregões", inp.etfNet5d), inp.etfAsOf)
        else FactorScore(MacroFactor.INSTITUTIONAL, null, w(MacroFactor.INSTITUTIONAL), "Sem fluxo dos ETFs (abra a aba ETFs para carregar)", null)

        val used = f.filter { it.value != null && it.weight > 0 }
        val totalW = f.sumOf { it.weight }.toDouble()
        val usedW = used.sumOf { it.weight }.toDouble()
        used.forEach { it.points = it.value!! * it.weight / usedW * 100 }
        val score = if (usedW <= 0 || usedW / max(1.0, totalW) < 0.35) null else used.sumOf { it.points }.let { Math.round(it).toInt().coerceIn(-100, 100) }
        val confidence = if (totalW <= 0) 0 else (usedW / totalW * 100).toInt()
        val missing = f.filter { it.value == null && it.weight > 0 }.map { it.factor.label + if (it.stale) " (desatualizado)" else "" }
        return MacroScore(score, f, confidence, missing, nowMs)
    }

    fun isoDay(ms: Long): String? = if (ms <= 0) null else java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
}

// ---------------------------------------------------------------- correlações

data class Corr(val asset: String, val r24h: Double?, val n24h: Int, val r7d: Double?, val n7d: Int, val r30d: Double?, val n30d: Int)

object MacroCorr {
    /** Retornos logarítmicos alinhados por horário (só horários em que os dois ativos negociaram). */
    fun aligned(a: MarketSeries, b: MarketSeries, fromMs: Long, bucketMs: Long): Pair<List<Double>, List<Double>> {
        fun buckets(s: MarketSeries) = s.times.zip(s.closes).filter { it.first >= fromMs }.associate { (it.first / bucketMs) to it.second }
        val ba = buckets(a); val bb = buckets(b)
        val keys = ba.keys.intersect(bb.keys).sorted()
        val ra = ArrayList<Double>(); val rb = ArrayList<Double>()
        for (i in 1 until keys.size) {
            val a0 = ba[keys[i - 1]]!!; val a1 = ba[keys[i]]!!; val b0 = bb[keys[i - 1]]!!; val b1 = bb[keys[i]]!!
            if (a0 > 0 && a1 > 0 && b0 > 0 && b1 > 0) { ra += ln(a1 / a0); rb += ln(b1 / b0) }
        }
        return ra to rb
    }

    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size < 8 || x.size != y.size) return null
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in x.indices) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); syy += (y[i] - my) * (y[i] - my) }
        if (sxx < 1e-14 || syy < 1e-14) return null
        return sxy / sqrt(sxx * syy)
    }

    /** 24 h e 7 d com barras de 1 h; 30 d com fechamentos diários. Mínimos de pontos evitam número sem significado. */
    fun all(btc: MarketSeries, others: List<MarketSeries>, nowMs: Long): List<Corr> = others.map { o ->
        val (a24, b24) = aligned(btc, o, nowMs - 26 * 3_600_000L, 3_600_000L)
        val (a7, b7) = aligned(btc, o, nowMs - 7 * 86_400_000L, 3_600_000L)
        val (a30, b30) = aligned(btc, o, nowMs - 31 * 86_400_000L, 86_400_000L)
        Corr(o.label, if (a24.size >= 8) pearson(a24, b24) else null, a24.size, if (a7.size >= 20) pearson(a7, b7) else null, a7.size,
            if (a30.size >= 12) pearson(a30, b30) else null, a30.size)
    }
}

// ---------------------------------------------------------------- integração com o order flow

enum class FlowState(val label: String) { BUYER("Comprador"), SELLER("Vendedor"), BALANCED("Equilibrado") }
enum class LiqState(val label: String) { LONG("LONG liquidados"), SHORT("SHORT liquidados"), NONE("Sem evento relevante") }
enum class Confluence(val label: String) { HIGH("Alta"), MEDIUM("Média"), LOW("Baixa") }

/** Resumo do order flow do próprio app (não vem de fora). */
data class FlowSnapshot(
    val deltaBtc15m: Double?, val volumeBtc15m: Double?, val longLiqBtc1h: Double, val shortLiqBtc1h: Double,
    val bidAbsorptions: Int, val askAbsorptions: Int, val priceChange1hPct: Double?,
)

data class IntegratedView(val macro: MacroDir, val flow: FlowState, val liq: LiqState, val confluence: Confluence, val reading: String)

object MacroConfluence {
    fun flowState(f: FlowSnapshot): FlowState {
        val d = f.deltaBtc15m ?: return FlowState.BALANCED
        val v = f.volumeBtc15m ?: return FlowState.BALANCED
        if (v <= 0) return FlowState.BALANCED
        return when { d / v > 0.10 -> FlowState.BUYER; d / v < -0.10 -> FlowState.SELLER; else -> FlowState.BALANCED }
    }

    fun liqState(f: FlowSnapshot, minBtc: Double = 5.0): LiqState {
        val l = f.longLiqBtc1h; val s = f.shortLiqBtc1h
        if (l + s < minBtc) return LiqState.NONE
        return if (l >= s * 1.5) LiqState.LONG else if (s >= l * 1.5) LiqState.SHORT else LiqState.NONE
    }

    fun evaluate(macro: MacroDir, f: FlowSnapshot): IntegratedView {
        val flow = flowState(f); val liq = liqState(f)
        val macroSign = when (macro) { MacroDir.FAVORABLE -> 1; MacroDir.UNFAVORABLE -> -1; else -> 0 }
        val flowSign = when (flow) { FlowState.BUYER -> 1; FlowState.SELLER -> -1; else -> 0 }
        val liqSign = when (liq) { LiqState.SHORT -> 1; LiqState.LONG -> -1; else -> 0 }
        val heldUp = (f.priceChange1hPct ?: 0.0) > -0.3
        val heldDown = (f.priceChange1hPct ?: 0.0) < 0.3
        val conf = when {
            macroSign != 0 && macroSign == flowSign && (liqSign == 0 || liqSign == macroSign) -> Confluence.HIGH
            macroSign != 0 && flowSign != 0 && macroSign != flowSign -> Confluence.LOW
            else -> Confluence.MEDIUM
        }
        val reading = when {
            macroSign < 0 && flowSign < 0 && liqSign < 0 ->
                "Cenário A: macro desfavorável + vendas agressoras + liquidações de LONG. Possível queda reforçada por pressão macro e desalavancagem."
            macroSign < 0 && f.bidAbsorptions > f.askAbsorptions && heldUp ->
                "Cenário B: macro desfavorável, mas há absorção de vendas nas paredes de compra e o preço se sustenta. O mercado pode estar absorvendo a pressão vendedora (resistência à queda)."
            macroSign > 0 && flowSign > 0 ->
                "Cenário C: macro favorável + compras agressoras. Movimento de alta alinhado com as condições macro e com o fluxo comprador" +
                    (if (liqSign > 0) ", reforçado por liquidações de SHORT." else ".")
            macroSign > 0 && f.askAbsorptions > f.bidAbsorptions && heldDown ->
                "Macro favorável, mas há absorção de compras nas paredes de venda e o preço não avança: oferta segurando a alta."
            macroSign < 0 && flowSign < 0 ->
                "Macro desfavorável e vendas agressoras na mesma direção, ainda sem desalavancagem forte (liquidações de LONG). Pressão vendedora alinhada ao cenário macro."
            macroSign > 0 && flowSign == 0 && liqSign > 0 ->
                "Macro favorável e SHORT sendo liquidados, mas sem compra agressora clara: alta puxada por recompra forçada, menos sustentada."
            macroSign < 0 && flowSign == 0 && liqSign < 0 ->
                "Macro desfavorável e LONG sendo liquidados, mas sem venda agressora clara: queda puxada por desalavancagem."
            macroSign != 0 && flowSign != 0 && macroSign != flowSign ->
                "Divergência: o fluxo agressor vai contra o cenário macro. Pode ser movimento técnico local; vale cautela até haver confirmação."
            macroSign == 0 ->
                "Cenário macro neutro/misto: o order flow tende a dominar a leitura de curto prazo."
            else -> "Sem confirmação clara do order flow em relação ao cenário macro."
        }
        return IntegratedView(macro, flow, liq, conf, reading + " Dados públicos não permitem identificar participantes individuais.")
    }
}
