/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.about

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.io.FlorisRef
import dev.patrickgold.florisboard.lib.io.loadTextAsset
import org.florisboard.lib.compose.florisVerticalScroll
import org.florisboard.lib.compose.stringRes

/**
 * Shows the attributions for the redistributed language data (word-frequency dictionaries from OPUS
 * OpenSubtitles and bigram context data from the Leipzig Corpora Collection, CC BY). Required attribution;
 * surfaced under About so it is visible in the app, not just bundled as an asset.
 */
@Composable
fun DataAttributionsScreen() = FlorisScreen {
    title = stringRes(R.string.about__data_attributions__title)
    scrollable = false

    val context = LocalContext.current

    content {
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .florisVerticalScroll(),
        ) {
            // Local fork, November 2026: show full notices for the bundled derivative dictionaries.
            val text = remember(context) {
                listOf("license/data_attributions.txt", "vocabulary/en_US-license.txt",
                    "vocabulary/ru_RU-license.txt", "vocabulary/danakt-license.txt").joinToString("\n\n") { path ->
                    FlorisRef.assets(path).loadTextAsset(context).getOrElse { it.message ?: path }
                }
            }
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}
