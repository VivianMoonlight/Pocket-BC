package dev.vivian.mobilebc.feature.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.constrainHeight
import dev.vivian.mobilebc.core.ui.LocalBottomNavigationHeight

/** Measures the animated panel and IME in one pass, avoiding a one-frame inset lag. */
@Composable
internal fun ComposerBottomLayout(panelHasFocus: Boolean, content: @Composable () -> Unit) {
    val ime = WindowInsets.ime
    val navigation = WindowInsets.navigationBars
    val bottomNavigation = LocalBottomNavigationHeight.current
    Layout(content = content) { measurables, constraints ->
        val consumedBottom = maxOf(navigation.getBottom(this), bottomNavigation)
        val keyboard = (ime.getBottom(this) - consumedBottom).coerceAtLeast(0)
        val loose = constraints.copy(minHeight = 0)
        val controls = measurables.filter { it.layoutId != "tool" }.associateWith { it.measure(loose) }
        val controlsHeight = controls.values.sumOf { it.height }
        val panel = measurables.single { it.layoutId == "tool" }
        val panelPlaceable = panel.measure(loose.copy(
            maxHeight = (constraints.maxHeight - controlsHeight - if (panelHasFocus) keyboard else 0).coerceAtLeast(0),
        ))
        val gap = composerKeyboardSpace(ime.getBottom(this), consumedBottom, panelPlaceable.height, panelHasFocus)
        val height = constraints.constrainHeight(controlsHeight + panelPlaceable.height + gap)
        layout(constraints.maxWidth, height) {
            var y = 0
            measurables.forEach { measurable ->
                val placeable = if (measurable == panel) panelPlaceable else controls.getValue(measurable)
                placeable.placeRelative(0, y)
                y += placeable.height
            }
        }
    }
}

/** Insets are absolute pixels; the Scaffold already consumes the navigation bar. */
internal fun composerKeyboardSpace(ime: Int, navigation: Int, panel: Int, panelHasFocus: Boolean): Int {
    val keyboard = (ime - navigation).coerceAtLeast(0)
    return if (panelHasFocus) keyboard else (keyboard - panel).coerceAtLeast(0)
}
