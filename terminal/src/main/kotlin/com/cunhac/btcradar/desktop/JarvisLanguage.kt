package com.cunhac.btcradar.desktop

/**
 * Idioma em que o Jarvis fala (atualização 18).
 *
 * Começa sempre em português. Pedidos como "fale em inglês", "responda em espanhol", "speak English" ou
 * "volte para o português" trocam o idioma das respostas e da voz (o resto do app não muda). Vale até pedir outro
 * idioma ou fechar o app.
 */
object JarvisLanguage {
    const val DEFAULT = "pt"

    /** Nomes de cada idioma, sem acento e em minúsculas, como as pessoas costumam dizer (em português, inglês e no próprio idioma). */
    private val NAMES: Map<String, List<String>> = linkedMapOf(
        "pt" to listOf("portugues do brasil", "portugues brasileiro", "portugues", "portuguese", "portugais", "portugiesisch", "portoghese"),
        "en" to listOf("ingles", "english", "anglais", "englisch", "inglese"),
        "es" to listOf("espanhol", "spanish", "espanol", "castellano", "espagnol", "spanisch", "spagnolo"),
        "fr" to listOf("frances", "french", "francais", "franzosisch", "francese"),
        "de" to listOf("alemao", "german", "deutsch", "aleman", "allemand", "tedesco"),
        "it" to listOf("italiano", "italian", "italien", "italienisch"),
        "ru" to listOf("russo", "russian", "ruso", "russe", "russisch", "русский", "по-русски", "по русски"),
        "zh" to listOf("chines", "mandarim", "chinese", "mandarin", "chino", "chinois", "chinesisch", "cinese", "中文", "汉语", "普通话"),
        "ja" to listOf("japones", "japanese", "japonais", "japanisch", "giapponese", "日本語"),
        "ko" to listOf("coreano", "korean", "coreen", "koreanisch", "한국어"),
    )

    /** Verbos de "fale / responda / troque" em vários idiomas. */
    private val VERB = Regex(
        "\\b(fal[ae]r?|falando|respond[ae]r?|responde|converse|conversa[r]?|mud[ae]r?|troc[ae]r?|troque|volt[ae]r?|use|usar|passe|passa[r]?|" +
            "fala comigo|speak|talk|answer|reply|respond|switch|change|use|go back|habla[r]?|hable|responde|parle[rz]?|reponds|sprich|antworte|parla|rispondi)\\b"
    )
    /** "Como se diz X em inglês" é tradução de uma palavra, não troca de idioma. */
    private val TRANSLATE = Regex("\\b(se diz|como diz|como fala|traduz|traducao|traduzir|translate|translation|significa|meaning)\\b")
    private val PREP = "(?:em|no|na|in|en|para|pra|to|into|auf|in lingua|em lingua|no idioma|na lingua)"

    data class Switch(val code: String, val rest: String)

    /**
     * Pedido de troca de idioma, ou null. `rest` é o que sobra da frase quando a pessoa já pergunta outra coisa
     * junto ("fale em inglês: como está o funding?").
     */
    fun detect(text: String): Switch? {
        val f = JarvisText.fold(text).trim()
        if (f.isEmpty() || TRANSLATE.containsMatchIn(f)) return null
        for ((code, names) in NAMES) for (n in names) {
            val rx = Regex("(^|[^\\p{L}])($PREP\\s+)?(o\\s+|the\\s+)?${Regex.escape(n)}(?=$|[^\\p{L}])")
            val m = rx.find(f) ?: continue
            val withPrep = m.groupValues[2].isNotBlank()
            val verb = VERB.containsMatchIn(f)
            // Sem verbo, só vale se a frase for curta ("em inglês, por favor", "english please", "agora em espanhol").
            val rest = (f.substring(0, m.range.first) + " " + f.substring(m.range.last + 1))
                .replace(Regex("\\b(por favor|please|agora|now|a partir de agora|from now on|comigo|with me|so|apenas|only|sempre|always|de novo|again|lingua|idioma|language|pode|poderia|voce|can|could|would|you|me|senhor|sir|favor)\\b"), " ")
                .replace(VERB, " ").replace(Regex("\\b(o|a|os|as|em|in|en|para|pra|to|the|e|and|y|ok|jarvis)\\b"), " ")
                .replace(Regex("[^\\p{L}\\p{N}?]+"), " ").trim()
            val restWords = rest.split(' ').count { it.length > 1 }
            if (restWords > 0 && !(verb && (withPrep || restWords <= 3))) continue
            return Switch(code, if (restWords >= 1) questionPart(text, n) else "")
        }
        return null
    }

    /** A pergunta que veio junto: o que vem depois de ":" ou ","; sem separador, a frase inteira (a busca ignora o nome do idioma). */
    private fun questionPart(original: String, name: String): String {
        val i = original.indexOfAny(charArrayOf(':', '?', ','))
        val after = if (i in 0 until original.length - 1) original.substring(i + 1).trim() else ""
        return after.takeIf { a -> a.length > 3 && !JarvisText.fold(a).contains(name) } ?: original
    }

    /** Confirmação, dita no idioma novo. */
    fun confirm(code: String): String = when (code) {
        "en" -> "Very well, sir. I'll speak English from now on."
        "es" -> "Muy bien, señor. A partir de ahora hablo en español."
        "fr" -> "Très bien, monsieur. À partir de maintenant, je parle français."
        "de" -> "Sehr wohl. Ab jetzt spreche ich Deutsch."
        "it" -> "Molto bene, signore. D'ora in poi parlo italiano."
        "ru" -> "Хорошо, сэр. Теперь я говорю по-русски."
        "zh" -> "好的，先生。从现在起我说中文。"
        "ja" -> "かしこまりました。これからは日本語で話します。"
        "ko" -> "알겠습니다. 이제부터 한국어로 말하겠습니다."
        else -> "Certo, senhor. A partir de agora falo em português."
    }

    /** Nome do idioma para a IA local. */
    fun llmName(code: String): String = when (code) {
        "en" -> "English"; "es" -> "Spanish"; "fr" -> "French"; "de" -> "German"; "it" -> "Italian"
        "ru" -> "Russian"; "zh" -> "Simplified Chinese"; "ja" -> "Japanese"; "ko" -> "Korean"; else -> "Brazilian Portuguese"
    }

    /** Nome em português, para a tela. */
    fun label(code: String): String = when (code) {
        "en" -> "inglês"; "es" -> "espanhol"; "fr" -> "francês"; "de" -> "alemão"; "it" -> "italiano"
        "ru" -> "russo"; "zh" -> "chinês"; "ja" -> "japonês"; "ko" -> "coreano"; else -> "português"
    }
}
