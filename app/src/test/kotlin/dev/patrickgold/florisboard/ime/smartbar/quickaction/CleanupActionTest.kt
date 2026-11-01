/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanupActionTest {
    @Test
    fun `stored live editing actions are retired without dropping unrelated actions`() {
        val live = QuickAction.InsertKey(TextKeyData.DICTATE_LIVE_PROMPT)
        val cleanup = QuickAction.InsertKey(TextKeyData.DICTATE_PROMPTS)
        val history = QuickAction.InsertKey(TextKeyData.DICTATE_REINSERT)
        val raw = QuickActionArrangement(live, listOf(history, live, cleanup), listOf(live))
        val decoded = QuickActionArrangement.Serializer.deserialize(QuickActionArrangement.Serializer.serialize(raw))
        assertFalse(live in decoded)
        assertTrue(history in decoded)
        assertTrue(cleanup in decoded)
        assertEquals(raw.dynamicActions, listOf(history, live, cleanup))
    }

    @Test
    fun `cleanup settings action remains in the default arrangement`() {
        assertTrue(QuickAction.InsertKey(TextKeyData.DICTATE_PROMPTS) in QuickActionArrangement.Default)
        assertFalse(QuickAction.InsertKey(TextKeyData.DICTATE_LIVE_PROMPT) in QuickActionArrangement.Default)
    }
}
