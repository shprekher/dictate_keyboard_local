/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.provider

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateLanguages
import dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** One preload lane for keyboard opens and non-keyboard entry points. Never changes a preference. */
object LocalModelPreloader {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lane = Mutex()
    private var pending: Job? = null
    private var pendingKey: List<Any?>? = null

    @Synchronized
    fun preload(context: Context): Job {
        val app = context.applicationContext
        val accounts = prefs.dictate.providerAccounts.get()
        val local = accounts.getOrEmpty(ProviderRegistry.LOCAL.id)
        val batch = local.transcriptionModel.ifBlank { ProviderRegistry.LOCAL.defaultTranscriptionModel.orEmpty() }
        val live = local.realtimeModel.takeIf { LocalModelCatalog.isStreaming(it) }
            ?: batch.takeIf { LocalModelCatalog.isStreaming(it) }
        val speech = when {
            prefs.dictate.realtimeTranscription.get() && live != null && LocalModelManager.isInstalled(app, live) -> live
            LocalModelManager.isInstalled(app, batch) -> batch
            local.realtimeModel.isNotBlank() && LocalModelManager.isInstalled(app, local.realtimeModel) -> local.realtimeModel
            else -> batch
        }
        val cleanup = chatModelFor(accounts.getOrEmpty(ProviderRegistry.LOCAL_CLEANUP.id), ProviderRegistry.LOCAL_CLEANUP)
        val language = prefs.dictate.activeInputLanguage.get().takeIf { it != DictateLanguages.DETECT }
        val textEnabled = prefs.dictate.cleanupEnabled.get()
        val instruction = dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupPrompt.resolve(
            cleanup, prefs.dictate.cleanupPromptSelection.get(), prefs.dictate.cleanupPromptCustom.get(),
        )
        val minutes = prefs.dictate.localModelUnloadMinutes.get()
        val key = listOf(speech, cleanup, language, textEnabled, minutes, instruction)
        pending?.takeIf { it.isActive && key == pendingKey }?.let { return it }
        pending?.cancel()
        pendingKey = key
        return scope.launch {
            lane.withLock {
                val started = SystemClock.elapsedRealtime()
                LocalTranscriptionProvider.setIdleUnloadMillis(minutes * 60_000L)
                try {
                    LocalTranscriptionProvider.preload(app, speech, language)
                    Log.i("DictateLatency", "phase=speechPreload model=$speech phaseMs=${SystemClock.elapsedRealtime() - started}")
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Throwable) {
                    Log.w("DictateLatency", "phase=speechPreload model=$speech failed: ${e.message}")
                }
                coroutineContext.ensureActive()
                // Native mapping cannot be interrupted. Keep the lane until it actually finishes,
                // even if a model switch or pressure signal cancels this preload job.
                if (textEnabled) withContext(NonCancellable) { LocalCleanupProvider.preload(app, cleanup, instruction) }
                // Optional preparation only; microphone start never waits for this job.
                withContext(Dispatchers.IO) {
                    try { dev.patrickgold.florisboard.dictate.cleanup.VocabularyResources.get(app) }
                    catch (e: Exception) { Log.w("DictateLatency", "Vocabulary unavailable: ${e.javaClass.simpleName}") }
                }
            }
        }.also { pending = it }
    }

    @Synchronized
    fun cancel() { pending?.cancel(); pending = null; pendingKey = null }
}
