package dev.vivian.mobilebc.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

data class InteractionPanelControls(val expanded: Boolean, val toggle: () -> Unit)

val LocalInteractionPanelControls = staticCompositionLocalOf<InteractionPanelControls?> { null }
