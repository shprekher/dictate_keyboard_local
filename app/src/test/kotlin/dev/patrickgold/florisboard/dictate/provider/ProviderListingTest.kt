/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderListingTest {
    private fun accountsOf(vararg accounts: ProviderAccount) =
        ProviderAccounts(accounts.associateBy { it.providerId })

    @Test
    fun `only the two local engines are listed on a fresh install`() {
        assertEquals(setOf("local", "local-cleanup"), ProviderListing.pinnedIds)
        val listed = ProviderRegistry.presets.filter {
            ProviderListing.isListed(it, ProviderAccounts.Empty, emptySet()) { false }
        }
        assertEquals(listOf("local", "local-cleanup"), listed.map { it.id })
    }

    @Test
    fun `a speech engine needs an installed batch or live model`() {
        val local = ProviderRegistry.LOCAL
        val accounts = accountsOf(ProviderAccount(local.id, realtimeModel = "kroko-de"))
        assertFalse(ProviderListing.isSetUp(local, accounts) { false })
        assertTrue(ProviderListing.isSetUp(local, accounts) { it == "kroko-de" })
        assertTrue(ProviderListing.isSetUp(local, ProviderAccounts.Empty) { it == local.defaultTranscriptionModel })
    }

    @Test
    fun `cleanup downloads cannot become speech choices`() {
        val cleanup = ProviderRegistry.LOCAL_CLEANUP
        assertTrue(ProviderListing.isSetUp(cleanup, ProviderAccounts.Empty) { it in cleanup.curatedChatModels })
        assertFalse(ProviderListing.isSetUp(cleanup, ProviderAccounts.Empty) { false })
        val choices = ProviderListing.transcriptionChoices(ProviderAccounts.Empty, "") { true }
        assertEquals(listOf("local"), choices.map { it.first })
    }

    @Test
    fun `saved remote accounts never reappear in the speech picker`() {
        val accounts = accountsOf(
            ProviderAccount("groq", apiKey = "obsolete"),
            ProviderAccount("custom:a", displayName = "Old server"),
        )
        val choices = ProviderListing.transcriptionChoices(accounts, "groq") { true }
        assertEquals(listOf("local"), choices.map { it.first })
    }

    @Test
    fun `a selected missing local model remains visible for repair`() {
        val local = ProviderRegistry.LOCAL
        assertFalse(ProviderListing.isPickable(local, ProviderAccounts.Empty, "") { false })
        assertTrue(ProviderListing.isPickable(local, ProviderAccounts.Empty, local.id) { false })
    }
}
