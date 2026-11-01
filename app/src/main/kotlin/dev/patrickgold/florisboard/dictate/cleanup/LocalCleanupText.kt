/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

/** Pure text helpers, testable independently of Android and the native model. */
object LocalCleanupText {
    val CLEANUP_INSTRUCTION = """
        Clean up this English/Russian speech transcript.

        Remove hesitation sounds, meaningless fillers, stutters, repeated words, and false starts. Preserve meaningful expressions.

        Correct spelling, grammar, punctuation, and capitalization. Preserve meaning, names, numbers, negation, tone, and original language.

        Do not add information, translate, or answer questions. If uncertain, keep the original wording.

        Return only the cleaned transcript.
    """.trimIndent()
    const val EDIT_INSTRUCTION = "Follow the user's text editing request. Preserve the source language " +
        "unless translation is explicitly requested. Return only the edited text, without explanations, " +
        "labels or thinking."

    fun withoutHesitations(input: String, language: String? = null): String =
        EnglishHesitationFilter.apply(input, language)

    private fun instructionText(cleanupOnly: Boolean, override: String?): String =
        if (cleanupOnly) override?.takeIf { it.isNotBlank() } ?: CLEANUP_INSTRUCTION else EDIT_INSTRUCTION

    /** This prefix contains no user text and is the only part eligible for resident KV reuse. */
    fun prefix(cleanupOnly: Boolean, override: String? = null): String {
        val instruction = instructionText(cleanupOnly, override)
        return "<|im_start|>system\n$instruction<|im_end|>\n<|im_start|>user\n"
    }

    // Keep arbitrary editing requests on their existing full-prompt evaluation path. The first token
    // after the user marker can absorb leading whitespace or punctuation; use the full prompt then so
    // tokenizer boundaries cannot change the result.
    fun cachePrefix(cleanupOnly: Boolean, input: String, override: String? = null): String =
        if (cleanupOnly && input.firstOrNull()?.isLetterOrDigit() == true) prefix(true, override) else ""

    fun prompt(input: String, cleanupOnly: Boolean, override: String? = null): String {
        // Prevent text from injecting ChatML role delimiters into the conversation.
        val safe = input.replace("<|", "< |")
        // Qwen3's documented non-thinking prefix; no hidden reasoning budget for simple editing.
        return prefix(cleanupOnly, override) + "$safe<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }

    fun validatedOutput(output: String): String {
        val text = output.trim()
        check(text.isNotBlank() && "<think>" !in text && "</think>" !in text && "<|" !in text) {
            "The local cleanup model returned empty text or internal markers; keep the original transcript"
        }
        return text
    }
}
