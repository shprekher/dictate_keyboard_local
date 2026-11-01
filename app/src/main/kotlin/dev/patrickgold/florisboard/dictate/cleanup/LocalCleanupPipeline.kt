/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

/** Request policy shared by every provider entry point, independently of Android/JNI. */
internal object LocalCleanupPipeline {
    suspend fun automatic(input: String, enabled: Boolean, cleanup: suspend () -> String): String =
        if (!enabled || input.none { it.isLetterOrDigit() }) input else cleanup()

    fun requireSupportedTask(modelId: String, cleanupOnly: Boolean) {
        checkNotNull(LocalCleanupModel.byIdOrNull(modelId)) { "The selected cleanup model is unavailable" }
        if (!cleanupOnly) {
            throw UnsupportedOperationException(
                "Manual rewording and spoken editing commands have been removed. Use automatic cleanup instead.",
            )
        }
    }

    suspend fun complete(
        input: String,
        modelId: String,
        cleanupOnly: Boolean,
        language: String? = null,
        instructionOverride: String? = null,
        infer: suspend (prompt: String, prefix: String) -> String,
    ): String {
        requireSupportedTask(modelId, cleanupOnly)
        if (input.isBlank()) return input
        val personalized = instructionOverride?.takeIf { it.isNotBlank() }
        val effectiveInput = if (personalized == null && LocalCleanupModel.needsHesitationPrefilter(modelId)) {
            LocalCleanupText.withoutHesitations(input, language)
        } else {
            input
        }
        // Do not ask a model to invent text for an empty turn or erase an existing selection.
        if (effectiveInput.none { it.isLetterOrDigit() }) return input
        val instruction = personalized ?: LocalCleanupPrompt.defaultFor(modelId)
        return LocalCleanupText.validatedOutput(infer(
            LocalCleanupText.prompt(effectiveInput, cleanupOnly, instruction),
            LocalCleanupText.cachePrefix(cleanupOnly, effectiveInput, instruction),
        ))
    }
}
