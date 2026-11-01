/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

import java.util.Locale

/** Local edits only; never normalize unrelated text or cross literal/word boundaries. */
internal object EnglishHesitationFilter {
    private val sounds = Regex(
        """(?<![^\s,])(?:[uU]h|[uU]m)(?=$|[\s,.;!?])""",
    )

    fun apply(input: String, language: String? = null): String {
        val code = language?.substringBefore('-')?.lowercase(Locale.ROOT).orEmpty()
        if (code.isNotEmpty() && code != "detect" && code != "en") return input
        // Do not delete Latin tokens from mixed-script dictation.
        if (input.codePoints().anyMatch { Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN }) {
            return input
        }
        val quoted = quotedPositions(input)
        val removals = mutableListOf<IntRange>()
        for (match in sounds.findAll(input)) {
            if (quoted[match.range.first]) continue
            var start = match.range.first
            var end = match.range.last + 1
            // A dot attached to the next word is a domain/filename, not a filler delimiter.
            if (input.getOrNull(end) == '.' && input.getOrNull(end + 1)?.isLetterOrDigit() == true) continue
            var before = start
            while (before > 0 && input[before - 1].isHorizontalSpace()) before--
            var after = end
            while (after < input.length && input[after].isHorizontalSpace()) after++
            // Preserve measurements such as "20 um" and capitalized names such as "Um Smith".
            if (input.getOrNull(before - 1)?.isDigit() == true || input.getOrNull(after)?.isDigit() == true) continue
            val sentenceStart = before == 0 || input[before - 1] in "\r\n.!?"
            if (match.value.first().isUpperCase() &&
                (!sentenceStart || (end < input.length && input[end] !in ",.!?"))
            ) {
                continue
            }
            // Punctuation attached to a preceding quoted literal belongs to that literal.
            if (before > 0 && input[before - 1] == ',' && (before < 2 || !quoted[before - 2])) start = before - 1
            if (input.getOrNull(end) == ',') end++
            if (sentenceStart && input.getOrNull(end) in listOf('.', '!', '?') &&
                (end + 1 == input.length || input[end + 1].isWhitespace())
            ) {
                end++
            }
            while (end < input.length && input[end].isHorizontalSpace()) end++
            if (end == input.length || input[end] in "\r\n.,;:!?") start = minOf(start, before)
            val previous = removals.lastOrNull()
            if (previous != null && start <= previous.last + 1) {
                removals[removals.lastIndex] = previous.first until maxOf(previous.last + 1, end)
            } else {
                removals += start until end
            }
        }
        if (removals.isEmpty()) return input
        return buildString {
            var offset = 0
            for (range in removals) {
                append(input, offset, range.first)
                val end = range.last + 1
                if (range.first > 0 && end < input.length && !input[range.first - 1].isWhitespace() &&
                    !input[end].isWhitespace() && input[end] !in ".,;:!?"
                ) {
                    append(' ')
                }
                offset = end
            }
            append(input, offset, input.length)
        }
    }

    private fun Char.isHorizontalSpace() = this == ' ' || this == '\t'

    private fun quotedPositions(input: String): BooleanArray {
        val quoted = BooleanArray(input.length)
        var closing: Char? = null
        var escaped = false
        for (i in input.indices) {
            val c = input[i]
            if (closing != null) {
                quoted[i] = true
                val apostrophe = c in "'’" && input.getOrNull(i - 1)?.isLetterOrDigit() == true &&
                    input.getOrNull(i + 1)?.isLetterOrDigit() == true
                if (!escaped && c == closing && !apostrophe) closing = null
            } else if (!escaped) {
                closing = when (c) {
                    '"' -> '"'
                    '`' -> '`'
                    '\'' -> if (input.getOrNull(i - 1)?.isLetterOrDigit() != true) '\'' else null
                    '‘' -> '’'
                    '“' -> '”'
                    '„' -> '“'
                    '«' -> '»'
                    else -> null
                }
                quoted[i] = closing != null
            }
            escaped = c == '\\' && !escaped
        }
        return quoted
    }
}
