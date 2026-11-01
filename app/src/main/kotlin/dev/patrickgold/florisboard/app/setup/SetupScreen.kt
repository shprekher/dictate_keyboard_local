/* Copyright (C) 2021-2026 The FlorisBoard Contributors. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.app.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.dictate.provider.LocalModelDownloads
import dev.patrickgold.florisboard.dictate.provider.LocalModelManager
import dev.patrickgold.florisboard.dictate.provider.ProviderAccounts
import dev.patrickgold.florisboard.dictate.provider.ProviderRegistry
import dev.patrickgold.florisboard.dictate.ui.DictateWaveform
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.florisboard.lib.util.launchActivity
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.florisboard.lib.android.AndroidVersion
import org.florisboard.lib.compose.FlorisStep
import org.florisboard.lib.compose.FlorisStepLayout
import org.florisboard.lib.compose.FlorisStepState
import org.florisboard.lib.compose.stringRes

/** Setup checks the same local model slots as dictation; legacy remote accounts cannot satisfy it. */
internal fun isProviderConfigured(
    accounts: ProviderAccounts,
    providerId: String,
    isModelInstalled: (String) -> Boolean,
): Boolean {
    val local = accounts.getOrEmpty(ProviderRegistry.LOCAL.id)
    return listOf(
        local.transcriptionModel.ifBlank { ProviderRegistry.LOCAL.defaultTranscriptionModel.orEmpty() },
        local.realtimeModel,
    ).any { it.isNotBlank() && isModelInstalled(it) }
}

@Composable
fun SetupScreen() = FlorisScreen {
    title = stringRes(R.string.setup__title)
    navigationIconVisible = false
    scrollable = false

    val context = LocalContext.current
    val navController = LocalNavController.current
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()
    val enabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
    val selected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)
    val notificationPermission by prefs.internal.notificationPermissionState.collectAsState()
    val accounts by prefs.dictate.providerAccounts.collectAsState()
    val installedTick by LocalModelDownloads.installedTick.collectAsState()
    val configured = remember(accounts, installedTick) {
        isProviderConfigured(accounts, ProviderRegistry.LOCAL.id) { LocalModelManager.isInstalled(context, it) }
    }
    var providerSkipped by rememberSaveable { mutableStateOf(false) }
    var floatingPassed by rememberSaveable { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        micGranted = it
    }
    val requestNotification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            prefs.internal.notificationPermissionState.set(
                if (granted) NotificationPermissionState.GRANTED else NotificationPermissionState.DENIED,
            )
        }
    }
    fun targetStep(): Int = when {
        !enabled -> 1
        !selected -> 2
        !micGranted -> 3
        AndroidVersion.ATLEAST_API33_T && notificationPermission == NotificationPermissionState.NOT_SET -> 4
        !configured && !providerSkipped -> 5
        !floatingPassed -> 6
        else -> 7
    }
    val stepState = rememberSaveable(saver = FlorisStepState.Saver) { FlorisStepState.new(init = targetStep()) }
    content {
        LaunchedEffect(enabled, selected, micGranted, notificationPermission, configured, providerSkipped, floatingPassed) {
            stepState.setCurrentAuto(targetStep())
        }
        // Return from Android's IME enabler after it has enabled the keyboard.
        LaunchedEffect(Unit) {
            while (true) {
                delay(200L)
                if (stepState.getCurrentAuto().value == 1 && stepState.getCurrentManual().value == -1 &&
                    !enabled && !selected && notificationPermission == NotificationPermissionState.NOT_SET &&
                    InputMethodUtils.isFlorisboardEnabled(context)
                ) {
                    context.launchActivity(FlorisAppActivity::class) {
                        it.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                }
            }
        }
        FlorisStepLayout(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            stepState = stepState,
            backLabel = stringRes(R.string.setup__nav_back),
            nextLabel = stringRes(R.string.setup__nav_next),
            header = {
                StepText(stringRes(R.string.setup__intro_message))
                Spacer(Modifier.height(16.dp))
            },
            steps = listOfNotNull(
                FlorisStep(
                    id = 1,
                    title = stringRes(R.string.setup__enable_ime__title),
                    art = { DictateWaveform(modifier = Modifier.fillMaxWidth().height(96.dp)) },
                ) {
                    StepText(stringRes(R.string.setup__enable_ime__description))
                    StepButton(stringRes(R.string.setup__enable_ime__open_settings_btn)) {
                        InputMethodUtils.showImeEnablerActivity(context)
                    }
                },
                FlorisStep(id = 2, title = stringRes(R.string.setup__select_ime__title), icon = Icons.Default.SwapHoriz) {
                    StepText(stringRes(R.string.setup__select_ime__description))
                    StepButton(stringRes(R.string.setup__select_ime__switch_keyboard_btn)) { InputMethodUtils.showImePicker(context) }
                },
                FlorisStep(id = 3, title = stringRes(R.string.setup__grant_mic_permission__title), icon = Icons.Default.Mic) {
                    StepText(stringRes(R.string.setup__grant_mic_permission__description))
                    StepButton(stringRes(R.string.setup__grant_mic_permission__btn)) { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
                },
                if (AndroidVersion.ATLEAST_API33_T) FlorisStep(
                    id = 4, title = stringRes(R.string.setup__grant_notification_permission__title), icon = Icons.Default.NotificationsActive,
                ) {
                    StepText(stringRes(R.string.setup__grant_notification_permission__description))
                    StepButton(stringRes(R.string.setup__grant_notification_permission__btn)) { requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS) }
                } else null,
                FlorisStep(id = 5, title = stringRes(R.string.dictate__local_models_title)) {
                    StepText(stringRes(R.string.dictate__local_models_intro))
                    StepButton(stringRes(R.string.dictate__local_models_title)) { navController.navigate(Routes.Settings.DictateProviders) }
                    TextButton(onClick = { providerSkipped = true }) { Text(stringRes(R.string.setup__provider__skip_btn)) }
                },
                FlorisStep(id = 6, title = stringRes(R.string.setup__floating_button__title), icon = Icons.Default.Adjust) {
                    StepText(stringRes(R.string.setup__floating_button__intro))
                    StepText(stringRes(R.string.setup__floating_button__accessibility_note))
                    StepText(stringRes(R.string.setup__floating_button__optional_note))
                    StepButton(stringRes(R.string.setup__floating_button__btn)) {
                        scope.launch {
                            prefs.internal.openFloatingButtonAfterSetup.set(true)
                            prefs.internal.isImeSetUp.set(true)
                        }
                        navController.navigate(Routes.Settings.Home) { popUpTo(Routes.Setup.Screen) { inclusive = true } }
                    }
                    TextButton(onClick = { floatingPassed = true }) { Text(stringRes(R.string.setup__floating_button__skip_btn)) }
                },
                FlorisStep(id = 7, title = stringRes(R.string.setup__finish_up__title), icon = Icons.Default.Celebration) {
                    StepText(stringRes(R.string.setup__finish_up__description_p1))
                    StepText(stringRes(R.string.setup__finish_up__description_p2))
                    if (!configured) StepText(stringRes(R.string.dictate__local_models_intro))
                    StepButton(stringRes(R.string.setup__finish_up__finish_btn)) {
                        scope.launch { prefs.internal.isImeSetUp.set(true) }
                        navController.navigate(Routes.Settings.Home) { popUpTo(Routes.Setup.Screen) { inclusive = true } }
                    }
                },
            ),
        )
    }
}
