/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate

import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.chatModelFor
import dev.patrickgold.florisboard.dictate.provider.chatModelIsPresetDefault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatModelForTest {
    private val cleanup = ProviderRegistry.LOCAL_CLEANUP

    @Test
    fun `a selected cleanup checkpoint is used without changing the record`() {
        for (id in cleanup.curatedChatModels) {
            val account = ProviderAccount(cleanup.id, transcriptionModel = id)
            assertEquals(id, chatModelFor(account, cleanup))
            assertFalse(chatModelIsPresetDefault(account, cleanup))
            assertEquals(id, account.transcriptionModel)
        }
    }

    @Test
    fun `the former chat model slot is read without migrating the choice`() {
        val id = cleanup.curatedChatModels.last()
        val account = ProviderAccount(cleanup.id, chatModel = id)
        assertEquals(id, chatModelFor(account, cleanup))
        assertFalse(chatModelIsPresetDefault(account, cleanup))
        assertEquals("", account.transcriptionModel)
    }

    @Test
    fun `an explicit selection wins over the older slot`() {
        val account = ProviderAccount(cleanup.id, transcriptionModel = cleanup.curatedChatModels.last(), chatModel = cleanup.curatedChatModels.first())
        assertEquals(cleanup.curatedChatModels.last(), chatModelFor(account, cleanup))
    }

    @Test
    fun `the existing quality default is only used when no checkpoint was selected`() {
        val account = ProviderAccount(cleanup.id)
        assertEquals(cleanup.defaultChatModel, chatModelFor(account, cleanup))
        assertTrue(chatModelIsPresetDefault(account, cleanup))
        assertEquals("", account.transcriptionModel)
        assertEquals("", account.chatModel)
    }

    @Test
    fun `an unavailable checkpoint is not silently replaced by another model`() {
        val account = ProviderAccount(cleanup.id, transcriptionModel = "not-installed-or-unknown")
        assertEquals("not-installed-or-unknown", chatModelFor(account, cleanup))
        assertFalse(chatModelIsPresetDefault(account, cleanup))
    }
}
