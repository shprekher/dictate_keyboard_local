/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.dictate.cleanup.VocabularyResources
import dev.patrickgold.florisboard.dictate.vocabulary.PersonalVocabulary
import dev.patrickgold.florisboard.dictate.vocabulary.TranscriptRefinement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly invoked by the user; never runs speech, changes models or logs personal transcripts.
 * Measures Kotlin overhead, NOT Qwen/ASR inference or end-to-end speech accuracy.
 */
@RunWith(AndroidJUnit4::class)
class VocabularyBenchmarkTest {
    @Test fun offlineVocabularyOverhead() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = System.nanoTime()
        val corrector = VocabularyResources.get(context)
        val acquireMs = (System.nanoTime() - start) / 1e6
        val personal = PersonalVocabulary.parse("OpenCode | опен код")
        val text = "опен код не меняет кринжовый вайб за пять рублей"
        repeat(50) { TranscriptRefinement.refine(text, personal, corrector, true) { it } }
        val samples = (1..500).map {
            val begin = System.nanoTime()
            val output = TranscriptRefinement.refine(text, personal, corrector, true) { it }
            assertEquals("OpenCode не меняет кринжовый вайб за 5 рублей", output)
            (System.nanoTime() - begin) / 1e6
        }.sorted()
        Log.i("VocabularyBenchmark", "acquireMs=$acquireMs p50Ms=${samples[249]} p95Ms=${samples[474]} maxMs=${samples.last()} n=500 inference=false")
    }
}
