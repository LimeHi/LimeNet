package app.limenet.core

object Flags {

    private val countries = mapOf(
        "германия" to "DE", "польша" to "PL", "финляндия" to "FI", "нидерланды" to "NL",
        "голландия" to "NL", "франция" to "FR", "сша" to "US", "америка" to "US",
        "великобритания" to "GB", "англия" to "GB", "швеция" to "SE", "швейцария" to "CH",
        "турция" to "TR", "япония" to "JP", "сингапур" to "SG", "канада" to "CA",
        "латвия" to "LV", "литва" to "LT", "эстония" to "EE", "чехия" to "CZ",
        "австрия" to "AT", "испания" to "ES", "италия" to "IT", "норвегия" to "NO",
        "дания" to "DK", "бельгия" to "BE", "румыния" to "RO", "болгария" to "BG",
        "венгрия" to "HU", "сербия" to "RS", "украина" to "UA", "казахстан" to "KZ",
        "армения" to "AM", "грузия" to "GE", "израиль" to "IL", "индия" to "IN",
        "бразилия" to "BR", "австралия" to "AU", "гонконг" to "HK", "корея" to "KR",
        "китай" to "CN", "ирландия" to "IE", "исландия" to "IS", "люксембург" to "LU",
        "молдова" to "MD", "португалия" to "PT", "словакия" to "SK", "словения" to "SI",
        "хорватия" to "HR", "греция" to "GR", "мексика" to "MX", "аргентина" to "AR",
        "оаэ" to "AE", "эмираты" to "AE", "саудовская" to "SA", "тайланд" to "TH",
        "вьетнам" to "VN", "малайзия" to "MY", "индонезия" to "ID", "филиппины" to "PH",
        "новая зеландия" to "NZ", "юар" to "ZA", "египет" to "EG", "беларусь" to "BY",
        "узбекистан" to "UZ", "азербайджан" to "AZ", "кипр" to "CY", "мальта" to "MT",
        "лихтенштейн" to "LI", "монако" to "MC", "андорра" to "AD", "албания" to "AL",
        "македония" to "MK", "босния" to "BA", "черногория" to "ME", "коста-рика" to "CR",
        "евросоюз" to "EU", "европа" to "EU",
    )

    /**
     * Код страны, если он читается из имени узла: либо готовый эмодзи-флаг
     * («🇩🇪 Германия»), либо двухбуквенный префикс («DE Германия»), либо
     * название страны («Германия 1»).
     */
    fun codeOf(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        emojiCodeAt(trimmed, 0)?.let { first ->
            val second = emojiCodeAt(trimmed, 2) ?: return null
            return "" + first + second
        }

        if (trimmed[0].code in 0xD800..0xDBFF) return null

        val candidate = trimmed.take(2)
        if (candidate.length == 2 && candidate.all { it in 'A'..'Z' || it in 'a'..'z' }) {
            val after = trimmed.getOrNull(2)
            if (after == null || !after.isLetterOrDigit()) {
                val upper = candidate.uppercase()
                return if (upper == "UK") "GB" else upper
            }
        }

        val lower = trimmed.lowercase()
        for ((country, code) in countries) {
            if (lower.startsWith(country)) return code
        }
        return null
    }

    /** Имя без кода страны: «DE Германия» и «🇩🇪 Германия» → «Германия». */
    fun label(name: String): String {
        val trimmed = name.trim()
        if (codeOf(trimmed) == null) return trimmed

        var rest = trimmed

        // снимаем эмодзи-флаг (пара региональных индикаторов, 4 кодовых единицы UTF-16)
        if (emojiCodeAt(rest, 0) != null && emojiCodeAt(rest, 2) != null) {
            rest = rest.drop(4)
        } else if (rest.length >= 2 && rest.take(2).all { it in 'A'..'Z' || it in 'a'..'z' }) {
            val after = rest.getOrNull(2)
            if (after == null || !after.isLetterOrDigit()) rest = rest.drop(2)
        }

        rest = rest.trimStart(' ', '-', '_', '|', '·', ':', '.', '/').trim()
        return rest.ifEmpty { trimmed }
    }

    /** Эмодзи-флаг из кода страны (региональные индикаторы). */
    fun emoji(code: String): String {
        val upper = code.trim().uppercase()
        if (upper.length != 2 || upper.any { it !in 'A'..'Z' }) return ""
        return upper.map { codePoint(0x1F1E6 + (it.code - 'A'.code)) }.joinToString("")
    }

    /** «DE Германия» → «🇩🇪 Германия». */
    fun decorate(name: String): String {
        val code = codeOf(name) ?: return name
        return "${emoji(code)} ${label(name)}"
    }

    /**
     * Буква страны из регионального индикатора в позиции [index], если он там есть.
     */
    private fun emojiCodeAt(value: String, index: Int): Char? {
        if (index + 1 >= value.length) return null
        if (value[index].code != 0xD83C) return null
        val low = value[index + 1].code
        if (low !in 0xDDE6..0xDDFF) return null
        return ('A'.code + (low - 0xDDE6)).toChar()
    }

    private fun codePoint(value: Int): String {
        if (value <= 0xFFFF) return value.toChar().toString()
        val shifted = value - 0x10000
        val high = 0xD800 + (shifted shr 10)
        val low = 0xDC00 + (shifted and 0x3FF)
        return charArrayOf(high.toChar(), low.toChar()).concatToString()
    }
}
