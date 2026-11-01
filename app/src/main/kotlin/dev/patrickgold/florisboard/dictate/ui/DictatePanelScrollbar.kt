/* Copyright 2026. SPDX-License-Identifier: Apache-2.0 */
package dev.patrickgold.florisboard.dictate.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Scrollbar for the lazy history/sticker panels; independent of the retired prompt panel. */
internal fun Modifier.dictateLazyPanelScrollbar(
    state: LazyListState,
    accent: Color,
    width: Dp = 5.dp,
): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible == 0 || visible >= total) return@drawWithContent
    val barWidth = width.toPx()
    val viewport = size.height
    val thumbHeight = (viewport * visible / total).coerceAtLeast(barWidth * 5f)
    val maxIndex = (total - visible).toFloat()
    val progress = (state.firstVisibleItemIndex / maxIndex).coerceIn(0f, 1f)
    val x = size.width - barWidth
    val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
    drawRoundRect(accent.copy(alpha = 0.12f), Offset(x, 0f), Size(barWidth, viewport), radius)
    drawRoundRect(accent.copy(alpha = 0.85f), Offset(x, (viewport - thumbHeight) * progress), Size(barWidth, thumbHeight), radius)
}
