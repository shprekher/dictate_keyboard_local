/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalCleanupModelTest {
    @Test
    fun `only instruction-following checkpoints support editing`() {
        assertFalse(LocalCleanupModel.supportsEditing(LocalCleanupModel.GEC_ID))
        assertFalse(LocalCleanupModel.supportsEditing(null))
        assertFalse(LocalCleanupModel.supportsEditing("unknown"))
        assertTrue(LocalCleanupModel.supportsEditing(LocalCleanupModel.QUALITY_ID))
        assertTrue(LocalCleanupModel.supportsEditing(LocalCleanupModel.FAST_ID))
    }

    @Test
    fun `GEC checkpoint is pinned and uses its trained instruction`() {
        val spec = LocalCleanupModel.GEC
        val file = spec.files.single()
        assertEquals(LocalCleanupModel.GEC_ID, spec.id)
        assertEquals(563_035_616, file.sizeBytes)
        assertEquals("893f573db88f587e823b66847e06049cdb3029eff6177d92f6577179f7178871", file.sha256)
        assertEquals(
            "https://huggingface.co/loqira/Qwen3.5-0.8B-GEC-KAZ-RUS-ENG/resolve/" +
                "ced469768d40dffc899fe459efb66a4a07aeabee/Qwen3.5-0.8B-GEC-KAZ-RUS-ENG.Q4_0.gguf",
            file.url,
        )
        assertEquals(LocalCleanupModel.GEC_INSTRUCTION, LocalCleanupModel.instructionFor(spec.id))
        assertEquals(listOf("en", "ru", "kk"), spec.languages)
        assertEquals(true, LocalCleanupModel.needsHesitationPrefilter(spec.id))
        assertEquals(false, LocalCleanupModel.needsHesitationPrefilter(LocalCleanupModel.QUALITY_ID))
        assertEquals(false, LocalCleanupModel.needsHesitationPrefilter(LocalCleanupModel.FAST_ID))
    }

    @Test
    fun `adding GEC does not change the existing cleanup default or prompts`() {
        assertEquals(LocalCleanupModel.QUALITY_ID, LocalCleanupModel.active("").id)
        assertEquals(LocalCleanupModel.QUALITY_ID, LocalCleanupModel.active("unknown").id)
        assertNull(LocalCleanupModel.instructionFor(LocalCleanupModel.QUALITY_ID))
        assertNull(LocalCleanupModel.instructionFor(LocalCleanupModel.FAST_ID))
        assertEquals(
            listOf(LocalCleanupModel.QUALITY_ID, LocalCleanupModel.FAST_ID, LocalCleanupModel.GEC_ID),
            LocalCleanupModel.specs.map { it.id },
        )
    }
}
