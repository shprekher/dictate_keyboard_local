/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

/** Deterministic, deliberately partial grammar. No guessed years, currency symbols, time zones,
 * ambiguous numeric dates, idioms, phone-number groups or cross-language number compositions.
 * Unsupported/ambiguous runs are retained in their entirety, not partially converted.
 */
object NumberNormalizer {
    private val en = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen")
        .split(' ').withIndex().associate { it.value to it.index } +
        mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val ru = mapOf(
        "ноль" to 0, "нуль" to 0, "один" to 1, "одна" to 1, "одно" to 1, "одну" to 1,
        "два" to 2, "две" to 2, "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6,
        "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10, "одиннадцать" to 11,
        "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19,
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400, "пятьсот" to 500,
        "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800, "девятьсот" to 900,
    )
    private val scales = mapOf("thousand" to 1000L, "million" to 1000000L, "billion" to 1000000000L,
        "тысяча" to 1000L, "тысячи" to 1000L, "тысяч" to 1000L,
        "миллион" to 1000000L, "миллиона" to 1000000L, "миллионов" to 1000000L,
        "миллиард" to 1000000000L, "миллиарда" to 1000000000L, "миллиардов" to 1000000000L)
    private val ordinalEn = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
        "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth")
        .withIndex().associate { it.value to it.index + 1 } + mapOf("thirtieth" to 30, "fortieth" to 40, "fiftieth" to 50, "hundredth" to 100)
    private val ordinalRu = buildMap<String, Pair<Int, String>> {
        val stems = listOf("перв", "втор", "трет", "четверт", "пят", "шест", "седьм", "восьм", "девят", "десят",
            "одиннадцат", "двенадцат", "тринадцат", "четырнадцат", "пятнадцат", "шестнадцат", "семнадцат", "восемнадцат", "девятнадцат", "двадцат")
        for ((i, stem) in stems.withIndex()) {
            for ((ending, suffix) in mapOf("ый" to "й", "ой" to "й", "ий" to "й", "ая" to "я", "ое" to "е", "ого" to "го", "ому" to "му")) {
                put(stem + ending, i + 1 to suffix)
            }
        }
        put("третье", 3 to "е"); put("третьего", 3 to "го"); put("третья", 3 to "я")
        put("тридцатый", 30 to "й"); put("тридцатое", 30 to "е"); put("тридцатого", 30 to "го")
    }
    private val months = ("january february march april may june july august september october november december " +
        "января февраля марта апреля мая июня июля августа сентября октября ноября декабря").split(' ').toSet()
    private fun validDay(day: Long, month: String): Boolean = day in 1..when (month) {
        "february", "февраля" -> 29L // Do not infer a year; February 29 can be valid.
        "april", "june", "september", "november", "апреля", "июня", "сентября", "ноября" -> 30L
        else -> 31L
    }
    private val units = ("dollar dollars euro euros pound pounds ruble rubles rouble roubles cent cents " +
        "доллар доллара долларов евро рубль рубля рублей фунт фунта фунтов цент цента центов " +
        "kilograms kilogram meters meter kilometres kilometres liters litres килограмм килограмма килограммов " +
        "метр метра метров литр литра литров hours minutes seconds часа часов минут секунды секунд " +
        "items item штук штуки штука years лет года год").split(' ').toSet()
    private val percents = setOf("percent", "percentage", "процент", "процента", "процентов")
    private val decimalMarkers = setOf("point", "точка", "запятая", "целых", "целая")
    private val denominator = mapOf("десятых" to 1, "сотых" to 2, "тысячных" to 3)
    private val connectors = setOf("and")
    private val signs = setOf("minus", "negative", "минус")
    private fun base(w: String): Int? = en[w] ?: ru[w]
    private fun numberWord(w: String): Boolean = base(w) != null || w == "hundred" || w in scales || w in ordinalEn || w in ordinalRu
    fun isNumberWord(w: String): Boolean = numberWord(fold(w)) || fold(w) in decimalMarkers || fold(w) in signs || fold(w) in denominator

    private fun integer(words: List<String>): Long? {
        if (words.isEmpty() || words.size > 24) return null
        val cyrillic = words.first().any { it in 'а'..'я' }
        if (words.any { it.any { c -> c in 'а'..'я' } != cyrillic }) return null
        var total = 0L
        var group = 0L
        var previous = 1000
        var lastScale = Long.MAX_VALUE
        var lastWasAnd = false
        for ((index, word) in words.withIndex()) {
            if (word == "and") {
                if (lastWasAnd || index == 0 || index == words.lastIndex || group < 100 && total == 0L) return null
                lastWasAnd = true
                continue
            }
            val scale = scales[word]
            when {
                scale != null -> {
                    if (group == 0L && index == 0 && word in setOf("тысяча", "миллион", "миллиард")) group = 1
                    if (group == 0L || scale >= lastScale || lastWasAnd) return null
                    total += group * scale
                    group = 0; previous = 1000; lastScale = scale
                }
                word == "hundred" -> {
                    if (group !in 1..9 || previous !in 1..9) return null
                    group *= 100; previous = 100
                }
                else -> {
                    val n = base(word) ?: return null
                    if (n >= previous || previous < 20 || previous in 20..90 && n !in 1..9 ||
                        previous >= 100 && n >= 100 && group > 0) return null
                    if (lastWasAnd && n >= 100) return null
                    group += n; previous = n
                }
            }
            lastWasAnd = false
        }
        return total + group
    }

    private fun ordinal(words: List<String>): Pair<Long, String>? {
        val last = words.lastOrNull() ?: return null
        val n = ordinalEn[last] ?: ordinalRu[last]?.first ?: return null
        val prefix = if (words.size == 1) 0L else integer(words.dropLast(1)) ?: return null
        if (prefix != 0L && (prefix % 10 != 0L || n > 9)) return null
        val value = prefix + n
        val suffix = ordinalRu[last]?.second ?: when {
            value % 100 in 11L..13L -> "th"
            value % 10 == 1L -> "st"
            value % 10 == 2L -> "nd"
            value % 10 == 3L -> "rd"
            else -> "th"
        }
        return value to suffix
    }

    fun normalize(text: String, protectedRanges: List<IntRange> = emptyList()): String {
        if (text.length > 100_000) return text
        // Split spoken-number hyphens, but preserve identifiers/compounds at the span boundaries below.
        val tokens = Regex("[\\p{L}\\p{M}]+|[0-9]+").findAll(text).toList()
        val protected = Regex("https?://\\S+|\\S+@\\S+|[`\"«][^`\"»]*[`\"»]|“[^”]*”|‘[^’]*’|(?<![\\p{L}])'[^'\\n]+'(?![\\p{L}])|[#@][\\p{L}\\p{N}_]+|[\\p{L}]+[0-9][\\p{L}\\p{N}]*", RegexOption.IGNORE_CASE)
            .findAll(text).map { it.range }.toList().plus(protectedRanges).sortedBy { it.first }
        fun key(i: Int) = tokens.getOrNull(i)?.value?.let(::fold).orEmpty()
        fun adjacent(a: Int, b: Int) = text.substring(tokens[a].range.last + 1, tokens[b].range.first).let { gap ->
            gap.isNotEmpty() && gap.all { it == ' ' || it == '\t' || it == '-' }
        }
        val out = StringBuilder()
        var cursor = 0
        var i = 0
        var protectedIndex = 0
        while (i < tokens.size) {
            val start = i
            val signed = key(i) in signs
            if (signed) i++
            if (!numberWord(key(i)) || i > start && (i >= tokens.size || !adjacent(start, i))) { i = start + 1; continue }
            val beginNumber = i
            var end = i + 1
            // One maximal numeric run; connectors only when followed by a number.
            while (end < tokens.size && adjacent(end - 1, end) &&
                (numberWord(key(end)) || key(end) in decimalMarkers || key(end) in denominator ||
                    key(end) in connectors && end + 1 < tokens.size && adjacent(end, end + 1) && numberWord(key(end + 1)))) end++
            val words = (beginNumber until end).map(::key)
            val previous = key(start - 1)
            val next = key(end)
            val connectedNext = end < tokens.size && adjacent(end - 1, end)
            val connectedPrevious = start > 0 && adjacent(start - 1, start)
            val dateContext = connectedNext && next in months || connectedPrevious && previous in months &&
                (previous !in setOf("may", "march") || ordinal(words) != null)
            val month = if (next in months) next else previous
            val forbiddenContext = previous in setOf("a", "an", "однажды", "once") && words.size == 1 && !dateContext
            var replacement: String? = null
            val decimalAt = words.indexOfFirst { it in decimalMarkers }
            val ordinal = ordinal(words)
            if (decimalAt > 0) {
                val whole = integer(words.take(decimalAt))
                val fraction = words.drop(decimalAt + 1)
                val places = denominator[fraction.lastOrNull()]
                val digits = if (places != null && words[decimalAt] in setOf("целых", "целая")) {
                    integer(fraction.dropLast(1))?.takeIf { it < Math.pow(10.0, places.toDouble()).toLong() }
                        ?.toString()?.padStart(places, '0')
                } else if (fraction.isNotEmpty() && fraction.all { (base(it) ?: 100) in 0..9 }) {
                    fraction.joinToString("") { base(it).toString() }
                } else null
                if (whole != null && digits != null && digits.length <= 9) replacement = "$whole${if (words.first().any { it in 'а'..'я' }) ',' else '.'}$digits"
            } else if (ordinal != null) {
                if (dateContext) {
                    if (validDay(ordinal.first, month)) replacement = ordinal.first.toString()
                }
                else if (words.size > 1 || connectedNext && next in setOf("floor", "place", "этаж", "место", "этажом")) {
                    replacement = ordinal.first.toString() + (if (words.last() in ordinalRu) "-" else "") + ordinal.second
                }
            } else {
                val value = integer(words)
                val namedMonthYear = connectedPrevious && previous in months && words.size > 1 && value != null && value in 1000..9999
                if (value != null && (!dateContext || validDay(value, month) || namedMonthYear) &&
                    (words.size > 1 || value >= 20 || signed || connectedNext && (next in units || next in percents) || dateContext)) replacement = value.toString()
                // A cue + hour + tens/minutes, without guessing AM/PM or a date.
                if (replacement == null && connectedPrevious && previous in setOf("at", "в") && words.size in 2..4) {
                    val options = (1 until words.size).mapNotNull { split ->
                        val hour = integer(words.take(split))
                        val minute = integer(words.drop(split))
                        if (hour != null && hour in 0..23 && minute != null && minute in 10..59 && base(words[split]) in 10..50) {
                            "$hour:${minute.toString().padStart(2, '0')}"
                        } else null
                    }.distinct()
                    if (options.size == 1) replacement = options.single()
                }
            }
            val first = tokens[start].range.first
            var last = tokens[end - 1].range.last + 1
            while (protectedIndex < protected.size && protected[protectedIndex].last < first) protectedIndex++
            val touchesProtected = (protected.getOrNull(protectedIndex)?.first ?: Int.MAX_VALUE) < last ||
                first > 0 && (text[first - 1].isLetterOrDigit() || text[first - 1] in "/_.-") ||
                last < text.length && (text[last].isLetterOrDigit() || text[last] in "/_-")
            // Never normalize one half of an unknown mixed-script or malformed numeric run.
            if (replacement != null && !forbiddenContext && !touchesProtected) {
                if (signed) replacement = "-$replacement"
                if (connectedNext && next in percents) { replacement += "%"; last = tokens[end].range.last + 1; end++ }
                out.append(text, cursor, first).append(replacement)
                cursor = last
            }
            i = end
        }
        return out.append(text, cursor, text.length).toString()
    }
}
