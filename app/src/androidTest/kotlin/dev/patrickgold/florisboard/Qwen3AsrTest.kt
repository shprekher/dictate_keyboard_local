/* Copyright (C) 2026. Licensed under the Apache License, Version 2.0. */
package dev.patrickgold.florisboard

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Copy model.tar.bz2, ru1.wav, f1_noise.wav and vad.onnx into filesDir/qwen-test using adb run-as. */
@RunWith(AndroidJUnit4::class)
class Qwen3AsrTest {
    @Test
    fun installsArchiveAndTranscribesEnglishAndRussianOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputs = File(context.filesDir, "qwen-test")
        assumeTrue("Push Qwen test inputs first", File(inputs, "model.tar.bz2").isFile)
        val spec = LocalModelCatalog.QWEN3_ASR_06B_INT8
        val modelDir = LocalTranscriptionProvider.modelDir(context, spec.id).apply { mkdirs() }
        LocalModelManager.extractArchive(File(inputs, "model.tar.bz2"), spec.archive!!, spec.files, modelDir)
        File(inputs, "vad.onnx").copyTo(File(modelDir, LocalTranscriptionProvider.VAD), overwrite = true)
        assertTrue(LocalModelManager.isInstalled(context, spec.id))
        try {
            val provider = LocalTranscriptionProvider(modelDir)
            for ((filename, language) in listOf("ru1.wav" to "ru", "f1_noise.wav" to "en", "ru1.wav" to null)) {
                val start = System.nanoTime()
                val text = provider.transcribe(TranscriptionRequest(File(inputs, filename), spec.id, language)).text
                Log.i("Qwen3AsrTest", "$filename / $language: $text (${(System.nanoTime() - start) / 1_000_000} ms)")
                assertTrue("empty transcript", text.isNotBlank())
                assertTrue("raw model scaffold: $text", "<asr_text>" !in text && "<|im_" !in text)
                if (language == "en") {
                    assertTrue(text, text.contains("Charles", ignoreCase = true))
                } else {
                    assertTrue(text, text.contains("побег", ignoreCase = true))
                }
            }
            // Exercise the hard-cap fallback too: a missing VAD must not allow unlimited audio
            // into Qwen's fixed context. f1_noise.wav is 19 s, longer than one model segment.
            val vad = File(modelDir, LocalTranscriptionProvider.VAD)
            assertTrue(vad.delete())
            try {
                val text = provider.transcribe(TranscriptionRequest(File(inputs, "f1_noise.wav"), spec.id, "en")).text
                Log.i("Qwen3AsrTest", "without VAD: $text")
                assertTrue(text, text.contains("Charles", ignoreCase = true) && text.length > 80)
            } finally {
                File(inputs, "vad.onnx").copyTo(vad, overwrite = true)
            }
        } finally {
            LocalTranscriptionProvider.unloadCachedModel()
        }
    }
}
