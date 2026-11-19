/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

/**
 * Deterministic, offline spoken-number normalizer.
 *
 * The text is lexed into words and numeric literals. A numeric span is then built from
 * adjacent tokens, allowing only two kinds of separators: whitespace/hyphen, and a single
 * punctuation mark that belongs to a spoken decimal marker ("274, точка 15" is one number,
 * "13, 14" is a list). This is what ASR punctuation looks like, so the grammar is not broken
 * by a comma the recognizer inserted. Everything outside a recognized span is left untouched.
 *
 * Policy: spoken quantities up to 9999 are written as digits; quantities from 10000 stay in
 * words. Decimals, phones, addresses, dates, times, codes and already-written digits are always
 * digits. A marker word is only interpreted between numeric parts; an isolated "запятая" in
 * dictation becomes a comma, but quoted or explicitly discussed words are preserved.
 *
 * No guessed years, locales, currencies, time zones, AM/PM, arithmetic or homophones.
 * Ambiguous runs are retained in their entirety rather than partially converted.
 */
object NumberNormalizer {
    private const val MAX_DIGITS = 9999L

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
        // Oblique cases of 2–30 and the teens. These are unambiguously numeric. The 1-forms
        // (одного/одной…), сорок (also "magpie"), 50–80 and the hundreds are deliberately left
        // out: they are either too ambiguous in prose or too irregular to map safely.
        "двух" to 2, "двум" to 2, "двумя" to 2,
        "трёх" to 3, "трех" to 3, "трём" to 3, "трем" to 3, "тремя" to 3,
        "четырёх" to 4, "четырех" to 4, "четырём" to 4, "четырем" to 4, "четырьмя" to 4,
        "пяти" to 5, "пятью" to 5,
        "шести" to 6, "шестью" to 6,
        "семи" to 7, "семью" to 7,
        "восьми" to 8, "восьмью" to 8, "восемью" to 8,
        "девяти" to 9, "девятью" to 9,
        "десяти" to 10, "десятью" to 10,
        "одиннадцати" to 11, "одиннадцатью" to 11,
        "двенадцати" to 12, "двенадцатью" to 12,
        "тринадцати" to 13, "тринадцатью" to 13,
        "четырнадцати" to 14, "четырнадцатью" to 14,
        "пятнадцати" to 15, "пятнадцатью" to 15,
        "шестнадцати" to 16, "шестнадцатью" to 16,
        "семнадцати" to 17, "семнадцатью" to 17,
        "восемнадцати" to 18, "восемнадцатью" to 18,
        "девятнадцати" to 19, "девятнадцатью" to 19,
        "двадцати" to 20, "двадцатью" to 20,
        "тридцати" to 30, "тридцатью" to 30,
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
    private val units = ("dollar dollars euro euros pound pounds ruble rubles rouble roubles cent cents " +
        "доллар доллара долларов евро рубль рубля рублей фунт фунта фунтов цент цента центов " +
        "kilograms kilogram meters meter kilometres kilometres liters litres килограмм килограмма килограммов " +
        "метр метра метров литр литра литров hours minutes seconds часа часов минут секунды секунд " +
        "items item штук штуки штука years лет года год").split(' ').toSet()
    private val percents = setOf("percent", "percentage", "процент", "процента", "процентов")
    private val decimalMarkers = setOf("point", "точка", "запятая", "целых", "целая")
    private val denominator = mapOf(
        "десятых" to 1, "десятая" to 1, "десятое" to 1,
        "сотых" to 2, "сотая" to 2, "сотое" to 2,
        "тысячных" to 3, "тысячная" to 3, "тысячное" to 3,
        "десятитысячных" to 4, "десятитысячная" to 4, "десятитысячное" to 4,
    )
    private val connectors = setOf("and", "и")
    private val signs = setOf("minus", "negative", "минус")
    private val plus = setOf("плюс", "plus")
    private val addressLabels = setOf("дом", "корпус", "строение", "квартира", "офис", "building", "apartment", "suite")
    private val phoneCues = setOf("телефон", "код", "phone", "code", "номер")
    private val ordinalCues = setOf("floor", "place", "этаж", "место", "этажом")
    private val scalePlurals = setOf("тысячи", "тысяч", "миллионы", "миллионов", "миллиарды", "миллиардов")
    private val commaMentionBefore = setOf("слово", "термин", "знак", "нужна", "ставится", "стоит",
        "пропущена", "лишняя", "пропущенная", "такая", "эта", "где", "зачем")
    private val commaMentionAfter = setOf("нужна", "ставится", "стоит", "разделяет", "отделяет", "означает",
        "пропущена", "лишняя", "перед", "после", "здесь", "это")

    // A complete dial shape. An arbitrary hyphen between numbers could also be a range or a date.
    private val dialGap = """[ \u00a0-]+"""
    private val dialFormat = Regex("""(?:\+?\d{1,3}(?:$dialGap|(?=[(])))?(?:\(\d{3}\)[ \u00a0-]*|\d{3}$dialGap)\d{3}$dialGap(?:\d{2}$dialGap\d{2}|\d{4})""")

    private val tokenPattern = Regex("""\d+(?:[.,]\d+)?|[\p{L}\p{M}]+""")
    private val protectedPattern = Regex(
        """https?://\S+|\S+@\S+|[`"«][^`"»]*[`"»]|“[^”]*”|‘[^’]*’|(?<![\p{L}])'[^'\n]+'(?![\p{L}])|[#@][\p{L}\p{N}_]+|[\p{L}]+[0-9][\p{L}\p{N}]*|\d+(?:[.,]\d+){2,}""",
        RegexOption.IGNORE_CASE,
    )

    private enum class Gap { PLAIN, HYPHEN, MARK, NONE, OTHER }

    private class Token(val text: String, val key: String, val range: IntRange, val literal: Boolean)

    private fun literal(w: String) = w.isNotEmpty() && w.all { it in '0'..'9' }
    private fun base(w: String): Int? = en[w] ?: ru[w]
    private fun cyrillic(w: String) = w.any { it in 'а'..'я' || it == 'ё' }
    private fun latin(w: String) = w.any { it in 'a'..'z' }
    private fun numberWord(w: String) = base(w) != null || w == "hundred" || w in scales || w in ordinalEn || w in ordinalRu
    private fun numericToken(w: String) = numberWord(w) || w in decimalMarkers || w in denominator

    /** Used by the meaning guard, so it must recognize every word the normalizer can rewrite. */
    fun isNumberWord(w: String): Boolean {
        val key = fold(w)
        return numberWord(key) || key in decimalMarkers || key in signs || key in denominator
    }

    private fun pow10(n: Int): Long = when (n) {
        1 -> 10L; 2 -> 100L; 3 -> 1000L; 4 -> 10000L; else -> 1L
    }

    private fun gapKind(gap: String): Gap {
        if (gap.isEmpty()) return Gap.NONE
        if (gap.all { it == ' ' || it == '\t' || it == '\u00a0' }) return Gap.PLAIN
        if (gap.all { it == ' ' || it == '\t' || it == '\u00a0' || it == '-' }) return Gap.HYPHEN
        val core = gap.trim(' ', '\t', '\u00a0')
        if (core.length == 1 && core[0] in ",.;:") return Gap.MARK
        return Gap.OTHER
    }

    private fun tokenize(text: String): List<Token> = tokenPattern.findAll(text).map { m ->
        Token(m.value, fold(m.value), m.range, m.value[0].isDigit())
    }.toList()

    /** Strict Russian/English cardinal grammar. Anything malformed returns null. */
    private fun integer(words: List<String>): Long? {
        if (words.isEmpty() || words.size > 24) return null
        val cyr = words.first().let(::cyrillic)
        if (words.any { if (cyr) latin(it) else cyrillic(it) }) return null
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
                    if (n == 0 && words.size > 1) return null // a spoken zero is a digit, never an empty group
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

    private fun validDay(day: Long, month: String): Boolean = day in 1..when (month) {
        "february", "февраля" -> 29L // Do not infer a year; February 29 can be valid.
        "april", "june", "september", "november", "апреля", "июня", "сентября", "ноября" -> 30L
        else -> 31L
    }

    /** Digit-by-digit runs and spoken telephone groups. Returns null unless it is a plausible run. */
    private fun digitRun(words: List<String>, phoneCue: Boolean): String? {
        if (words.isEmpty() || words.size > 32) return null
        val alphabetic = words.filterNot(::literal)
        if (alphabetic.any(::cyrillic) && alphabetic.any(::latin)) return null
        if (words.all { literal(it) || base(it) in 0..9 }) {
            val digits = words.joinToString("") { if (literal(it)) it else base(it).toString() }
            if (words.size >= 2 && (words.any { !literal(it) } || words.all { it.length == 1 }) || phoneCue || digits.length in 7..15) return digits
        }
        val result = StringBuilder()
        var i = 0
        while (i < words.size) {
            if (literal(words[i])) {
                result.append(words[i++])
                continue
            }
            var end = minOf(i + 5, words.size)
            var group: Long? = null
            while (end > i) {
                group = integer(words.subList(i, end))?.takeIf { it in 0..999 }
                if (group != null) break
                end--
            }
            if (group == null) return null
            result.append(group)
            i = end
        }
        return result.toString().takeIf { phoneCue && it.length in 1..15 || it.length in 7..15 }
    }

    private fun fractionDigits(words: List<String>, allowCardinal: Boolean): String? {
        if (words.isEmpty()) return null
        if (words.all { literal(it) || base(it) in 0..9 }) {
            return words.joinToString("") { if (literal(it)) it else base(it).toString() }
        }
        if (!allowCardinal) return null
        val zeros = words.takeWhile { base(it) == 0 || literal(it) && it.all { c -> c == '0' } }
        if (zeros.isNotEmpty() && zeros.size < words.size) {
            val tail = integer(words.drop(zeros.size)) ?: return null
            return "0".repeat(zeros.sumOf { if (literal(it)) it.length else 1 }) + tail
        }
        return integer(words)?.toString()
    }

    fun normalize(text: String, protectedRanges: List<IntRange> = emptyList()): String {
        if (text.length > 100_000) return text
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return text
        val protected = protectedPattern.findAll(text).map { it.range }.toList()
            .plus(protectedRanges).sortedBy { it.first }

        val gaps = arrayOfNulls<Gap>(tokens.size)
        for (i in 1 until tokens.size) {
            gaps[i] = gapKind(text.substring(tokens[i - 1].range.last + 1, tokens[i].range.first))
        }
        fun key(i: Int) = tokens.getOrNull(i)?.key.orEmpty()
        fun gapAt(i: Int): Gap? = if (i in 1 until tokens.size) gaps[i] else null
        fun joinable(i: Int, mark: Boolean): Boolean = when (gapAt(i)) {
            Gap.PLAIN, Gap.HYPHEN -> true
            Gap.MARK -> mark
            else -> false
        }

        var protectedIndex = 0
        fun overlapsProtected(first: Int, last: Int): Boolean {
            while (protectedIndex < protected.size && protected[protectedIndex].last < first) protectedIndex++
            return (protected.getOrNull(protectedIndex)?.first ?: Int.MAX_VALUE) < last
        }

        val out = StringBuilder()
        var cursor = 0
        var i = 0

        fun mentionedComma(i: Int): Boolean =
            key(i - 1) in commaMentionBefore || key(i + 1) in commaMentionAfter ||
                (key(i - 1) in setOf("ли", "здесь") &&
                    (key(i - 2) in commaMentionBefore || key(i - 2) == "ли" && key(i - 3) in commaMentionBefore))

        /** A spoken "запятая" outside a number becomes punctuation, never a surviving word. */
        fun convertComma(t: Token): Boolean {
            val endWord = t.range.last + 1
            if (overlapsProtected(t.range.first, endWord)) return false
            if (text.getOrNull(t.range.first - 1)?.let { it.isLetterOrDigit() || it in "_/-" } == true) return false
            if (text.getOrNull(endWord)?.let { it.isLetterOrDigit() || it in "_/-" } == true) return false
            var left = t.range.first
            while (left > cursor && text[left - 1] in " \t\u00a0") left--
            out.append(text, cursor, left)
            val previous = out.lastOrNull()
            if (previous != null && previous !in ",.;:!?—–" && previous != ',') out.append(',')
            cursor = endWord
            var right = endWord
            while (right < text.length && text[right] in " \t\u00a0") right++
            if (text.getOrNull(right) == ',') cursor = right + 1
            return true
        }

        fun canExtend(end: Int): Boolean {
            val k = key(end)
            val previous = key(end - 1)
            // A formatted decimal is complete; a scale noun after a denominator is a unit
            // ("одна десятая миллиона"), not another numeric group.
            if (tokens[end - 1].text.any { it == '.' || it == ',' }) return false
            if (previous in denominator) return false
            val nextNumeric = end + 1 < tokens.size && (numericToken(key(end + 1)) || tokens[end + 1].literal)
            val markerEdge = k in decimalMarkers || previous in decimalMarkers || k == "дробь" || previous == "дробь"
            if (!joinable(end, markerEdge)) return false
            if (k in connectors) {
                val decimalish = previous in setOf("целых", "целая") || k == "and" ||
                    (end + 1 until minOf(tokens.size, end + 5)).any { key(it) in denominator }
                return decimalish && nextNumeric
            }
            if (k == "дробь") return nextNumeric
            if (!(numericToken(k) || tokens[end].literal)) return false
            if (k in decimalMarkers && !nextNumeric && key(end + 1) !in connectors) return false
            return true
        }

        fun simpleValue(part: List<String>): String? {
            if (part.isEmpty() || part.any { it in decimalMarkers || it in denominator || it in connectors }) return null
            if (part.all(::literal)) return part.joinToString("")
            val value = integer(part) ?: return null
            if (value !in 0L..MAX_DIGITS) return null
            return value.toString()
        }

        while (i < tokens.size) {
            val start = i
            val token = tokens[i]
            val tokenStart = token.range.first
            val tokenEnd = token.range.last + 1
            if (overlapsProtected(tokenStart, tokenEnd)) {
                i++
                continue
            }

            if (token.key == "запятая" && !mentionedComma(i)) {
                if (convertComma(token)) {
                    i++
                    continue
                }
            }

            val spokenPlus = token.key in plus && i + 1 < tokens.size && tokens[i + 1].literal &&
                (gapAt(i + 1) == Gap.PLAIN || gapAt(i + 1) == Gap.HYPHEN)
            if (token.literal || spokenPlus) {
                val from = if (spokenPlus) tokens[i + 1].range.first else when (text.getOrNull(tokenStart - 1)) {
                    '+', '(' -> tokenStart - 1
                    else -> tokenStart
                }
                val match = if (from >= 0) dialFormat.matchAt(text, from) else null
                val prefix = if (spokenPlus) tokenStart else from
                if (match != null && prefix >= cursor && !overlapsProtected(prefix, match.range.last + 1)) {
                    val left = text.getOrNull(prefix - 1)
                    val right = text.getOrNull(match.range.last + 1)
                    if (left?.isLetterOrDigit() != true && right?.isLetterOrDigit() != true &&
                        left !in listOf('/', '.', '-', '_') && right !in listOf('/', '-', '_')) {
                        out.append(text, cursor, prefix)
                        if (spokenPlus) out.append('+')
                        out.append(match.value.filter { it == '+' || it.isDigit() })
                        cursor = match.range.last + 1
                        while (i < tokens.size && tokens[i].range.first < cursor) i++
                        continue
                    }
                }
            }

            val positive = token.key in plus
            val signed = token.key in signs || positive
            val numberAt = if (signed) i + 1 else i
            if (numberAt >= tokens.size) {
                i = start + 1
                continue
            }
            if (!(numericToken(key(numberAt)) || tokens[numberAt].literal)) {
                i = start + 1
                continue
            }
            if (signed && (gapAt(numberAt) != Gap.PLAIN && gapAt(numberAt) != Gap.HYPHEN)) {
                i = start + 1
                continue
            }

            // Eleven digits split by ASR pauses: cross commas only for a complete 7/8 number.
            if (!signed || positive) {
                val prefixKey = key(numberAt)
                if (prefixKey in setOf("7", "8", "семь", "восемь", "seven", "eight")) {
                    val groups = mutableListOf<String>()
                    var j = numberAt
                    var paused = false
                    val digits = StringBuilder()
                    while (j < tokens.size && j - numberAt < 32 &&
                        (tokens[j].literal || base(key(j)) != null || key(j) == "hundred")) {
                        groups += key(j)
                        j++
                        if (j >= tokens.size || !(tokens[j].literal || base(key(j)) != null || key(j) == "hundred")) break
                        if (gaps[j] == Gap.PLAIN || gaps[j] == Gap.HYPHEN) continue
                        if (text.substring(tokens[j - 1].range.last + 1, tokens[j].range.first).trim() != ",") break
                        digits.append(digitRun(groups, true) ?: break)
                        groups.clear()
                        paused = true
                    }
                    val tail = if (paused && j - numberAt < 32) digitRun(groups, true) else null
                    if (tail != null && digits.append(tail).length == 11 && digits.first() in "78") {
                        val after = tokens[j - 1].range.last + 1
                        if (!overlapsProtected(tokenStart, after) &&
                            text.getOrNull(after)?.let { it.isLetterOrDigit() || it in "/_-" } != true &&
                            (tokenStart == 0 || text[tokenStart - 1] !in "@#/_-." && !text[tokenStart - 1].isLetterOrDigit())) {
                            out.append(text, cursor, tokenStart)
                            if (positive) out.append('+')
                            out.append(digits)
                            cursor = after
                            i = j
                            continue
                        }
                    }
                }
            }

            var end = numberAt + 1
            while (end < tokens.size && canExtend(end)) end++

            val words = (numberAt until end).map(::key)
            val previous = key(start - 1)
            val next = key(end)
            val connectedPrevious = gapAt(start) == Gap.PLAIN || gapAt(start) == Gap.HYPHEN
            val connectedNext = gapAt(end) == Gap.PLAIN || gapAt(end) == Gap.HYPHEN
            val address = connectedPrevious && previous in addressLabels &&
                (!connectedNext || next in addressLabels || next in setOf("корпуса", "литера", "дробь"))
            val dateContext = connectedNext && next in months || connectedPrevious && previous in months &&
                (previous !in setOf("may", "march") || ordinal(words) != null)
            val month = if (next in months) next else previous
            val forbiddenContext = previous in setOf("a", "an") && words.size == 1 && !dateContext
            val forced = address || connectedPrevious && previous in phoneCues
            val half = connectedNext && next == "с" && key(end + 1) == "половиной"

            var replacement: String? = null
            var consumeHalf = false
            // Already-written digits are never re-spelled. Only a signed literal or a
            // digit-by-digit run (0 5 2) is joined; dates, ranges and card numbers stay intact.
            val allLiteral = words.all(::literal)
            if (allLiteral) {
                replacement = when {
                    signed -> words.joinToString("")
                    words.size >= 2 && words.all { it.length == 1 } &&
                        (numberAt + 1 until end).all { gapAt(it) == Gap.PLAIN } -> words.joinToString("")
                    else -> null
                }
            }

            // Explicit spoken slash, never an ASR homophone guess.
            val slash = words.indexOf("дробь")
            if (!allLiteral && slash > 0 && slash < words.lastIndex) {
                val left = simpleValue(words.subList(0, slash))
                val right = simpleValue(words.subList(slash + 1, words.size))
                if (left != null && right != null) replacement = "$left/$right"
            }

            if (!allLiteral && replacement == null && words.count { it == "запятая" } >= 2 && words.size % 2 == 1 &&
                words.withIndex().all { (index, word) ->
                    if (index % 2 == 1) word == "запятая" else literal(word) || base(word) in 0..9
                }) {
                replacement = words.filterIndexed { index, _ -> index % 2 == 0 }
                    .joinToString(", ") { if (literal(it)) it else base(it).toString() }
            }

            if (!allLiteral && replacement == null) {
                val markers = words.withIndex().filter { it.value in decimalMarkers }.map { it.index }
                if (markers.size == 1) {
                    val at = markers.single()
                    val marker = words[at]
                    val separator = if (marker in setOf("точка", "point")) "." else ","
                    val wholeTokens = words.subList(0, at)
                    val whole = when {
                        wholeTokens.isEmpty() -> if (marker in setOf("целых", "целая")) "0" else null
                        wholeTokens.size == 1 && literal(wholeTokens[0]) -> wholeTokens[0]
                        else -> integer(wholeTokens)?.let { if (it in 0L..MAX_DIGITS) it.toString() else null }
                    }
                    var fraction = words.subList(at + 1, words.size)
                    if (fraction.firstOrNull() in connectors) fraction = fraction.drop(1)
                    val places = fraction.lastOrNull()?.let { denominator[it] }
                    val digits = when {
                        places != null -> {
                            val body = fraction.dropLast(1)
                            val value = if (body.isEmpty()) null else integer(body)
                            if (value != null && value < pow10(places)) value.toString().padStart(places, '0') else null
                        }
                        marker in setOf("целых", "целая") -> null // an unfinished "целых" is left alone
                        else -> fractionDigits(fraction, allowCardinal = marker != "point")
                    }
                    if (whole != null && digits != null && digits.length <= 12) replacement = "$whole$separator$digits"
                } else if (markers.isEmpty() && words.lastOrNull() in denominator) {
                    val places = denominator.getValue(words.last())
                    val body = words.dropLast(1)
                    val split = body.indexOfFirst { it in connectors }
                    val wholeTokens = if (split >= 0) body.subList(0, split) else emptyList()
                    val fractionTokens = if (split >= 0) body.subList(split + 1, body.size) else body
                    val whole = if (wholeTokens.isEmpty()) "0" else
                        integer(wholeTokens)?.let { if (it in 0L..MAX_DIGITS) it.toString() else null }
                    val fraction = if (fractionTokens.isEmpty()) null else integer(fractionTokens)
                    if (whole != null && fraction != null && fraction < pow10(places))
                        replacement = "$whole,${fraction.toString().padStart(places, '0')}"
                }
            }

            if (!allLiteral && replacement == null) {
                val value = ordinal(words)
                if (value != null) {
                    if (dateContext) {
                        if (validDay(value.first, month)) replacement = value.first.toString()
                    } else if (words.size > 1 || connectedNext && next in ordinalCues) {
                        replacement = value.first.toString() + (if (words.last() in ordinalRu) "-" else "") + value.second
                    }
                }
            }

            if (!allLiteral && replacement == null) {
                val value = integer(words)
                val namedMonthYear = connectedPrevious && previous in months && words.size > 1 &&
                    value != null && value in 1000..9999
                val idiomaticOne = value == 1L && !address && (
                    connectedNext && next in setOf("из", "of", "in") ||
                        connectedPrevious && previous == "дом" && connectedNext && next == "на")
                val pluralScale = words.size == 1 && words[0] in scalePlurals
                if (value != null) {
                    if (!pluralScale && !idiomaticOne && !forbiddenContext &&
                        (value <= MAX_DIGITS || forced) &&
                        (!dateContext || validDay(value, month) || namedMonthYear)) {
                        if (half) {
                            consumeHalf = true
                            replacement = "$value,5"
                        } else {
                            replacement = value.toString()
                        }
                    }
                } else if (!(connectedNext && (next in units || next in percents))) {
                    val digitCue = positive || connectedPrevious && previous in phoneCues
                    replacement = digitRun(words, digitCue)
                }
                if (replacement == null && words.count { it == "and" } == 1) {
                    val split = words.indexOf("and")
                    val a = integer(words.take(split))
                    val b = integer(words.drop(split + 1))
                    if (a != null && b != null) replacement = "$a and $b"
                }
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
            if (replacement != null && consumeHalf) {
                last = tokens[end + 1].range.last + 1
                end += 2
            } else if (replacement != null && connectedNext && next in percents) {
                replacement += "%"
                last = tokens[end].range.last + 1
                end++
            }
            val touchesProtected = overlapsProtected(first, last) ||
                first > 0 && (text[first - 1].isLetterOrDigit() || text[first - 1] in "/_.-") ||
                last < text.length && (text[last].isLetterOrDigit() || text[last] in "/_-")
            if (replacement != null && !touchesProtected) {
                if (signed) replacement = (if (positive) "+" else "-") + replacement
                out.append(text, cursor, first).append(replacement)
                cursor = last
            }
            i = end
        }
        return out.append(text, cursor, text.length).toString()
    }
}
