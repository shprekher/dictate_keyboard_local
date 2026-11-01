/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.dictate.audio.AudioSpeedUp
import dev.patrickgold.florisboard.dictate.provider.LocalModelCatalog
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.LocalTranscriptionProvider
import dev.patrickgold.florisboard.dictate.provider.TranscriptionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #272 — what the speed-up costs in recognition accuracy, measured instead of guessed.
 *
 * Takes the WAVs pushed to [IN_DIR], produces a copy at each rate with the shipping [AudioSpeedUp] code,
 * and transcribes every one of them with an on-device model. The transcripts are logged in a parseable
 * form; word error rate against the reference text is computed off-device.
 *
 * Modified by the local fork, November 2026: offline only. Needs pushed audio and an already installed
 * model. Never downloads a model or sends the audio to a retired cloud provider.
 *
 *   adb shell mkdir -p /data/local/tmp/speedup
 *   adb push en.wav de.wav /data/local/tmp/speedup/
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.patrickgold.florisboard.SpeedUpAccuracyTest \
 *     -Pandroid.testInstrumentationRunnerArguments.speedUpModel=parakeet-tdt-0.6b-v3
 *
 * `speedUpModel=none` only writes the variants and skips
 * everything on-device.
 */
@RunWith(AndroidJUnit4::class)
class SpeedUpAccuracyTest {

    @Test
    fun transcribesEveryRateOnDevice() {
        val args = InstrumentationRegistry.getArguments()
        val modelId = args.getString("speedUpModel")
        assumeTrue("set speedUpModel=<id|none> to run the #272 measurement", modelId != null)
        val inDir = File(IN_DIR)
        val sources = inDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sortedBy { it.name }
        assumeTrue("no WAVs pushed to $IN_DIR — skipping", !sources.isNullOrEmpty())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // External files allow the user to retrieve variants for offline analysis.
        val outDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "speedup-out")
            .apply { mkdirs() }

        // 1) Every rate, produced by the code that ships. 100 % is copied so the reference run goes
        //    through exactly the same file plumbing as the rest.
        val variants = mutableListOf<Pair<File, Int>>()
        for (source in sources!!) {
            for (percent in RATES) {
                val out = File(outDir, "${source.nameWithoutExtension}-$percent.wav")
                val produced = if (percent == 100) {
                    source.copyTo(out, overwrite = true)
                } else {
                    runBlocking { AudioSpeedUp.process(source, out, percent / 100f) }
                }
                if (produced == null) {
                    Log.w(TAG, "$LINE|skip|${source.name}|$percent|speed-up declined this clip")
                    continue
                }
                variants += produced to percent
                Log.i(TAG, "$LINE|file|${source.name}|$percent|${produced.absolutePath}|${produced.length()}")
            }
        }
        assert(variants.isNotEmpty()) { "no variants were produced" }

        if (modelId == "none") {
            Log.i(TAG, "$LINE|done|variants written to ${outDir.absolutePath}")
            return
        }

        // 2) The same audio through an on-device model, once per variant.
        val spec = LocalModelCatalog.byId(modelId!!) ?: error("unknown model id: $modelId")
        assumeTrue("Install the explicitly selected model before this offline benchmark", LocalModelManager.isInstalled(context, spec.id))
        val provider = LocalTranscriptionProvider(LocalTranscriptionProvider.modelDir(context, spec.id))
        for ((file, percent) in variants) {
            val startedMs = System.currentTimeMillis()
            val text = runBlocking {
                provider.transcribe(
                    TranscriptionRequest(audioFile = file, model = spec.id, language = languageOf(file.name)),
                ).text
            }
            Log.i(
                TAG,
                "$LINE|text|${spec.id}|${file.name}|$percent|${System.currentTimeMillis() - startedMs}ms|" +
                    text.replace('\n', ' ').trim(),
            )
        }
    }

    /**
     * The language a clip is in, taken from its file name — the measurement runs with the language set,
     * the way someone who dictates in one language has it set.
     */
    private fun languageOf(name: String): String = if (name.startsWith("minds-de-")) "de" else "en"

    private companion object {
        const val TAG = "SpeedUpAccuracy"

        /** Marks the machine-readable lines so they can be pulled straight out of logcat. */
        const val LINE = "SPEEDUP"
        const val IN_DIR = "/data/local/tmp/speedup"

        val RATES = listOf(100, 125, 150, 175, 200)
    }
}
