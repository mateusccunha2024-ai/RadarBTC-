package com.cunhac.btcradar.desktop

import kotlin.math.sqrt

/**
 * Conversa que funciona mesmo sem a IA local (atualização 17):
 * cumprimentos, explicação de conceitos com o valor ao vivo, respostas focadas no que foi perguntado,
 * fala resumida e o filtro do microfone (silêncio e frases que o Whisper inventa).
 */
object JarvisChat {

    // ---------------------------------------------------------------- cumprimentos e conversa curta

    private val GREET = listOf("oi", "ola", "olá", "eae", "e ai", "e aí", "eai", "opa", "salve", "fala", "bom dia", "boa tarde", "boa noite",
        "hello", "hi", "hey", "hola", "buenos dias", "bonjour", "hallo", "ciao")
    private val HOW_ARE = listOf("tudo bem", "tudo bom", "como vai", "como voce esta", "como vc esta", "beleza", "blz", "how are you", "que tal")
    private val THANKS = listOf("obrigado", "obrigada", "valeu", "vlw", "brigado", "thanks", "thank you", "gracias", "merci", "danke", "grazie")
    private val WHO = listOf("quem e voce", "quem e vc", "quem é você", "o que voce e", "seu nome", "who are you", "what are you")
    private val NAME = listOf("jarvis", "javis", "jarviz")

    /** Palavras de cumprimento/conversa que não mudam o sentido da pergunta. */
    private val FILLER = (GREET + HOW_ARE + THANKS + NAME + listOf("tudo", "bem", "por favor", "pfv", "pls", "please", "ai", "aí", "?", "!", ".", ",")).map { JarvisText.fold(it) }

    /** Só conversa (sem pergunta sobre dados)? Devolve a resposta; null se houver pergunta de verdade. */
    fun smallTalk(q: String, hourOfDay: Int = java.time.LocalTime.now().hour): String? {
        val f = " " + JarvisText.fold(q).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (f.isBlank()) return null
        fun has(list: List<String>) = list.any { " ${JarvisText.fold(it)} " in f }
        val greet = has(GREET); val how = has(HOW_ARE); val thanks = has(THANKS); val who = has(WHO)
        if (!greet && !how && !thanks && !who && !has(NAME)) return null
        // Sobrou alguma palavra que não é conversa? Então é pergunta (ex.: "oi, como está o funding?").
        var rest = f
        for (w in FILLER.sortedByDescending { it.length }) rest = rest.replace(" $w ", " ")
        if (rest.isNotBlank() && !who) return null
        val hello = when (hourOfDay) { in 5..11 -> "Bom dia"; in 12..17 -> "Boa tarde"; else -> "Boa noite" }
        return when {
            who -> L("Sou o Jarvis, o assistente do Radar BTC. Leio os dados do app (preço, livro, paredes, fluxo, liquidações, funding, ETFs, macro e notícias), monto planos de trade com o Time Machine e explico conceitos. Nunca envio ordens.")
            thanks && !greet -> L("Por nada. Se quiser, pergunte o preço, o funding ou peça um plano de trade.")
            how -> L(hello) + "! " + L("Tudo certo por aqui, com os livros ao vivo. Quer um resumo do BTC agora ou um plano de trade?")
            else -> L(hello) + "! " + L("Como posso ajudar? Exemplos: \"como está o BTC?\", \"o que é funding?\", \"plano de trade BTC\".")
        }
    }

    // ---------------------------------------------------------------- glossário

    data class Concept(val id: String, val keys: List<String>, val text: String, val factPrefixes: List<String> = emptyList(), val topic: Topic? = null)

    val GLOSSARY = listOf(
        Concept("funding", listOf("funding", "financiamento", "taxa de financiamento", "funding rate"),
            "Funding é a taxa que longs e shorts pagam entre si a cada 8 horas nos contratos perpétuos. Positivo: quem está comprado paga quem está vendido (mercado otimista e alavancado na compra). Negativo: o contrário. Funding muito alto costuma indicar excesso de alavancagem de um lado, o que aumenta o risco de liquidações em cascata.",
            listOf("Funding"), Topic.DERIV),
        Concept("oi", listOf("open interest", "interesse aberto", " oi "),
            "Open interest é o total de contratos futuros em aberto. Subindo junto com o preço: dinheiro novo entrando. Subindo com o preço caindo: mais shorts abrindo. Caindo: posições sendo fechadas ou liquidadas.",
            listOf("Open interest", "Variação do open interest"), Topic.DERIV),
        Concept("liq", listOf("liquidacao", "liquidação", "liquidacoes", "liquidações", "liquidar", "liquidation"),
            "Liquidação é quando a corretora fecha à força uma posição alavancada porque a margem acabou. Muitas liquidações de longs empurram o preço para baixo; de shorts, para cima. O app mostra as liquidações reais e onde estariam as próximas (mapa de liquidez).",
            listOf("Liquidações reais", "Zona ímã"), Topic.LIQ),
        Concept("cvd", listOf("cvd", "delta", "fluxo agressor", "agressor"),
            "Delta é a diferença entre o volume comprado a mercado (agressores de compra) e o vendido a mercado. O CVD é esse delta somado ao longo do tempo. Preço subindo com CVD caindo é uma divergência: a alta pode estar fraca.",
            listOf("Fluxo agressor", "Leitura do fluxo", "Divergência"), Topic.FLOW),
        Concept("wall", listOf("parede", "paredes", "wall", "muro de ordens"),
            "Parede é uma ordem limitada grande no livro, que pode segurar o preço (suporte na compra, resistência na venda). Pode ser cancelada a qualquer momento (spoofing), por isso o app acompanha se ela se mantém ou some.",
            listOf("Parede de"), Topic.WALLS),
        Concept("book", listOf("livro de ofertas", "order book", "orderbook", "book de ofertas"),
            "O livro de ofertas lista as ordens limitadas de compra (bids) e de venda (asks) em cada preço. A diferença entre a melhor compra e a melhor venda é o spread. A profundidade mostra quanto BTC há perto do preço.",
            listOf("Binance:", "Bybit:", "OKX:"), Topic.BOOK),
        Concept("spread", listOf("spread"), "Spread é a distância entre a melhor oferta de compra e a melhor de venda. Spread pequeno: mercado líquido. Spread grande: entrar e sair custa mais.", listOf("Binance:", "Bybit:", "OKX:"), Topic.BOOK),
        Concept("magnet", listOf("zona ima", "zona ímã", "ima", "ímã", "mapa de liquidez", "heatmap"),
            "Zona ímã é uma faixa de preço com muitas liquidações estimadas acumuladas. O preço costuma ser atraído para essas regiões, porque ali há liquidez para grandes participantes. É uma estimativa do modelo, não uma certeza.",
            listOf("Zona ímã"), Topic.LIQ),
        Concept("etf", listOf("etf", "etfs", "ibit", "fbtc"),
            "Os ETFs spot de Bitcoin compram e guardam BTC de verdade para os cotistas. Fluxo líquido positivo é dinheiro entrando (compra de BTC); negativo, saindo.",
            listOf("ETFs"), Topic.ETF),
        Concept("leverage", listOf("alavancagem", "alavancado", "leverage", "margem"),
            "Alavancagem é operar com mais dinheiro do que você tem, usando margem. Com 10x, uma queda de cerca de 10% contra você zera a posição (liquidação). Aumenta o ganho e, na mesma medida, a perda.", emptyList()),
        Concept("longshort", listOf("long", "short", "comprado", "vendido"),
            "Long é apostar na alta (comprado). Short é apostar na queda (vendido), vendendo um contrato para recomprar mais barato.", listOf("Liquidações reais")),
        Concept("stop", listOf("stop loss", "stop"),
            "Stop é o preço em que você sai para limitar a perda, definido antes de entrar. No plano do Jarvis ele fica no meio da abertura de 15 minutos.", emptyList()),
        Concept("r", listOf("alvo 2r", "o que e r", "risco retorno", "retorno risco", "reward", " 2r", " r multiplo"),
            "R é o risco do trade (distância da entrada até o stop). Um alvo de 2R fica a duas vezes essa distância acima da entrada: ganha 2 se der certo, perde 1 se der errado.", emptyList()),
        Concept("timemachine", listOf("time machine", "maquina do tempo", "monte carlo", "replay"),
            "O Time Machine refaz o resto do dia 10 mil vezes com os próprios candles do ativo e conta quantas vezes o alvo veio antes do stop. Não é previsão: mostra as chances com base no comportamento passado.", emptyList()),
        Concept("orb", listOf("abertura de 15", "rompimento da abertura", "opening range", "orb"),
            "Rompimento da abertura: marca a máxima e a mínima dos primeiros 15 minutos e entra quando um candle de 5 minutos fecha acima da máxima. O stop fica no meio da faixa e o alvo em 2R.", emptyList()),
        Concept("support", listOf("suporte", "resistencia", "resistência", "support", "resistance"),
            "Suporte é uma região onde compradores costumam aparecer e segurar a queda; resistência, onde vendedores seguram a alta. No app, as paredes grandes do livro mostram onde eles estão agora.", listOf("Parede de"), Topic.WALLS),
        Concept("dxy", listOf("dxy", "indice do dolar", "índice do dólar"),
            "DXY é o índice do dólar contra outras moedas fortes. Dólar forte costuma pesar sobre o Bitcoin e ativos de risco; dólar fraco costuma ajudar.", listOf("DXY", "Dólar"), Topic.MACRO),
        Concept("vix", listOf("vix", "indice do medo", "índice do medo"),
            "VIX mede a volatilidade esperada das ações dos EUA. VIX alto é medo no mercado e costuma vir com queda de ativos de risco.", listOf("VIX"), Topic.MACRO),
        Concept("feargreed", listOf("medo e ganancia", "medo e ganância", "fear and greed", "fear & greed"),
            "O índice medo e ganância vai de 0 (medo extremo) a 100 (ganância extrema) e resume o humor do mercado cripto.", listOf("Índice medo"), Topic.MACRO),
        Concept("fed", listOf("fed", "juros", "taxa de juros", "fomc", "federal reserve"),
            "O Fed é o banco central dos EUA. Juros altos deixam o dinheiro mais caro e costumam pesar sobre o Bitcoin; cortes de juros costumam ajudar ativos de risco.", listOf("Juros do Fed", "Treasury", "Kalshi"), Topic.MACRO),
        Concept("stable", listOf("stablecoin", "stablecoins", "usdt", "usdc"),
            "Stablecoins são moedas digitais atreladas ao dólar (USDT, USDC). Estoque crescendo é mais dinheiro pronto para comprar cripto.", listOf("Estoque de stablecoins"), Topic.MACRO),
        Concept("halving", listOf("halving"),
            "Halving é o corte pela metade da recompensa dos mineradores de Bitcoin, a cada 210 mil blocos (cerca de 4 anos). Reduz a emissão de novos BTC.", emptyList()),
        Concept("iceberg", listOf("iceberg", "absorcao", "absorção"),
            "Iceberg é uma ordem grande escondida que aparece aos poucos no livro. Absorção é quando muita agressão bate num preço e ele não anda: alguém está absorvendo tudo.", listOf("Parede de"), Topic.WALLS),
    )

    private val ASK_CONCEPT = listOf("o que e", "oque e", "o q e", "que e ", "o que significa", "significa", "explica", "explique", "me explica",
        "como funciona", "pra que serve", "para que serve", "por que importa", "porque importa", "what is", "what's", "what does", "explain",
        "define", "definicao", "que quer dizer", "o que sao", "qué es", "c'est quoi", "was ist")

    /** A pergunta pede uma explicação ("o que é…", "explica…")? */
    fun asksConcept(q: String): Boolean { val f = " " + JarvisText.fold(q) + " "; return ASK_CONCEPT.any { JarvisText.fold(it) in f } }

    /** Pergunta de conceito ("o que é funding?") → conceito do glossário; null se não for. */
    fun concept(q: String): Concept? {
        val f = " " + JarvisText.fold(q) + " "
        if (!asksConcept(q)) return null
        return GLOSSARY.maxByOrNull { c -> c.keys.maxOfOrNull { k -> if (JarvisText.fold(k) in f) k.length else 0 } ?: 0 }
            ?.takeIf { c -> c.keys.any { JarvisText.fold(it) in f } }
    }

    /** Explicação + o valor ao vivo do app (se houver). */
    fun conceptAnswer(c: Concept, facts: List<Fact>): Pair<String, List<Fact>> {
        val live = facts.filter { f -> c.factPrefixes.any { f.text.startsWith(it) } }.take(3)
        val text = L(c.text) + if (live.isEmpty()) "" else " " + L("Agora no app") + ": " + live.joinToString("; ") { L(it.text) } + "."
        return text to live
    }

    // ---------------------------------------------------------------- foco no assunto

    private val PREFIX: Map<Topic, List<String>> = mapOf(
        Topic.PRICE to listOf("Preço do BTC", "Variação do BTC"),
        Topic.DERIV to listOf("Funding", "Open interest", "Variação do open interest"),
        Topic.BOOK to listOf("Binance:", "Bybit:", "OKX:"),
        Topic.WALLS to listOf("Parede de"),
        Topic.FLOW to listOf("Fluxo agressor", "Leitura do fluxo", "Divergência"),
        Topic.LIQ to listOf("Liquidações reais", "Zona ímã"),
        Topic.ETF to listOf("ETFs"),
        Topic.MACRO to listOf("Score macro", "Juros do Fed", "Treasury", "DXY", "S&P", "Nasdaq", "VIX", "Ouro", "Índice medo", "Kalshi", "Juro real", "Estoque de stablecoins"),
        Topic.NEWS to listOf("Notícia"),
        Topic.CALENDAR to listOf("Agenda"),
        Topic.HOURS to listOf("A bolsa", "O BTC negocia"),
        Topic.STATUS to listOf("Livros ao vivo"),
    )

    /** Fatos do assunto perguntado primeiro (antes vinha sempre o preço na frente, mesmo perguntando de funding). */
    fun focus(facts: List<Fact>, topics: Set<Topic>): List<Fact> {
        val asked = topics - setOf(Topic.SUMMARY, Topic.HELP)
        if (asked.isEmpty()) return facts
        val pref = asked.flatMap { PREFIX[it].orEmpty() }
        val (hit, rest) = facts.partition { f -> pref.any { f.text.startsWith(it) } }
        return hit + rest
    }

    /** Resposta curta com os dados, sem a IA: até [max] fatos, do assunto perguntado primeiro. */
    fun ruleAnswer(facts: List<Fact>, topics: Set<Topic>, max: Int = 5): String {
        if (facts.isEmpty()) return L("Ainda não tenho dados para isso. Espere os livros conectarem ou abra a aba correspondente.")
        val f = focus(facts, topics)
        val asked = topics - setOf(Topic.SUMMARY, Topic.HELP)
        val pref = asked.flatMap { PREFIX[it].orEmpty() }
        val only = if (pref.isEmpty()) f else f.filter { x -> pref.any { x.text.startsWith(it) } }.ifEmpty { f }
        return only.take(max).joinToString(". ") { L(it.text) } + "."
    }

    // ---------------------------------------------------------------- fala resumida

    /** O que a voz lê: as 2 primeiras frases (até ~240 letras). O resto fica na tela. */
    fun forSpeech(text: String, maxChars: Int = 240): String {
        val parts = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val out = StringBuilder()
        for ((i, p) in parts.withIndex()) {
            if (i >= 2 || (out.isNotEmpty() && out.length + p.length > maxChars)) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(p)
        }
        val said = out.toString().ifBlank { text.take(maxChars) }
        return if (said.length < text.length - 5) said + " " + L("Os detalhes estão na tela.") else said
    }

    // ---------------------------------------------------------------- microfone

    /**
     * Tem fala de verdade no áudio? Mede a energia em janelas de 30 ms e compara com o ruído de fundo.
     * Sem isso o Whisper "ouvia" frases no silêncio (ex.: "Eu não sou de você.") e enviava sozinho.
     */
    fun hasSpeech(samples: FloatArray, rate: Int = 16_000): Boolean {
        val win = rate * 30 / 1000
        if (samples.size < win * 5) return false
        val rms = (0 until samples.size / win).map { k ->
            var s = 0.0
            for (i in k * win until (k + 1) * win) { val v = samples[i].toDouble(); s += v * v }
            sqrt(s / win)
        }.sorted()
        val floor = rms[(rms.size * 0.2).toInt().coerceAtMost(rms.size - 1)]
        val th = maxOf(0.012, floor * 3.0)
        return rms.count { it > th } >= 10   // pelo menos ~0,3 s acima do ruído
    }

    /** Frases que o Whisper costuma inventar em silêncio ou ruído. */
    private val HALLUCINATIONS = listOf("eu nao sou de voce", "obrigado", "obrigada", "tchau", "legendas pela comunidade amara org", "legenda",
        "inscreva se", "se inscreva", "thank you", "thanks for watching", "you", "bye", "subtitles by", "amara org", "musica", "aplausos",
        "e ai", "hum", "ah", "...", "eh")

    /** Transcrição suspeita (frase típica de alucinação ou curta demais)? */
    fun suspicious(t: String): Boolean {
        val f = JarvisText.fold(t).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (f.length < 2) return true
        return HALLUCINATIONS.any { f == JarvisText.fold(it) }
    }
}
