/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

import java.text.Normalizer
import java.util.Locale

internal fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)
internal val wordPattern = Regex("[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*|[0-9]+")

data class VocabularyEntry(val spelling: String, val alternatives: List<String> = emptyList())

/** Explicit substitutions, not fuzzy ASR biasing. Blank lines are ignored. No model retraining.
 * Format: preferred spelling | heard alternative ; another alternative
 * Inspired by Hex's term/sounds_like UX; implemented independently (no Hex code copied).
 */
class PersonalVocabulary(val entries: List<VocabularyEntry>) {
    init {
        require(entries.size <= 500) { "At most 500 entries are supported" }
        val owners = mutableMapOf<String, String>()
        for (entry in entries) {
            require(entry.alternatives.size <= 16) { "At most 16 alternatives per entry" }
            for (value in listOf(entry.spelling) + entry.alternatives) {
                require(value.isNotBlank() && value.length <= 96 && wordPattern.findAll(value).count() <= 8) {
                    "Each expression must contain 1–96 characters and at most 8 words"
                }
                require(wordPattern.findAll(value).joinToString(" ") { it.value } == value) {
                    "Use words separated by single spaces (hyphens and apostrophes are allowed)"
                }
                // Prevent an explicit alias from silently changing a quantity or a negation.
                require(MeaningGuard.anchors(value) == MeaningGuard.anchors(entry.spelling)) {
                    "Alternatives must preserve numbers and negations"
                }
                val key = fold(value)
                require(owners[key] == null || owners[key] == entry.spelling) { "Conflicting alternative: $value" }
                owners[key] = entry.spelling
            }
        }
    }

    private val substitutions by lazy {
        entries.flatMap { e -> (e.alternatives + e.spelling).map { it to e.spelling } }
            .associate { fold(it.first) to it.second }
    }
    private val maxWords = entries.flatMap { it.alternatives + it.spelling }
        .maxOfOrNull { wordPattern.findAll(it).count() } ?: 0
    private val preferred = entries.map { fold(it.spelling) }.toSet()

    /** Explicit terms are protected even when they look like fillers, numbers or informal grammar. */
    fun protectedRanges(input: String): List<IntRange> {
        if (preferred.isEmpty()) return emptyList()
        val tokens = wordPattern.findAll(input).toList()
        val ranges = mutableListOf<IntRange>()
        for (i in tokens.indices) for (count in 1..minOf(maxWords, tokens.size - i)) {
            val range = tokens[i].range.first..tokens[i + count - 1].range.last
            if (fold(input.substring(range)) in preferred) ranges += range
        }
        return ranges
    }

    fun retainsTerms(before: String, after: String): Boolean {
        fun counts(text: String) = protectedRanges(text).map { fold(text.substring(it)) }.groupingBy { it }.eachCount()
        val expected = counts(before)
        val actual = counts(after)
        return expected.all { (term, n) -> (actual[term] ?: 0) >= n }
    }

    /** One pass over source spans: longest exact alias first, never cascading replacements. */
    fun apply(input: String): String {
        val tokens = wordPattern.findAll(input).toList()
        val out = StringBuilder()
        var cursor = 0
        var i = 0
        while (i < tokens.size) {
            var matched = false
            for (count in maxWords downTo 1) {
                if (i + count > tokens.size) continue
                val end = tokens[i + count - 1].range.last + 1
                val source = input.substring(tokens[i].range.first, end)
                val preferred = substitutions[fold(source)] ?: continue
                // Do not edit identifiers, URLs, hashtags, e-mail or compounds piecemeal.
                val start = tokens[i].range.first
                if (start > 0 && input[start - 1] in "@/#._-" || end < input.length && input[end] in "@/_-" ||
                    end + 1 < input.length && input[end] == '.' && input[end + 1].isLetterOrDigit()) continue
                out.append(input, cursor, start).append(preferred)
                cursor = end
                i += count
                matched = true
                break
            }
            if (!matched) i++
        }
        return out.append(input, cursor, input.length).toString()
    }

    companion object {
        fun parse(raw: String): PersonalVocabulary {
            require(raw.length <= 128 * 1024) { "Vocabulary is too large" }
            val entries = raw.lineSequence().filter { it.isNotBlank() }.map { line ->
                val parts = line.split('|')
                require(parts.size <= 2) { "Use one | between spelling and alternatives" }
                VocabularyEntry(parts[0].trim(), parts.getOrNull(1)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty())
            }.toList()
            require(entries.map { fold(it.spelling) }.distinct().size == entries.size) { "Duplicate preferred spelling" }
            return PersonalVocabulary(entries)
        }
    }
}
