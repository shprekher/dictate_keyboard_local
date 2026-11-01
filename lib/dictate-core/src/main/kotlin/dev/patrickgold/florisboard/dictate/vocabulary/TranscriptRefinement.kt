/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.vocabulary

import kotlinx.coroutines.CancellationException

/** ASR -> personal/candidate vocabulary -> existing Qwen -> numbers -> guard -> final.
 * Uncertain fuzzy candidates are not injected into the Qwen prompt: that could manufacture words.
 */
object TranscriptRefinement {
    suspend fun refine(
        raw: String,
        personal: PersonalVocabulary,
        corrector: VocabularyCorrector,
        normalizeNumbers: Boolean,
        cleanup: suspend (String) -> String,
    ): String {
        if (raw.length > 100_000) return raw
        val vocabulary = corrector.analyze(raw, personal)
        val source = vocabulary.text
        val proposed = try { cleanup(source) } catch (c: CancellationException) { throw c } catch (_: Exception) { source }
        val cleaned = if (MeaningGuard.accept(source, proposed) && personal.retainsTerms(source, proposed)) personal.apply(proposed) else source
        val normalized = if (normalizeNumbers) NumberNormalizer.normalize(cleaned, personal.protectedRanges(cleaned)) else cleaned
        // Independent final comparison against the same deterministic numeric grammar on the source.
        val expected = if (normalizeNumbers) NumberNormalizer.normalize(source, personal.protectedRanges(source)) else source
        return if (MeaningGuard.accept(expected, normalized)) normalized else expected
    }
}
