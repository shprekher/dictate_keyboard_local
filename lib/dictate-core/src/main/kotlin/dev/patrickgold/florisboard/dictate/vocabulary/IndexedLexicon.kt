/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun interface Lexicon { fun contains(word: String): Boolean }

/** Read-only, thread-safe binary search over a memory-mapped UTF-8 pool and 32-bit offsets. */
class IndexedLexicon(buffer: ByteBuffer) : Lexicon {
    private val data = buffer.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
    private val count: Int
    private val pool: Int
    init {
        require(data.remaining() >= 12 && data.getInt(0) == 0x44564c31) { "Invalid vocabulary header" }
        count = data.getInt(4)
        require(count in 1..5_000_000)
        pool = 8 + (count + 1) * 4
        require(pool <= data.limit())
        var previous = 0
        for (i in 0..count) {
            val offset = data.getInt(8 + i * 4)
            require(offset >= previous && offset <= data.limit() - pool)
            previous = offset
        }
        require(previous == data.limit() - pool)
    }
    override fun contains(word: String): Boolean {
        val bytes = fold(word).toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            val start = pool + data.getInt(8 + mid * 4)
            val end = pool + data.getInt(12 + mid * 4)
            var comparison = 0
            for (i in 0 until minOf(bytes.size, end - start)) {
                comparison = (bytes[i].toInt() and 255) - (data.get(start + i).toInt() and 255)
                if (comparison != 0) break
            }
            if (comparison == 0) comparison = bytes.size - (end - start)
            when {
                comparison == 0 -> return true
                comparison < 0 -> hi = mid - 1
                else -> lo = mid + 1
            }
        }
        return false
    }
}

data class SpellingCandidate(val word: String, val score: Double, val editDistance: Int, val phoneticMatch: Boolean)
data class VocabularyAnalysis(val text: String, val candidates: Map<String, List<SpellingCandidate>>)

/** Absence from a dictionary is NOT evidence of an error. Fuzzy results are candidates only.
 * Bounded to eight unique tokens and one-edit neighbors per utterance; no full lexicon scan.
 */
class VocabularyCorrector(
    private val lexicon: Lexicon,
    private val contextScore: (String?, String, String?) -> Double = { _, _, _ -> 0.0 },
    private val frequency: (String) -> Int = { 0 },
) {
    fun analyze(input: String, personal: PersonalVocabulary): VocabularyAnalysis {
        val corrected = personal.apply(input)
        val terms = personal.entries.flatMap { wordPattern.findAll(it.spelling).map { m -> fold(m.value) }.toList() }.toSet()
        val tokens = wordPattern.findAll(corrected).map { it.value }.toList()
        val context = tokens.groupingBy { fold(it) }.eachCount()
        val candidates = linkedMapOf<String, List<SpellingCandidate>>()
        for ((index, word) in tokens.withIndex()) {
            val key = fold(word)
            if (candidates.size >= 8) break
            if (key in terms || key in candidates || word.length !in 4..24 || word.any { !it.isLetter() } ||
                word.any { it.isUpperCase() } || lexicon.contains(key) || Slang.isProtected(key)) continue
            val alphabet = when {
                word.all { it in 'а'..'я' || it == 'ё' } -> "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
                word.all { it in 'a'..'z' } -> "abcdefghijklmnopqrstuvwxyz"
                else -> continue // No transliteration or cross-script substitutions.
            }
            val neighbors = mutableSetOf<String>()
            for (i in key.indices) {
                neighbors += key.removeRange(i, i + 1)
                if (i + 1 < key.length) neighbors += key.substring(0, i) + key[i + 1] + key[i] + key.substring(i + 2)
                for (letter in alphabet) neighbors += key.substring(0, i) + letter + key.substring(i + 1)
            }
            for (i in 0..key.length) for (letter in alphabet) neighbors += key.substring(0, i) + letter + key.substring(i)
            candidates[key] = neighbors.asSequence().filter { it != key && lexicon.contains(it) }
                .map {
                    val sameSound = phonetic(it) == phonetic(key)
                    val contextual = contextScore(tokens.getOrNull(index - 1)?.let(::fold), it, tokens.getOrNull(index + 1)?.let(::fold))
                        .takeIf { score -> score.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0
                    // Corpus frequency (when available) and repetition in THIS utterance are independent signals.
                    SpellingCandidate(it, 1.0 + (if (sameSound) 0.3 else 0.0) +
                        kotlin.math.ln(1.0 + frequency(it).coerceAtLeast(0)) * 0.02 +
                        (if ((context[it] ?: 0) > 0) 0.2 else 0.0) + contextual, 1, sameSound)
                }.sortedWith(compareByDescending<SpellingCandidate> { it.score }.thenBy { it.word }).take(5).toList()
        }
        return VocabularyAnalysis(corrected, candidates)
    }

    companion object {
        fun phonetic(word: String): String = fold(word).replace('ё', 'е').replace('й', 'и')
            .replace('з', 'с').replace('б', 'п').replace('д', 'т').replace('г', 'к').replace('в', 'ф')
            .replace("ph", "f").replace("ck", "k").replace(Regex("[aeiouаеёиоуыэюяьъ]"), "")
            .replace(Regex("(.)\\1+"), "$1")
    }
}

/** Open vocabulary remains protected even if it is absent here. These productive roots also prevent
 * suggestions for slang inflections without promoting them to standard dictionary errors.
 */
object Slang {
    private val roots = listOf("кринж", "вайб", "рофл", "имб", "чилл", "хайп", "краш", "флекс", "зашквар", "треш", "трэш")
    fun isProtected(word: String): Boolean = roots.any { fold(word).startsWith(it) }
}
