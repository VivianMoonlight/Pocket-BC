package dev.vivian.mobilebc.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.focusGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

val LocalScreenInteractive = staticCompositionLocalOf { true }

/** Keeps outgoing content drawable without leaving invisible controls clickable. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun InteractionBoundary(enabled: Boolean, content: @Composable () -> Unit) {
    Box(
        if (enabled) Modifier else Modifier
            .clearAndSetSemantics { }
            .focusProperties { enter = { FocusRequester.Cancel } }
            .focusGroup()
            .onPreviewKeyEvent { true }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
    ) { content() }
}

@Composable
fun <T : Any> PageTransition(
    target: T,
    backward: Boolean,
    contentKey: (T) -> Any = { it },
    content: @Composable (T) -> Unit,
) {
    val distance = with(LocalDensity.current) { 24.dp.roundToPx() }
    AnimatedContent(
        targetState = target,
        contentKey = contentKey,
        transitionSpec = {
            val direction = if (backward) -1 else 1
            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { distance * direction }) togetherWith
                (fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { -distance * direction })
        },
        label = "page",
    ) { screen -> content(screen) }
}

/** Owns only the visual dismissal; business cancellation stays in onDismissRequest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedModalSheet(
    onDismissRequest: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.(requestClose: () -> Unit) -> Unit,
) {
    if (!LocalScreenInteractive.current) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val onDismiss by rememberUpdatedState(onDismissRequest)
    val dismissal = remember { DismissalGuard() }
    var closing by remember { mutableStateOf(false) }
    DisposableEffect(dismissal) { onDispose { dismissal.invalidate() } }
    val finish = {
        if (dismissal.complete()) onDismiss()
    }
    ModalBottomSheet(onDismissRequest = finish, sheetState = sheet) {
        InteractionBoundary(!closing) {
            Column {
                content {
                    if (dismissal.begin()) {
                        closing = true
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                        scope.launch {
                            try {
                                sheet.hide()
                                if (!sheet.isVisible) finish()
                            } finally {
                                closing = false
                                dismissal.cancelPending()
                            }
                        }
                    }
                }
            }
        }
    }
}
