/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.app.setup

import dev.patrickgold.florisboard.dictate.provider.ProviderAccount
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderConfiguredTest {
    private fun accountsOf(vararg accounts: ProviderAccount) =
        ProviderAccounts(accounts.associateBy { it.providerId })

    @Test
    fun `setup requires a downloaded speech model`() {
        val local = ProviderRegistry.LOCAL
        assertFalse(isProviderConfigured(ProviderAccounts.Empty, local.id) { false })
        assertTrue(isProviderConfigured(ProviderAccounts.Empty, local.id) { it == local.defaultTranscriptionModel })
        val accounts = accountsOf(ProviderAccount(local.id, transcriptionModel = "whisper-small"))
        assertFalse(isProviderConfigured(accounts, local.id) { it == local.defaultTranscriptionModel })
        assertTrue(isProviderConfigured(accounts, local.id) { it == "whisper-small" })
    }

    @Test
    fun `an installed live model also supports batch entry points`() {
        val accounts = accountsOf(ProviderAccount("local", realtimeModel = "kroko-de"))
        assertTrue(isProviderConfigured(accounts, "local") { it == "kroko-de" })
    }

    @Test
    fun `old cloud credentials and custom servers do not satisfy setup`() {
        val accounts = accountsOf(
            ProviderAccount("groq", apiKey = "obsolete"),
            ProviderAccount("custom:a", customBaseUrl = "http://old-server/v1/"),
        )
        assertFalse(isProviderConfigured(accounts, "groq") { false })
        assertTrue(isProviderConfigured(accounts, "groq") { it == ProviderRegistry.LOCAL.defaultTranscriptionModel })
    }
}
