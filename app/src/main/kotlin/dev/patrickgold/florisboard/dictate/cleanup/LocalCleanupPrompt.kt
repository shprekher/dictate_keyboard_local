/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults

object LocalCleanupPrompt {
    fun defaultFor(modelId: String): String =
        LocalCleanupModel.instructionFor(modelId) ?: LocalCleanupText.CLEANUP_INSTRUCTION

    fun customOverride(selection: Int, custom: String): String? =
        custom.takeIf { selection == DictatePromptDefaults.SELECTION_CUSTOM && it.isNotBlank() }

    fun resolve(modelId: String, selection: Int, custom: String): String =
        customOverride(selection, custom) ?: defaultFor(modelId)
}
