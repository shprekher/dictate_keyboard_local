/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupPrompt
import dev.patrickgold.florisboard.dictate.cleanup.LocalCleanupModel
import dev.patrickgold.florisboard.dictate.data.prompts.DictatePromptDefaults
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.provider.chatModelFor
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import org.florisboard.lib.compose.stringRes

@Composable
fun DictateCleanupScreen() = FlorisScreen {
    title = stringRes(R.string.dictate__cleanup_title)
    previewFieldVisible = false
    val prefs by FlorisPreferenceStore
    content {
        val nav = LocalNavController.current
        val accounts by prefs.dictate.providerAccounts.collectAsState()
        val model = chatModelFor(accounts.getOrEmpty(ProviderRegistry.LOCAL_CLEANUP.id), ProviderRegistry.LOCAL_CLEANUP)
        val selection by prefs.dictate.cleanupPromptSelection.collectAsState()
        val custom by prefs.dictate.cleanupPromptCustom.collectAsState()
        val default = LocalCleanupPrompt.defaultFor(model)
        SwitchPreference(
            prefs.dictate.cleanupEnabled,
            icon = Icons.Default.AutoFixHigh,
            title = stringRes(R.string.dictate__cleanup_enabled_title),
            summary = stringRes(R.string.dictate__cleanup_enabled_summary),
        )
        Preference(
            icon = Icons.Default.ModelTraining,
            title = stringRes(R.string.dictate__local_cleanup_title),
            summary = LocalCleanupModel.byIdOrNull(model)?.displayName ?: model,
            onClick = { nav.navigate(Routes.Settings.DictateProviders) },
        )
        PromptSelectionPreference(
            pref = prefs.dictate.cleanupPromptSelection,
            title = stringRes(R.string.dictate__cleanup_prompt_title),
            icon = Icons.Default.Psychology,
            entries = listPrefEntries {
                entry(DictatePromptDefaults.SELECTION_PREDEFINED, stringRes(R.string.dictate__cleanup_prompt_default))
                entry(DictatePromptDefaults.SELECTION_CUSTOM, stringRes(R.string.dictate__cleanup_prompt_custom))
            },
            infoTitle = stringRes(R.string.dictate__cleanup_prompt_default),
            infoDescription = stringRes(R.string.dictate__cleanup_prompt_help),
            infoPromptText = default,
            customPref = prefs.dictate.cleanupPromptCustom,
            customPlaceholder = stringRes(R.string.dictate__cleanup_prompt_placeholder),
        )
        Text(
            stringRes(R.string.dictate__cleanup_prompt_help),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            stringRes(R.string.dictate__cleanup_prompt_active),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SelectionContainer {
            Text(
                LocalCleanupPrompt.resolve(model, selection, custom),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (LocalCleanupPrompt.customOverride(selection, custom) != null) {
            Text(
                stringRes(R.string.dictate__cleanup_prompt_default),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SelectionContainer { Text(default, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
    }
}
