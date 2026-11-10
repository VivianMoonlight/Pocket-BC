package dev.vivian.mobilebc.portrait

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vivian.mobilebc.R
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.feature.characterprofile.CharacterPortraitState
import dev.vivian.mobilebc.feature.roomdetails.InteractionGroupUiState

internal enum class PortraitInteractionMode { Action, Item }

private val defaultInteractionGeometry = PortraitGeometry(0.0, 500.0, 0.0, 1000.0, 0.0, 1000.0, false)

@Composable
internal fun InteractivePortrait(
    state: CharacterPortraitState?,
    groups: List<InteractionGroupUiState>,
    mode: PortraitInteractionMode,
    onSelect: (String) -> Unit,
) {
    val visibility = LocalPortraitVisibility.current
    val canShowPortrait = visibility.visible && state != null && state.version.normalizedBcVersionOrNull() == PortraitVersion
    val availableGroups = remember(groups, mode) {
        groups.filter { if (mode == PortraitInteractionMode.Action) it.hasActivities || it.hasItems || it.hasCurrentItem else it.hasItems || it.hasCurrentItem }
            .distinctBy { it.name }
    }
    var candidates by remember(state?.character, mode) { mutableStateOf<List<InteractionGroupUiState>>(emptyList()) }
    var retry by remember(state?.character?.memberNumber) { mutableIntStateOf(0) }
    var geometry by remember(state?.character) { mutableStateOf<PortraitGeometry?>(null) }
    val interactionGeometry = geometry ?: defaultInteractionGeometry
    val selectCurrent by rememberUpdatedState(onSelect)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.portrait_tap_part), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            if (availableGroups.isNotEmpty()) TextButton(onClick = { candidates = availableGroups }) {
                Text(stringResource(R.string.portrait_part_list))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val portraitHeight = minOf(maxWidth, 400.dp) * 2
            Box(Modifier.fillMaxWidth()) {
                if (canShowPortrait && state != null) {
                    key(state.character.memberNumber, retry) {
                        PortraitCanvas(state, onRetry = { retry++ }, height = portraitHeight,
                            onGeometryChanged = { if (it != null) geometry = it }, quietLoading = true)
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(portraitHeight))
                }
                Box(
                    Modifier.align(Alignment.TopCenter).width(portraitHeight / 2).height(portraitHeight)
                        .pointerInput(interactionGeometry, availableGroups) {
                            detectTapGestures { offset ->
                                val x = offset.x * 500f / size.width
                                val y = offset.y * 1000f / size.height
                                val matches = portraitGroupsAt(availableGroups, interactionGeometry, x, y) { true }
                                if (matches.size == 1) selectCurrent(matches.single().name)
                                else if (matches.isNotEmpty()) candidates = matches
                            }
                        },
                ) {
                    PortraitInteractionZones(availableGroups, interactionGeometry)
                }
            }
        }
    }
    if (candidates.isNotEmpty()) {
        AlertDialog(onDismissRequest = { candidates = emptyList() },
            title = { Text(stringResource(R.string.portrait_choose_part)) },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(candidates, key = { it.name }) { group ->
                    TextButton(onClick = { candidates = emptyList(); onSelect(group.name) }) { Text(group.displayName) }
                }
            } },
            confirmButton = { TextButton(onClick = { candidates = emptyList() }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

@Composable
private fun PortraitInteractionZones(groups: List<InteractionGroupUiState>, geometry: PortraitGeometry) {
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val border = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    val labelColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val labelShadow = MaterialTheme.colorScheme.surface.toArgb()
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        val xScale = size.width / 500f
        val yScale = size.height / 1000f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textAlign = Paint.Align.CENTER
            setShadowLayer(2.dp.toPx(), 0f, 0f, labelShadow)
        }
        for (group in groups.distinctBy { it.name }) for (zone in group.zones) {
            val rect = geometry.bounds(zone) ?: continue
            val left = rect.left * xScale
            val top = rect.top * yScale
            val width = (rect.right - rect.left) * xScale
            val height = (rect.bottom - rect.top) * yScale
            val corner = CornerRadius(3.dp.toPx())
            drawRoundRect(fill, topLeft = Offset(left, top), size = Size(width, height), cornerRadius = corner)
            drawRoundRect(border, topLeft = Offset(left, top), size = Size(width, height), cornerRadius = corner,
                style = Stroke(width = 1.dp.toPx()))
            paint.textSize = minOf(12.sp.toPx(), height * 0.55f)
            val textWidth = paint.measureText(group.displayName)
            if (textWidth > width - 4.dp.toPx() && textWidth > 0f) {
                paint.textSize *= ((width - 4.dp.toPx()) / textWidth).coerceAtLeast(0f)
            }
            if (paint.textSize >= 7.sp.toPx() && paint.measureText(group.displayName) <= width - 4.dp.toPx()) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(group.displayName, left + width / 2f,
                        top + (height - paint.ascent() - paint.descent()) / 2f, paint)
                }
            }
        }
    }
}
