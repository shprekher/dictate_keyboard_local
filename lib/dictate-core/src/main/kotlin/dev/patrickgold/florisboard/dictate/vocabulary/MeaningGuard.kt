/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

/** A fail-closed lexical guard, not a claim of semantic understanding. Qwen may punctuate/capitalize
 * and remove known hesitation sounds or immediate repetitions, but may not introduce/reorder words,
 * formalize slang, substitute names, remove negations or change quantities. Reject -> pre-Qwen text.
 */
object MeaningGuard {
    private val negations = setOf("no", "not", "never", "не", "нет", "ни", "нельзя", "без", "without", "don't", "isn't", "can't", "won't")
    private val hesitation = setOf("uh", "um", "erm", "э", "ээ", "эм", "мм")
    private fun words(text: String) = wordPattern.findAll(text).map { fold(it.value) }.toList()
    fun anchors(text: String): List<String> = words(text).filter {
        it in negations || it.any(Char::isDigit) || NumberNormalizer.isNumberWord(it)
    }

    fun accept(before: String, after: String): Boolean {
        if (after.isBlank() || after.length > before.length * 2 + 64 || "<|" in after) return false
        // Compare before normalization: the model is not authorized to spell out or recalculate numbers.
        if (anchors(before) != anchors(after)) return false
        val beforeCounts = words(before).groupingBy { it }.eachCount()
        if (words(after).groupingBy { it }.eachCount().any { (word, count) -> count > (beforeCounts[word] ?: 0) }) return false
        fun canonical(text: String): List<String> {
            val result = mutableListOf<String>()
            for (word in words(text)) {
                if (word in hesitation) continue
                if (result.lastOrNull() != word || word in negations || NumberNormalizer.isNumberWord(word)) result += word
            }
            return result
        }
        if (canonical(before) != canonical(after)) return false
        // US/us and iPhone/Iphone are not interchangeable capitalization choices.
        val names = wordPattern.findAll(before).map { it.value }.filter { token ->
            token.length > 1 && token.drop(1).any(Char::isUpperCase)
        }.groupingBy { it }.eachCount()
        val afterWords = wordPattern.findAll(after).map { it.value }.groupingBy { it }.eachCount()
        if (names.any { (name, count) -> (afterWords[name] ?: 0) < count }) return false
        // Numeric punctuation, currency, signed values and percent markers must remain byte-for-byte.
        val numeric = Regex("[+−-]?[0-9]+(?:[.,:/-][0-9]+)*[%₽$€£]?")
        if (numeric.findAll(before).map { it.value }.toList() != numeric.findAll(after).map { it.value }.toList()) return false
        val symbols = Regex("[%₽$€£]")
        return symbols.findAll(before).map { it.value }.toList() == symbols.findAll(after).map { it.value }.toList()
    }
}
