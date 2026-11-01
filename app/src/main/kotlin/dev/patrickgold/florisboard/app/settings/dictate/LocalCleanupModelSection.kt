/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupModel
import dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupProvider
import dev.patrickgold.florisboard.dictate.provider.LocalModelDownloads
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes
import kotlin.math.roundToInt

/** The two on-device cleanup checkpoints: quality and a faster, smaller alternative. */
@Composable
fun LocalCleanupModelSection(
    activeModelId: String,
    onActiveModelChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val tick by LocalModelDownloads.installedTick.collectAsState()
    var deleteTick by remember { mutableIntStateOf(0) }
    val installed = remember(tick, deleteTick) {
        LocalCleanupModel.specs.filter { LocalCleanupModel.isInstalled(context, it) }.map { it.id }.toSet()
    }
    val downloads by LocalModelDownloads.state.collectAsState()

    Column {
        Text(
            stringRes(R.string.dictate__local_cleanup_description),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            stringRes(R.string.dictate__local_cleanup_choice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (!LocalCleanupModel.supported) {
            Text(stringRes(R.string.dictate__local_cleanup_unsupported))
        } else {
            LocalCleanupModel.specs.forEach { spec ->
                ModelRow(
                    spec,
                    LocalModelState(installed, downloads, activeModelId, ""),
                    LocalModelActions(
                        onSelect = { onActiveModelChange(spec.id) },
                        onInstall = {
                            LocalModelDownloads.clearError(spec.id)
                            LocalModelDownloads.start(context, spec)
                        },
                        onCancel = { LocalModelDownloads.cancel(spec.id) },
                        onDelete = {
                            LocalModelManager.delete(context, spec.id)
                            LocalCleanupProvider.unloadNow()
                            deleteTick++
                        },
                    ),
                )
            }
        }

    }
}
