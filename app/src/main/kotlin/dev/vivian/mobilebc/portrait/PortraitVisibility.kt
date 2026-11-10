package dev.vivian.mobilebc.portrait

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.vivian.mobilebc.R
import kotlin.math.roundToInt

internal class PortraitVisibility(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("portrait_visibility", Context.MODE_PRIVATE))
    var visible by mutableStateOf(preferences.getBoolean("visible", false))
        private set
    private var revision = 0

    fun updateVisible(value: Boolean) {
        revision++
        visible = value
        preferences.edit().putBoolean("visible", value).apply()
    }

    fun beginReveal(): Int = revision
    fun completeReveal(expectedRevision: Int) {
        if (revision == expectedRevision && !visible) updateVisible(true)
    }
}

internal val LocalPortraitVisibility = staticCompositionLocalOf<PortraitVisibility> { error("Missing portrait visibility") }

@Composable
internal fun PortraitVisibilityProvider(context: Context, content: @Composable () -> Unit) {
    val visibility = remember(context.applicationContext) { PortraitVisibility(context) }
    CompositionLocalProvider(LocalPortraitVisibility provides visibility, content = content)
}

private val dressIcon by lazy {
    ImageVector.Builder(name = "PortraitDress", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 2.5f); lineTo(6f, 4.5f); lineTo(8.5f, 9f); lineTo(3.5f, 20.5f)
            curveTo(8.9f, 22.2f, 15.1f, 22.2f, 20.5f, 20.5f); lineTo(15.5f, 9f)
            lineTo(18f, 4.5f); lineTo(16f, 2.5f); lineTo(14f, 4f); lineTo(10f, 4f); close()
            moveTo(8.5f, 9f); lineTo(15.5f, 9f)
        }
    }.build()
}

private fun eyeIcon(hidden: Boolean) = ImageVector.Builder(
    name = if (hidden) "PortraitEyeHidden" else "PortraitEye", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(12f, 4.5f); curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
        curveTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f); curveTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f)
        curveTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f); close()
        moveTo(12f, 6.5f); curveTo(15.8f, 6.5f, 19.1f, 8.7f, 20.8f, 12f)
        curveTo(19.1f, 15.3f, 15.8f, 17.5f, 12f, 17.5f); curveTo(8.2f, 17.5f, 4.9f, 15.3f, 3.2f, 12f)
        curveTo(4.9f, 8.7f, 8.2f, 6.5f, 12f, 6.5f); close()
        moveTo(12f, 8f); curveTo(14.21f, 8f, 16f, 9.79f, 16f, 12f)
        curveTo(16f, 14.21f, 14.21f, 16f, 12f, 16f); curveTo(9.79f, 16f, 8f, 14.21f, 8f, 12f)
        curveTo(8f, 9.79f, 9.79f, 8f, 12f, 8f); close()
    }
    if (hidden) path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round) {
        moveTo(3f, 3f); lineTo(21f, 21f)
    }
}.build()

private val shownEye by lazy { eyeIcon(false) }
private val hiddenEye by lazy { eyeIcon(true) }

@Composable
private fun PortraitStatusIcon(visible: Boolean, badgeColor: Color, description: String?) {
    Box(Modifier.size(32.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        Icon(dressIcon, contentDescription = null, modifier = Modifier.size(24.dp).align(Alignment.TopStart))
        Box(Modifier.size(19.dp).align(Alignment.BottomEnd).background(badgeColor, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (visible) shownEye else hiddenEye, contentDescription = null, modifier = Modifier.size(15.dp))
        }
    }
}

/** Press and drag the expanded track to reveal; tap once to hide. */
@Composable
internal fun PortraitVisibilityIcon(modifier: Modifier = Modifier) {
    val visibility = LocalPortraitVisibility.current
    val density = LocalDensity.current
    val travel = with(density) { 112.dp.toPx() }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var expanded by remember { mutableStateOf(false) }
    val popupState = remember { MutableTransitionState(false) }
    popupState.targetState = expanded && !visibility.visible
    val label = stringResource(if (visibility.visible) R.string.portrait_hide_all else R.string.portrait_slide_to_show)
    val showLabel = stringResource(R.string.portrait_show)
    val dragModifier = if (visibility.visible) Modifier else Modifier
        .semantics {
            customActions = listOf(CustomAccessibilityAction(showLabel) {
                visibility.completeReveal(visibility.beginReveal())
                true
            })
        }
        .pointerInput(visibility, travel) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val revision = visibility.beginReveal()
                dragDistance = 0f
                expanded = true
                try {
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        dragDistance = (change.position.y - down.position.y).coerceIn(0f, travel)
                        if (dragDistance > viewConfiguration.touchSlop) change.consume()
                        if (!change.pressed) {
                            if (dragDistance >= travel) visibility.completeReveal(revision)
                            break
                        }
                    } while (true)
                } finally {
                    expanded = false
                }
            }
        }
    Box(modifier = modifier) {
        IconButton(onClick = { if (visibility.visible) visibility.updateVisible(false) }, modifier = dragModifier) {
            PortraitStatusIcon(visibility.visible, MaterialTheme.colorScheme.surface, label)
        }
        if (popupState.currentState || popupState.targetState) {
            Popup(alignment = Alignment.TopEnd, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
                AnimatedVisibility(
                    visibleState = popupState,
                    enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.85f, transformOrigin = TransformOrigin(1f, 0f)),
                    exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
                ) {
                    Box(Modifier.size(width = 180.dp, height = 160.dp).clearAndSetSemantics {}) {
                        Box(
                            modifier = Modifier.align(Alignment.TopEnd).size(width = 48.dp, height = 160.dp)
                                .shadow(8.dp, RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
                        ) {
                            Box(Modifier.align(Alignment.BottomCenter).offset(y = (-12).dp)
                                .size(width = 18.dp, height = 3.dp)
                                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
                            Box(
                                modifier = Modifier.offset { IntOffset(0, dragDistance.roundToInt()) }.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                    PortraitStatusIcon(false, MaterialTheme.colorScheme.primaryContainer, null)
                                }
                            }
                        }
                        Box(
                            modifier = Modifier.align(Alignment.TopEnd)
                                .offset { IntOffset((-56.dp).roundToPx(), dragDistance.roundToInt() + 5.dp.roundToPx()) }
                                .shadow(4.dp, RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(18.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Crossfade(targetState = dragDistance >= travel, animationSpec = tween(120), label = "portrait slider hint") { atBottom ->
                                Text(stringResource(if (atBottom) R.string.portrait_slider_show else R.string.portrait_slider_hidden),
                                    color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
