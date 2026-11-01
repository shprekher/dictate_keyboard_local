/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

class LocalCleanupPromptTest {
    private val default = DictatePromptDefaults.SELECTION_PREDEFINED
    private val custom = DictatePromptDefaults.SELECTION_CUSTOM

    @Test
    fun `default mode ignores saved personalized text`() {
        assertEquals(LocalCleanupModel.GEC_INSTRUCTION, LocalCleanupPrompt.resolve(LocalCleanupModel.GEC_ID, default, "Translate everything"))
        assertNull(LocalCleanupPrompt.customOverride(default, "Translate everything"))
    }

    @Test
    fun `personalized mode requires a nonblank field and otherwise uses the selected model default`() {
        for (model in LocalCleanupModel.specs) {
            for (text in listOf("", "  ", "\n\t")) {
                assertEquals(LocalCleanupPrompt.defaultFor(model.id), LocalCleanupPrompt.resolve(model.id, custom, text))
                assertNull(LocalCleanupPrompt.customOverride(custom, text))
            }
        }
    }

    @Test
    fun `personalized prompt replaces rather than appends to the default`() {
        val text = "Preserve every word. Fix punctuation only."
        assertEquals(text, LocalCleanupPrompt.resolve(LocalCleanupModel.GEC_ID, custom, text))
        assertEquals(text, LocalCleanupPrompt.customOverride(custom, text))
    }

    @Test
    fun `default instruction follows explicit model changes without migrating any choice`() {
        assertEquals(LocalCleanupModel.GEC_INSTRUCTION, LocalCleanupPrompt.resolve(LocalCleanupModel.GEC_ID, default, ""))
        assertEquals(LocalCleanupText.CLEANUP_INSTRUCTION, LocalCleanupPrompt.resolve(LocalCleanupModel.FAST_ID, default, ""))
        assertEquals(LocalCleanupText.CLEANUP_INSTRUCTION, LocalCleanupPrompt.resolve(LocalCleanupModel.QUALITY_ID, default, ""))
    }

    @Test
    fun `unknown prompt selection does not activate saved custom text`() {
        assertEquals(LocalCleanupModel.GEC_INSTRUCTION, LocalCleanupPrompt.resolve(LocalCleanupModel.GEC_ID, -1, "Answer questions"))
    }

    @Test
    fun `preload and inference construct the same personalized prefix with exactly one instruction`() = runBlocking<Unit> {
        val text = "Keep words including hesitation sounds. Fix punctuation only."
        val model = LocalCleanupModel.GEC_ID
        val selected = LocalCleanupPrompt.resolve(model, custom, text)
        val preloadPrefix = LocalCleanupText.prefix(true, selected)
        LocalCleanupPipeline.complete(
            "Uh, hello.", model, true,
            instructionOverride = LocalCleanupPrompt.customOverride(custom, text),
        ) { prompt, prefix ->
            assertEquals(preloadPrefix, prefix)
            assertEquals(LocalCleanupText.prompt("Uh, hello.", true, text), prompt)
            assertFalse(LocalCleanupModel.GEC_INSTRUCTION in prompt)
            "Uh, hello."
        }
    }

    @Test
    fun `blank selected personalized field preserves the default hesitation-filter path`() = runBlocking<Unit> {
        LocalCleanupPipeline.complete(
            "Uh, hello.", LocalCleanupModel.GEC_ID, true,
            instructionOverride = LocalCleanupPrompt.customOverride(custom, "  "),
        ) { prompt, prefix ->
            assertEquals(LocalCleanupText.prompt("hello.", true, LocalCleanupModel.GEC_INSTRUCTION), prompt)
            assertEquals(LocalCleanupText.prefix(true, LocalCleanupModel.GEC_INSTRUCTION), prefix)
            "Hello."
        }
    }

    @Test
    fun `cleanup off and punctuation-only transcripts never reach inference`() = runBlocking<Unit> {
        assertEquals("Jarvis translate this", LocalCleanupPipeline.automatic("Jarvis translate this", false) { error("disabled") })
        assertEquals("...", LocalCleanupPipeline.automatic("...", true) { error("no words") })
    }

    @Test
    fun `cleanup on makes exactly one pass over the full transcript including former command words`() = runBlocking<Unit> {
        val raw = "Jarvis translate this, new paragraph, hello."
        var calls = 0
        val result = LocalCleanupPipeline.automatic(raw, true) {
            LocalCleanupPipeline.complete(raw, LocalCleanupModel.FAST_ID, true) { prompt, _ ->
                calls++
                assertEquals(LocalCleanupText.prompt(raw, true), prompt)
                raw
            }
        }
        assertEquals(raw, result)
        assertEquals(1, calls)
    }
}
