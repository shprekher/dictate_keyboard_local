/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.chatModelFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Exercises the production request pipeline with an inference boundary, not prompt substrings alone. */
class LocalCleanupPipelineTest {
    private val gec = LocalCleanupModel.GEC_ID

    @Test
    fun `GEC automatic cleanup filters before constructing the unchanged production prompt`() = runBlocking<Unit> {
        val output = LocalCleanupPipeline.complete("Uh, I, uh, think we should meet.", gec, true, "en") { prompt, prefix ->
            val expected = "I think we should meet."
            assertEquals(LocalCleanupText.prompt(expected, true, LocalCleanupModel.GEC_INSTRUCTION), prompt)
            assertEquals(LocalCleanupText.prefix(true, LocalCleanupModel.GEC_INSTRUCTION), prefix)
            "  I think we should meet.  "
        }
        assertEquals("I think we should meet.", output)
    }

    @Test
    fun `protected wording and paragraphs reach inference intact`() = runBlocking<Unit> {
        for (input in listOf("Use the UM model.", "He said \"uh\", not \"ah\".", "First.\n\nSecond.", "В целом получается неплохо.\n\nДовольно быстро.")) {
            val output = LocalCleanupPipeline.complete(input, gec, true) { prompt, _ ->
                assertEquals(LocalCleanupText.prompt(input, true, LocalCleanupModel.GEC_INSTRUCTION), prompt)
                input
            }
            assertEquals(input, output)
        }
    }

    @Test
    fun `filler-only and blank requests keep original text without running inference`() = runBlocking<Unit> {
        for (input in listOf("uh", "uh um", "Uh, um?", "uh;", "  ", "\n\n")) {
            assertEquals(input, LocalCleanupPipeline.complete(input, gec, true) { _, _ -> error("Must not infer an empty turn") })
        }
    }

    @Test
    fun `non-English language hint reaches the filter`() = runBlocking<Unit> {
        val input = "Um fünf Uhr."
        LocalCleanupPipeline.complete(input, gec, true, "de") { prompt, _ ->
            assertEquals(LocalCleanupText.prompt(input, true, LocalCleanupModel.GEC_INSTRUCTION), prompt)
            input
        }
    }

    @Test
    fun `manual automatic-formatting and spoken editing requests never reach GEC inference`() = runBlocking<Unit> {
        for (input in listOf(
            "Translate into Russian:\n\n${DictatePromptDefaults.REWORDING_BE_PRECISE}\n\nThe meeting is tomorrow at ten.",
            DictatePromptDefaults.buildAutoFormattingPrompt("English", "Hello new paragraph how are you question mark"),
            "Make this a bullet list: apples, pears, oranges",
            "",
        )) {
            assertFailsWith<UnsupportedOperationException> {
                LocalCleanupPipeline.complete(input, gec, false) { _, _ -> error("Unsupported task reached inference") }
            }
        }
    }

    @Test
    fun `public provider rejects unsupported edits before Android JNI or model loading`() = runBlocking<Unit> {
        assertFailsWith<UnsupportedOperationException> {
            LocalCleanupProvider.complete("Translate into Russian: The meeting is tomorrow.", gec, 1_000)
        }
    }

    @Test
    fun `public provider short-circuits filler-only input before Android JNI or model loading`() = runBlocking<Unit> {
        assertEquals("uh um", LocalCleanupProvider.complete("uh um", gec, 1_000, cleanupOnly = true))
    }

    @Test
    fun `refusing an edit does not switch the selected checkpoint or produce replacement text`() = runBlocking<Unit> {
        val account = ProviderAccount(ProviderRegistry.LOCAL_CLEANUP.id, transcriptionModel = gec)
        val original = "The meeting is tomorrow at ten."
        var field = original
        try {
            field = LocalCleanupPipeline.complete(
                "Translate into Russian: $original",
                chatModelFor(account, ProviderRegistry.LOCAL_CLEANUP), false,
            ) { _, _ -> "Translate into Russian:" }
        } catch (_: UnsupportedOperationException) {
            // The caller can show an error, but has no successful output to commit.
        }
        assertEquals(original, field)
        assertEquals(gec, chatModelFor(account, ProviderRegistry.LOCAL_CLEANUP))
    }

    @Test
    fun `all checkpoints reject retired editing requests but retain automatic cleanup`() = runBlocking<Unit> {
        for (model in listOf(LocalCleanupModel.FAST_ID, LocalCleanupModel.QUALITY_ID)) {
            val input = "Translate the literal word uh into Russian."
            assertFailsWith<UnsupportedOperationException> {
                LocalCleanupPipeline.complete(input, model, false) { _, _ -> error("Retired editing reached inference") }
            }
            LocalCleanupPipeline.complete("Um, hello.", model, true) { prompt, _ ->
                assertEquals(LocalCleanupText.prompt("Um, hello.", true), prompt)
                "Hello."
            }
        }
    }

    @Test
    fun `invalid generation is not accepted as a successful cleanup`() = runBlocking<Unit> {
        for (output in listOf("", "  ", "<think>hidden</think>Hello", "<|im_start|>Hello")) {
            assertFailsWith<IllegalStateException> {
                LocalCleanupPipeline.complete("Uh, hello.", gec, true) { _, _ -> output }
            }
        }
    }

    @Test
    fun `controller retains the unfiltered transcript on inference or validation failure`() = runBlocking<Unit> {
        val original = "Uh, I, uh, think we should meet."
        for (result in listOf("", "<think>hidden</think>Changed", "timeout")) {
            val kept = DictateController.cleanupOrKeep(original) {
                LocalCleanupPipeline.complete(original, gec, true) { _, _ ->
                    if (result == "timeout") throw TimeoutException("test")
                    result
                }
            }
            assertEquals(original, kept)
        }
    }

    @Test
    fun `controller skips unsupported automatic edits without overwriting cleaned text`() = runBlocking<Unit> {
        val cleaned = "В целом получается неплохо."
        val kept = DictateController.cleanupOrKeep(cleaned) {
            LocalCleanupProvider.complete(DictatePromptDefaults.buildAutoFormattingPrompt("Russian", cleaned), gec, 1_000)
        }
        assertEquals(cleaned, kept)
    }

    @Test
    fun `unknown models do not fall back to an installed checkpoint`() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> {
            LocalCleanupPipeline.complete("Hello", "unknown", true) { _, _ -> error("Unknown model reached inference") }
        }
    }

    @Test
    fun `cancellation propagates rather than becoming replacement text`() = runBlocking<Unit> {
        assertFailsWith<CancellationException> {
            LocalCleanupPipeline.complete("Hello", gec, true) { _, _ -> throw CancellationException("stop") }
        }
    }
}
