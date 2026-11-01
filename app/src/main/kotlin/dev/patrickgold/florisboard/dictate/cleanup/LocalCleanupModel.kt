/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

import android.content.Context
import android.os.Build
import dev.patrickgold.florisboard.dictate.provider.LocalModelFile
import dev.patrickgold.florisboard.dictate.provider.LocalModelSpec
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.ModelCredit
import java.io.File

/**
 * The text models offered for on-device cleanup. Deliberately outside the speech-recognition catalog:
 * these entries never appear in the transcription picker.
 *
 * [QUALITY] is the original Qwen3 1.7B checkpoint. [FAST] is Qwen3 0.6B, the same family and prompt at
 * roughly a third of the download and well under half the per-transcript latency; the quality model is
 * kept for difficult transcripts where the extra capacity is worth the wait.
 */
object LocalCleanupModel {
    const val QUALITY_ID = "qwen3-1.7b-q4-k-m-cleanup"
    const val FAST_ID = "qwen3-0.6b-q4-k-m-cleanup"
    const val GEC_ID = "qwen3.5-0.8b-gec-q4-0-cleanup"

    private const val QUALITY_CREDIT_URL = "https://huggingface.co/Qwen/Qwen3-1.7B"
    private const val FAST_CREDIT_URL = "https://huggingface.co/Qwen/Qwen3-0.6B"
    private const val GEC_CREDIT_URL = "https://huggingface.co/loqira/Qwen3.5-0.8B-GEC-KAZ-RUS-ENG"

    /** Publisher's trained GEC instruction, used by default unless the user explicitly overrides it. */
    const val GEC_INSTRUCTION = "Отформатируй текст голосового ввода: расставь пунктуацию и заглавные " +
        "буквы, разбей на абзацы, исправь опечатки и орфографические ошибки. Сохрани язык, слова и " +
        "смысл, ничего не добавляй от себя."

    val QUALITY = LocalModelSpec(
        id = QUALITY_ID,
        displayName = "Qwen3 1.7B Q4_K_M (quality cleanup)",
        languages = listOf("en", "ru"),
        credit = ModelCredit("Qwen Team / Alibaba Cloud", "Apache-2.0", QUALITY_CREDIT_URL),
        files = listOf(LocalModelFile(
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/d7f544eead698dbd1f15126ef60b45a1e1933222/Qwen3-1.7B-Q4_K_M.gguf",
            "model.gguf", 1_107_409_472,
            "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
        )),
    )

    val FAST = LocalModelSpec(
        id = FAST_ID,
        displayName = "Qwen3 0.6B Q4_K_M (fast cleanup)",
        languages = listOf("en", "ru"),
        credit = ModelCredit("Qwen Team / Alibaba Cloud", "Apache-2.0", FAST_CREDIT_URL),
        files = listOf(LocalModelFile(
            "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf",
            "model.gguf", 396_705_472,
            "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
        )),
    )

    val GEC = LocalModelSpec(
        id = GEC_ID,
        displayName = "Qwen3.5 0.8B GEC Q4_0 (grammar/spelling)",
        languages = listOf("en", "ru", "kk"),
        credit = ModelCredit("Loqira Labs / Qwen Team", "Apache-2.0", GEC_CREDIT_URL),
        files = listOf(LocalModelFile(
            "https://huggingface.co/loqira/Qwen3.5-0.8B-GEC-KAZ-RUS-ENG/resolve/ced469768d40dffc899fe459efb66a4a07aeabee/Qwen3.5-0.8B-GEC-KAZ-RUS-ENG.Q4_0.gguf",
            "model.gguf", 563_035_616,
            "893f573db88f587e823b66847e06049cdb3029eff6177d92f6577179f7178871",
        )),
    )

    /** The original pick, kept as the default so an existing installation keeps the model it chose. */
    val specs: List<LocalModelSpec> = listOf(QUALITY, FAST, GEC)

    /** Model-specific system instruction, when the checkpoint was trained with one. */
    fun instructionFor(id: String?): String? = if (id == GEC_ID) GEC_INSTRUCTION else null

    /** GEC preserves words by design; its English fillers need a deterministic pre-pass. */
    fun needsHesitationPrefilter(id: String?): Boolean = id == GEC_ID

    /** The GEC fine-tune is a proofreader, not a general instruction-following model. */
    fun supportsEditing(id: String?): Boolean = id == QUALITY_ID || id == FAST_ID

    /** The previous single-model API still points at the quality checkpoint. */
    val spec: LocalModelSpec get() = QUALITY

    fun byIdOrNull(id: String?): LocalModelSpec? = specs.firstOrNull { it.id == id }

    fun active(id: String?): LocalModelSpec = byIdOrNull(id) ?: QUALITY

    val supported: Boolean get() = "arm64-v8a" in Build.SUPPORTED_ABIS

    fun file(context: Context, spec: LocalModelSpec = QUALITY) =
        File(LocalTranscriptionProvider.modelDir(context, spec.id), "model.gguf")

    fun isInstalled(context: Context, spec: LocalModelSpec = QUALITY): Boolean =
        supported && file(context, spec).length() == spec.files.single().sizeBytes

    fun installed(context: Context): List<LocalModelSpec> = specs.filter { isInstalled(context, it) }

    fun anyInstalled(context: Context): Boolean = installed(context).isNotEmpty()
}
