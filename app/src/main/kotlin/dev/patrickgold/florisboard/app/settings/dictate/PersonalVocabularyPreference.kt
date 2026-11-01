/* Copyright 2026 The Dictate Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.app.settings.dictate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.dictate.vocabulary.PersonalVocabulary
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceUiScope
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.florisDialogScroll
import org.florisboard.lib.compose.stringRes

/** Uses the existing preference/backup store. Editing replaces the list only after validation + Save. */
@Composable
internal fun PreferenceUiScope<FlorisPreferenceModel>.PersonalVocabularyPreference(pref: PreferenceData<String>) {
    val saved by pref.collectAsState()
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Preference(
        title = stringRes(R.string.dictate__vocabulary_title),
        summary = stringRes(R.string.dictate__vocabulary_summary),
        onClick = { draft = saved; error = null },
    )
    if (draft != null) {
        JetPrefAlertDialog(
            title = stringRes(R.string.dictate__vocabulary_title),
            scrollModifier = florisDialogScroll(),
            confirmLabel = stringRes(R.string.action__save),
            onConfirm = {
                val value = draft.orEmpty()
                val validation = runCatching { PersonalVocabulary.parse(value) }
                if (validation.isSuccess) scope.launch { pref.set(value); draft = null }
                else error = validation.exceptionOrNull()?.message
            },
            dismissLabel = stringRes(R.string.action__cancel),
            onDismiss = { draft = null },
        ) {
            Column {
                Text(stringRes(R.string.dictate__vocabulary_help))
                OutlinedTextField(
                    value = draft.orEmpty(),
                    onValueChange = { if (it.length <= 128 * 1024) { draft = it; error = null } },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                )
            }
        }
    }
}
