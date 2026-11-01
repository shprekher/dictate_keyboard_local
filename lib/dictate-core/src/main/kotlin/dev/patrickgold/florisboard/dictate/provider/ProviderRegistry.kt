/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.provider

/**
 * One data-residency region of a provider (issue #403).
 *
 * A region is not a base URL the user thought up: it is a published address of the same service, and the
 * provider decides which ones exist. That is why it is a list on the preset rather than a free text box —
 * a typo in a residency host does not fail loudly, it silently sends the audio to the wrong continent.
 *
 * [realtimeUrl] is the point of the whole type. Soniox runs a second host for streaming that is nowhere
 * derivable from the REST one the user picked, so a region that could only move the batch address would
 * leave live dictation talking to the default region while everything else moved — which is exactly the
 * failure this issue asked us not to build. Null where the provider has no streaming (OpenRouter).
 *
 * The region is stored as the account's own base URL, so nothing about persistence, the Wear bridge, the
 * importer or the connection test needed a new field to learn about.
 */
data class ProviderRegion(
    /** Stable key, mapped to a translated name in the settings UI. */
    val id: String,
    val baseUrl: String,
    val realtimeUrl: String? = null,
)

/**
 * A selectable provider option shown to the user.
 *
 * Base URLs are stable facts. Default model ids are conservative starting points only – the source
 * of truth is the live catalog via [LlmProvider.listModels] when [supportsDynamicModels] is true, so
 * users can freely pick any model the provider offers (important for OpenRouter's large catalog).
 *
 * NOTE: model ids must be re-verified against the provider when extending defaults – never guessed.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val capabilities: ProviderCapabilities,
    val supportsDynamicModels: Boolean,
    /**
     * The provider's own page for creating an API key — the deepest link that survives a sign-in, not
     * the dashboard root, because the last step is exactly the one someone without a key cannot guess.
     *
     * **Null means "this provider has no key page", never "we did not look it up"** (#410). Both places
     * that offer the page — the setup wizard's button and the key icon in the provider dialog's title
     * row — key off null alone, so a missing URL silently removes the way out of a dialog whose key
     * field is the thing being stared at. Null is right for Dictate Cloud, Ollama, the on-device
     * provider and custom endpoints: there is nowhere to send anyone.
     *
     * Checked against the live host, with the date, like every other fact in here.
     */
    val apiKeyUrl: String? = null,
    val defaultChatModel: String? = null,
    /**
     * The `reasoning_effort` [defaultChatModel] runs at while the user's own setting is Off — a fact
     * about that one model, measured, never a guess about what models in general can take.
     *
     * Off omits the field and leaves the effort to the provider, and for a reasoning model the provider's
     * default is rarely what a rewording wants: OpenAI's gpt-6-luna thinks at medium unless told
     * otherwise, which made a one-sentence correction twice as slow and multiplied its output tokens.
     * Applies only while that exact model is the one in use ([reasoningEffortWhenOff]); a model the user
     * picked runs at whatever their setting says.
     */
    val defaultChatReasoningEffort: String? = null,
    val defaultTranscriptionModel: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val isCustom: Boolean = false,
    /**
     * Curated, known-good model ids used by the model picker as an offline-capable starting set (and,
     * for transcription, to distinguish STT models since the `/models` catalog doesn't say which is
     * which). The live [LlmProvider.listModels] catalog is merged on top. Verify ids against the
     * provider when editing – never guessed.
     */
    val curatedChatModels: List<String> = emptyList(),
    val curatedTranscriptionModels: List<String> = emptyList(),
    /**
     * The audio containers this provider documents as accepted uploads (issue #322).
     *
     * **Empty means unknown, never "anything goes"** — the same rule [maxUploadBytes] follows, and for
     * the same reason: a provider that publishes no list is left to speak for itself, and an unknown
     * file is sent as it is rather than converted on a guess. A non-empty list is a promise the app
     * acts on: a container missing from it is transcoded before upload instead of being refused by the
     * provider after the bytes have already been paid for.
     *
     * Filled in only from the provider's own documentation, with the date it was read.
     */
    val acceptedAudioContainers: Set<AudioContainer> = emptySet(),
    /** Wire format of this provider's speech-to-text endpoint (OpenRouter differs – see [TranscriptionApi]). */
    val transcriptionApi: TranscriptionApi = TranscriptionApi.OPENAI_MULTIPART,
    /**
     * Real-time streaming transcription (issue #128). [supportsRealtime] gates whether the global
     * real-time mode applies to this provider; [realtimeApi] picks the WebSocket wire format;
     * [defaultRealtimeModel] is the streaming model used unless the user chooses another from
     * [curatedRealtimeModels]. Left off for batch-only providers (Groq, OpenRouter, …).
     */
    val supportsRealtime: Boolean = false,
    val realtimeApi: RealtimeApi? = null,
    val defaultRealtimeModel: String? = null,
    val curatedRealtimeModels: List<String> = emptyList(),
    /**
     * True for a built-in provider whose base URL is user-editable (issue #136): the editor shows a base
     * URL field pre-filled with [baseUrl], so e.g. Ollama can point at a LAN server instead of localhost.
     * Distinct from [isCustom] (a fully user-defined endpoint with its own name).
     *
     * A preset that also lists [regions] keeps this on — the account still carries its own base URL, and
     * every resolution site already reads it — but the editor offers the region list instead of a text
     * box, because there the set of valid addresses is known and short (#403).
     */
    val allowsCustomBaseUrl: Boolean = false,
    /**
     * The provider's data-residency regions (issue #403), the first being the one [baseUrl] points at.
     * Empty for everyone who serves the world from one address.
     */
    val regions: List<ProviderRegion> = emptyList(),
    /**
     * True where the provider keeps the data in the EU **by default**, in its own published words, so
     * the "EU" filter of the add-provider list can offer it. A provider that merely *offers* an EU
     * region says so through [regions] instead and is found by [servesFromEu] all the same.
     */
    val hostedInEu: Boolean = false,
) {
    /** Whether the audio and text can stay in the EU with this provider — by default, or by region. */
    val servesFromEu: Boolean
        get() = hostedInEu || regions.any { it.id == "eu" }

    /** The effort to send with [model] when the user's setting is Off; null leaves it to the provider. */
    fun reasoningEffortWhenOff(model: String): String? =
        defaultChatReasoningEffort?.takeIf { model == defaultChatModel }
}

/**
 * Catalog of built-in OpenAI-compatible providers plus a factory for user-defined custom endpoints.
 */
object ProviderRegistry {

    private val CHAT_ONLY = ProviderCapabilities(chat = true, transcription = false)
    private val CHAT_AND_STT = ProviderCapabilities(chat = true, transcription = true)
    private val STT_ONLY = ProviderCapabilities(chat = false, transcription = true)

    val LOCAL = ProviderPreset(
        id = "local",
        displayName = "On-device (offline)",
        baseUrl = "",
        capabilities = STT_ONLY,
        transcriptionApi = TranscriptionApi.LOCAL_ONDEVICE,
        supportsDynamicModels = false,
        apiKeyUrl = null,
        // Base is the recommended balance of accuracy/speed; tiny is offered for low-end devices.
        defaultTranscriptionModel = "whisper-base",
        curatedTranscriptionModels = listOf("whisper-base", "whisper-tiny"),
    )

    /** Phone-side text inference; handled by LocalCleanupProvider, never by the HTTP client. */
    val LOCAL_CLEANUP = ProviderPreset(
        id = "local-cleanup",
        displayName = "On-device cleanup (offline)",
        baseUrl = "",
        capabilities = CHAT_ONLY,
        supportsDynamicModels = false,
        defaultChatModel = "qwen3-1.7b-q4-k-m-cleanup",
        curatedChatModels = listOf(
            "qwen3-1.7b-q4-k-m-cleanup",
            "qwen3-0.6b-q4-k-m-cleanup",
            // Qwen3.5-0.8B GEC, added as an optional grammar/spelling checkpoint.
            "qwen3.5-0.8b-gec-q4-0-cleanup",
        ),
    )

    /** All built-in presets in display order. The custom option is added by the UI on top of these. */
    val presets: List<ProviderPreset> = listOf(LOCAL, LOCAL_CLEANUP)

    fun byId(id: String): ProviderPreset? = presets.firstOrNull { it.id == id }

    /**
     * The data-residency region [customBaseUrl] selects on [preset] (issue #403).
     *
     * A blank URL is the default region, because that is what every account stored before regions existed
     * and what a fresh one still stores. Null for a preset without regions, and for a URL that is none of
     * them — an account pointed somewhere by hand keeps going where it was pointed instead of being
     * quietly snapped onto a region it never chose.
     */
    fun regionOf(preset: ProviderPreset, customBaseUrl: String): ProviderRegion? {
        if (preset.regions.isEmpty()) return null
        if (customBaseUrl.isBlank()) return preset.regions.first()
        val wanted = customBaseUrl.trim().trimEnd('/')
        return preset.regions.firstOrNull { it.baseUrl.trimEnd('/').equals(wanted, ignoreCase = true) }
    }

    /**
     * The largest audio upload [providerId] accepts, or 0 when the provider does not document one.
     *
     * **0 means unknown, never unlimited.** Callers must treat it as "no figure to check against", not
     * as permission to send anything.
     *
     * Kept here rather than in the file-import screen (where it started) because the recording path
     * needs it too: 16 kHz mono WAV is 32 kB per second, so 25 MB is reached after 13 minutes
     * 39 seconds — which is what made a 14-minute dictation fail outright (#281).
     *
     * Figures read from each provider's own documentation on 2026-08-17:
     *  - OpenAI 25 MB.
     *  - Dictate Cloud 25 MB — but for its own reason, not a borrowed one. The figure used to be
     *    justified as "it proxies to OpenAI, so it inherits the ceiling", and that reasoning has
     *    stopped being true: the server now chooses between two providers per service and the app is
     *    deliberately never told which. What the number has to express is the **server's own
     *    promise**, `MAX_AUDIO_SECONDS` — ten minutes, which at 16 kHz mono WAV is about 19 MB. The
     *    25 MB stays because it is the ceiling the server would reject at, and rejecting is its job:
     *    it answers 413 with `CONTENT_SIZE_LIMIT`, which the app turns into an offer to keep the
     *    recording. A lower figure here would refuse locally what the server would have accepted.
     *  - Groq 25 MB on the free tier, 100 MB on the dev tier. The key does not say which tier it is
     *    on, so the lower one is assumed.
     *  - Gemini caps the whole request at 20 MB, and its audio travels **base64-inline**
     *    (`transcribeGeminiGenerateContent`), which inflates it by 4/3 — so the audio itself may not
     *    exceed about 15 MB. This is the one provider whose limit bites before the general packing
     *    threshold does. The Interactions path taken by the dedicated STT model (#292) documents the
     *    same 20 MB request ceiling and encodes the same way, so the figure covers both.
     *  - ElevenLabs 3 GB, Deepgram 2 GB, AssemblyAI 2.2 GB through the upload endpoint. Far beyond
     *    anything a keyboard produces; recorded so the number is not looked up twice.
     *  - SiliconFlow 50 MB (and one hour), from its transcription API reference.
     *  - Scaleway 25 MB for whisper-large-v3 on its serverless API (supported-models page, read
     *    2026-09-25), and asked the same day: the "MB" is a MiB. A 25,500,044-byte WAV transcribed; one
     *    of 26,214,444 bytes came back 400 "Maximum file size exceeded (…, value=25.000041961669922)". Its
     *    rate limit counts audio seconds, 1800 a minute once a payment method is on file; a single upload
     *    at this ceiling is about 820 seconds of 16 kHz WAV, so the size, not the rate, is met first.
     *  - OVHcloud 2048 MB or three hours per request with a key (speech-to-text guide, updated
     *    2026-05-11) — with Deepgram's, since a keyboard never gets near it. Unmeasured: the anonymous
     *    probe is capped at 10 MB, and the app never sends without a key.
     *  - OpenRouter 25 MB for a multipart upload, added 2026-09-04 while checking #321 — it was simply
     *    missing, which meant the file-import path never split anything for it and a shared recording
     *    went out whole to be refused. Its harder limit is not a size at all: a request gets about 60
     *    seconds of *processing* time, and that does not convert into an audio length honestly, because
     *    how much speech fits depends on the chosen model's speed rather than on anything we control.
     *  - Mistral and Soniox document a *duration* (3 hours, 300 minutes) but no size, so they stay 0.
     *    Do not translate a duration into bytes here: the encoding is not theirs to assume.
     *  - Azure 300 MB, from the MAI-Transcribe page's prerequisites (read 2026-09-09). The Fast
     *    Transcription API it travels allows 500 MB in general; the model's own page is the stricter
     *    of the two and is the one that governs a MAI request.
     *  - xAI 500 MB (speech-to-text guide, read 2026-09-29; a larger file is a 413). Taken as decimal
     *    megabytes, the lower of the two readings, since nobody has measured which one xAI means.
     */
    fun maxUploadBytes(providerId: String): Long = when (providerId) {
        "openai", "cloud", "groq", "openrouter", "scaleway" -> 25L * 1024 * 1024
        "gemini" -> 15L * 1024 * 1024
        "siliconflow" -> 50L * 1024 * 1024
        "azure" -> 300L * 1024 * 1024
        "xai" -> 500L * 1000 * 1000
        "elevenlabs" -> 3L * 1024 * 1024 * 1024
        "deepgram", "ovhcloud" -> 2L * 1024 * 1024 * 1024
        "assemblyai" -> 2252L * 1024 * 1024
        else -> 0L
    }

    /**
     * True for Google's dedicated speech-to-text models (`gemini-3.5-transcribe`, `-transcribe-live`,
     * issue #292) as opposed to the multimodal chat models that transcribe as a side job.
     *
     * The distinction is not cosmetic: a dedicated model is reached over the **Interactions API**
     * (`POST /v1beta/interactions`, its own request and response shape), not over `generateContent`,
     * and it cannot serve single-call multimodal (#130) at all, because that route posts audio to
     * `chat/completions`. Both callers ask this one question, so it is asked in one place.
     *
     * Matched by name rather than a list, so a later `gemini-4-transcribe` needs no app update — the
     * same bet the model picker's own heuristic already makes.
     */
    fun isGeminiTranscribeModel(model: String): Boolean =
        model.removePrefix("models/").lowercase().contains("transcribe")

    // A general "is this a speech-to-text model" check briefly lived here, so the single-call switch and
    // the rewording model could be decided from it. It is gone on purpose: guessing a model's abilities
    // from its name held for the ids we knew and misfired on the rest, and the wrong answer cost more
    // than the right one gave — it refused to fold the settings fields together and explained nothing
    // (#313). Which model can do what is now the user's call. The Gemini check above stays because it
    // answers a different question: which *endpoint* a request travels.

    /** Builds a preset for a user-defined OpenAI-compatible endpoint. */
    /**
     * [realtime] marks a server the user has told us speaks the OpenAI realtime protocol under
     * `/v1/realtime` (#249). Several self-hosted transcription servers do; there is no way to detect it
     * without connecting, so it is a switch in the editor rather than a guess.
     */
    fun custom(
        baseUrl: String,
        displayName: String = "Custom server",
        capabilities: ProviderCapabilities = CHAT_AND_STT,
        realtime: Boolean = false,
    ): ProviderPreset = ProviderPreset(
        id = "custom",
        displayName = displayName,
        baseUrl = baseUrl,
        capabilities = capabilities,
        supportsDynamicModels = true,
        isCustom = true,
        supportsRealtime = realtime,
        realtimeApi = if (realtime) RealtimeApi.OPENAI else null,
    )
}
