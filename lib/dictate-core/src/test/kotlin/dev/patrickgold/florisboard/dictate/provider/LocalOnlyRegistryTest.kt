/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LocalOnlyRegistryTest : FunSpec({
    test("only phone-side speech and text engines are registered") {
        ProviderRegistry.presets.map { it.id } shouldBe listOf("local", "local-cleanup")
        ProviderRegistry.presets.forEach {
            it.baseUrl shouldBe ""
            it.apiKeyUrl shouldBe null
            it.supportsDynamicModels shouldBe false
            it.realtimeApi shouldBe null
        }
    }
    test("legacy cloud ids cannot resolve to a usable preset") {
        listOf("cloud", "openai", "groq", "gemini", "ollama", "custom", "custom:legacy").forEach {
            ProviderRegistry.byId(it) shouldBe null
        }
    }
    test("cleanup models are separate from speech models and retain the quality default") {
        ProviderRegistry.LOCAL.transcriptionApi shouldBe TranscriptionApi.LOCAL_ONDEVICE
        ProviderRegistry.LOCAL.capabilities.transcription shouldBe true
        ProviderRegistry.LOCAL_CLEANUP.capabilities.transcription shouldBe false
        ProviderRegistry.LOCAL_CLEANUP.curatedChatModels shouldBe listOf(
            "qwen3-1.7b-q4-k-m-cleanup", "qwen3-0.6b-q4-k-m-cleanup",
            "qwen3.5-0.8b-gec-q4-0-cleanup",
        )
        ProviderRegistry.LOCAL_CLEANUP.defaultChatModel shouldBe "qwen3-1.7b-q4-k-m-cleanup"
    }
})
