/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.cleanup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.provider.DictateApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Serial CPU inference, with no HTTP client and no cloud fallback.
 *
 * The weights and the KV cache stay resident between calls: mapping 1.1 GB and allocating a 4096-token
 * context per transcript cost several seconds on top of the (already resident) speech model's answer.
 * [warmUp] loads them while the keyboard is open, and an idle timer frees them again using the same
 * user-chosen 0–30 minute budget as the on-device speech models.
 */
object LocalCleanupProvider {
    private const val LOG_TAG = "DictateCleanup"
    private const val THREADS = 4

    private val prefs by FlorisPreferenceStore

    @Volatile private var appContext: Context? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "local-text-cleanup").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "local-cleanup-unload").apply { isDaemon = true }
    }

    @Volatile private var pendingUnload: ScheduledFuture<*>? = null
    private val activityGeneration = AtomicLong()
    @Volatile private var nativeUsed = false

    fun initialize(context: Context) { appContext = context.applicationContext }

    /** Loads the selected model and evaluates its fixed cleanup instruction before recording starts. */
    fun warmUp(context: Context, modelId: String, instruction: String, onFinished: () -> Unit = {}) {
        if (!LocalCleanupModel.supported) { onFinished(); return }
        val app = context.applicationContext
        appContext = app
        val spec = LocalCleanupModel.byIdOrNull(modelId) ?: run { onFinished(); return }
        val file = LocalCleanupModel.file(app, spec)
        if (file.length() != spec.files.single().sizeBytes) { onFinished(); return }
        markActive()
        worker.execute {
            markActive()
            val started = SystemClock.elapsedRealtime()
            try {
                nativeUsed = true
                val cold = LocalCleanupNative.warm(
                    file.absolutePath,
                    LocalCleanupText.prefix(
                        cleanupOnly = true,
                        override = instruction,
                    ).toByteArray(Charsets.UTF_8),
                    THREADS,
                )
                val perf = LocalCleanupNative.performance()
                Log.i(LOG_TAG, "model=${spec.id} phase=cleanupWarm cold=$cold phaseMs=${SystemClock.elapsedRealtime() - started} " +
                    "loadMs=${perf[0]} promptMs=${perf[1]} prefixTokens=${perf[7]} reusedTokens=${perf[5]}")
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "model=${spec.id} phase=cleanupWarm failed: ${e.message}")
            } finally {
                scheduleIdleUnload()
                onFinished()
            }
        }
    }

    /** Awaitable warm-up lets the coordinator load speech first, then text without parallel peaks. */
    suspend fun preload(context: Context, modelId: String, instruction: String) = suspendCancellableCoroutine<Unit> { continuation ->
        warmUp(context, modelId, instruction) { if (continuation.isActive) continuation.resume(Unit) }
    }

    /** Frees the resident weights immediately, e.g. after the model file is deleted in settings. */
    fun unloadNow() {
        markActive()
        worker.execute { if (nativeUsed) LocalCleanupNative.unload() }
    }

    /** Re-arm an already resident model when the user changes the shared retention slider. */
    fun retentionChanged() {
        markActive()
        worker.execute { scheduleIdleUnload() }
    }

    suspend fun complete(
        input: String,
        modelId: String,
        timeoutMillis: Long,
        cleanupOnly: Boolean = false,
        language: String? = null,
        instructionOverride: String? = null,
    ): String = LocalCleanupPipeline.complete(input, modelId, cleanupOnly, language, instructionOverride) { prompt, prefix ->
        infer(modelId, timeoutMillis, prompt.toByteArray(Charsets.UTF_8), prefix.toByteArray(Charsets.UTF_8))
    }

    private suspend fun infer(modelId: String, timeoutMillis: Long, prompt: ByteArray, prefix: ByteArray): String {
        check(LocalCleanupModel.supported) { "Local text cleanup requires an ARM64 device" }
        val app = checkNotNull(appContext) { "Local cleanup is not initialized" }
        val spec = checkNotNull(LocalCleanupModel.byIdOrNull(modelId)) { "The selected cleanup model is unavailable" }
        val model = LocalCleanupModel.file(app, spec)
        check(model.length() == spec.files.single().sizeBytes) { "Download the local text cleanup model first" }
        return suspendCancellableCoroutine { continuation ->
            markActive()
            val request = LocalCleanupNative.createRequest(timeoutMillis)
            continuation.invokeOnCancellation { LocalCleanupNative.cancelRequest(request) }
            worker.execute {
                markActive()
                val started = SystemClock.elapsedRealtime()
                try {
                    if (continuation.isActive) {
                        nativeUsed = true
                        val bytes = LocalCleanupNative.complete(request, model.absolutePath, prompt, prefix, THREADS)
                        val perf = LocalCleanupNative.performance()
                        Log.i(LOG_TAG, "model=${spec.id} phase=cleanupComplete phaseMs=${SystemClock.elapsedRealtime() - started} " +
                            "loadMs=${perf[0]} promptMs=${perf[1]} generationMs=${perf[2]} promptTokens=${perf[3]} outputTokens=${perf[4]} " +
                            "reusedTokens=${perf[5]} evaluatedTokens=${perf[6]} prefixTokens=${perf[7]}")
                        continuation.resume(bytes.toString(Charsets.UTF_8))
                    }
                } catch (e: CancellationException) {
                    continuation.cancel(e)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "model=${spec.id} phase=cleanupFailed phaseMs=${SystemClock.elapsedRealtime() - started}: ${e.message}")
                    val kind = if (e is TimeoutException) DictateApiException.Kind.TIMEOUT else DictateApiException.Kind.UNKNOWN
                    continuation.resumeWithException(DictateApiException(kind, e.message ?: "Local text cleanup failed", e))
                } finally {
                    LocalCleanupNative.releaseRequest(request)
                    scheduleIdleUnload()
                }
            }
        }
    }

    private fun markActive() {
        activityGeneration.incrementAndGet()
        pendingUnload?.cancel(false)
    }

    private fun scheduleIdleUnload() {
        pendingUnload?.cancel(false)
        val generation = activityGeneration.get()
        val minutes = prefs.dictate.localModelUnloadMinutes.get()
        if (minutes <= 0 || !nativeUsed) return
        pendingUnload = scheduler.schedule(
            { worker.execute {
                if (generation == activityGeneration.get()) {
                    LocalCleanupNative.unload()
                    Log.i(LOG_TAG, "phase=cleanupUnload idle")
                }
            } },
            minutes.toLong(), TimeUnit.MINUTES,
        )
    }
}

internal object LocalCleanupNative {
    init { System.loadLibrary("dictate_cleanup") }
    external fun createRequest(timeoutMillis: Long): Long
    external fun cancelRequest(id: Long)
    external fun releaseRequest(id: Long)
    external fun complete(id: Long, modelPath: String, promptUtf8: ByteArray, prefixUtf8: ByteArray, threads: Int): ByteArray
    /** Maps weights, allocates context and evaluates the fixed prefix; returns whether weights loaded. */
    external fun warm(modelPath: String, prefixUtf8: ByteArray, threads: Int): Boolean
    external fun unload()
    /** Timings and token counts, including prefix reuse and retained KV size. No text content. */
    external fun performance(): LongArray
}
