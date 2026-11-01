/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.chatModelFor
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/**
 * The only model management screen left. Cloud providers, API keys, proxies and connection tests are
 * gone; speech recognition and text cleanup are selected and downloaded here, and nothing is uploaded.
 */
@Composable
fun DictateProvidersScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__local_models_title)
    previewFieldVisible = false
    // The screen owns its vertical scroll below. Leaving FlorisScreen's scroll on as well nests two
    // vertical scroll containers, which Compose cannot measure (the screen closes as soon as it opens).
    scrollable = false

    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val accounts by prefs.dictate.providerAccounts.collectAsState()
    val local = accounts.getOrEmpty(ProviderRegistry.LOCAL.id)
    val cleanup = accounts.getOrEmpty(ProviderRegistry.LOCAL_CLEANUP.id)

    var activeModel by remember(local.transcriptionModel) {
        mutableStateOf(
            local.transcriptionModel.ifBlank {
                ProviderRegistry.LOCAL.defaultTranscriptionModel.orEmpty()
            },
        )
    }
    var activeStreamingModel by remember(local.realtimeModel) {
        mutableStateOf(local.realtimeModel)
    }
    var activeCleanup by remember(cleanup.transcriptionModel, cleanup.chatModel) {
        mutableStateOf(chatModelFor(cleanup, ProviderRegistry.LOCAL_CLEANUP))
    }

    fun save(id: String, update: (dev.patrickgold.florisboard.dictate.provider.ProviderAccount) -> dev.patrickgold.florisboard.dictate.provider.ProviderAccount) {
        scope.launch {
            prefs.dictate.providerAccounts.set(
                prefs.dictate.providerAccounts.get().edit(id, update),
            )
        }
    }

    content {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                stringRes(R.string.dictate__local_models_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            Text(
                stringRes(R.string.dictate__local_speech_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LocalModelSection(
                activeModelId = activeModel,
                activeStreamingModelId = activeStreamingModel,
                onActiveModelChange = {
                    activeModel = it
                    val model = it
                    save(ProviderRegistry.LOCAL.id) { it.copy(transcriptionModel = model) }
                },
                onActiveStreamingModelChange = {
                    activeStreamingModel = it
                    val model = it
                    save(ProviderRegistry.LOCAL.id) { it.copy(realtimeModel = model) }
                },
                onModelChosen = {
                    scope.launch {
                        prefs.dictate.transcriptionProviderId.set(ProviderRegistry.LOCAL.id)
                    }
                },
            )

            Text(
                stringRes(R.string.dictate__local_cleanup_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            )
            LocalCleanupModelSection(
                activeModelId = activeCleanup,
                onActiveModelChange = {
                    activeCleanup = it
                    val model = it
                    save(ProviderRegistry.LOCAL_CLEANUP.id) { it.copy(transcriptionModel = model) }
                },
            )
        }
    }
}
