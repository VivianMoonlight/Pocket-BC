package dev.vivian.mobilebc.portrait

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.vivian.mobilebc.MobileBcApplication
import dev.vivian.mobilebc.R
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.core.ui.LocalScreenInteractive
import dev.vivian.mobilebc.feature.characterprofile.CharacterPortraitState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

@Composable
internal fun BcCharacterPortrait(state: CharacterPortraitState) {
    val visibility = LocalPortraitVisibility.current
    key(state.ownerMemberNumber, state.accountGeneration, state.roomPresenceEpoch, state.version, state.character.memberNumber) {
        var retry by remember { mutableStateOf(0) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.portrait_title), style = MaterialTheme.typography.titleMedium)
                    PortraitVisibilityIcon()
                }
                if (visibility.visible) {
                    if (state.version.normalizedBcVersionOrNull() != PortraitVersion) {
                        Text(stringResource(R.string.portrait_version, state.version))
                    } else {
                        key(retry) {
                            PortraitCanvas(state, onRetry = { retry++ })
                        }
                        Text(stringResource(R.string.portrait_static), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PortraitCanvas(state: CharacterPortraitState, onRetry: () -> Unit, height: Dp? = null,
    onGeometryChanged: ((PortraitGeometry?) -> Unit)? = null, quietLoading: Boolean = false) {
    val context = LocalContext.current
    val composition = remember(context.applicationContext) {
        (context.applicationContext as MobileBcApplication).appComposition
    }
    val payload = remember(state.character) { runCatching { portraitPayload(state.character) }.getOrNull() }
    var key by remember(payload, state.ownerMemberNumber) { mutableStateOf<String?>(null) }
    var loaded by remember(payload, state.ownerMemberNumber) { mutableStateOf(false) }
    var image by remember(payload, state.ownerMemberNumber) { mutableStateOf<ImageBitmap?>(null) }
    var geometry by remember(payload, state.ownerMemberNumber) { mutableStateOf<PortraitGeometry?>(null) }
    val geometryVersion by composition.portraitFrames.geometryVersion.collectAsState()
    LaunchedEffect(payload, state.ownerMemberNumber) {
        if (payload != null) {
            val saved = withContext(Dispatchers.IO) {
                val id = portraitFrameKey(state.ownerMemberNumber, composition.portraitRenderingVersion.await(), payload)
                val bytes = composition.portraitFrames.read(id)
                id to bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
            key = saved.first
            image = saved.second
        }
        loaded = true
    }
    LaunchedEffect(key, geometryVersion) {
        geometry = key?.let { id -> withContext(Dispatchers.IO) { composition.portraitFrames.readGeometry(id) } }
    }
    LaunchedEffect(geometry, image, loaded) {
        if (image != null) onGeometryChanged?.invoke(geometry)
        else if (!loaded) onGeometryChanged?.invoke(null)
    }
    when {
        !loaded -> PortraitSurface(height) { PortraitLoading(quietLoading) }
        image != null -> PortraitSurface(height) {
            Image(checkNotNull(image), stringResource(R.string.portrait_description, state.character.displayName),
                Modifier.fillMaxHeight().aspectRatio(0.5f))
        }
        else -> RenderingPortraitCanvas(state, payload, onRetry, height, geometry, onGeometryChanged, quietLoading) { raw, capturedGeometry ->
            val id = key ?: return@RenderingPortraitCanvas
            val rendered = withContext(Dispatchers.IO) {
                val bytes = decodePortraitFrame(raw) ?: return@withContext null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.takeIf { it.width == 500 && it.height == 1000 } ?: return@withContext null
                composition.portraitFrames.save(id, bytes)
                if (capturedGeometry != null) composition.portraitFrames.saveGeometry(id, capturedGeometry)
                bitmap.asImageBitmap()
            }
            image = rendered
        }
    }
}

private fun decodePortraitFrame(raw: String): ByteArray? {
    if (raw.length > PortraitFrames.MaxFrameBytes * 2) return null
    val data = runCatching { JSONArray("[$raw]").getString(0) }.getOrNull()
        ?.takeIf { it.startsWith("data:image/png;base64,") } ?: return null
    return runCatching { Base64.decode(data.substringAfter(','), Base64.DEFAULT) }.getOrNull()
}

/** Render current room appearances ahead of opening a portrait, one at a time. */
@Composable
internal fun PrewarmRoomPortraits(states: List<CharacterPortraitState>) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val composition = remember(context.applicationContext) {
        (context.applicationContext as MobileBcApplication).appComposition
    }
    val candidates = remember(states) {
        states.filter { it.version.normalizedBcVersionOrNull() == PortraitVersion }
            .mapNotNull { state -> runCatching { state.ownerMemberNumber to portraitPayload(state.character) }.getOrNull() }
    }
    if (candidates.isEmpty()) return
    val view = remember(composition) {
        BcPortraitWebView(context.applicationContext, composition.portraitResources).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false
            isClickable = false
        }
    }
    DisposableEffect(view) { onDispose { view.release() } }
    AndroidView(factory = { view }, modifier = Modifier.size(1.dp).alpha(0f))
    LaunchedEffect(view, lifecycle, candidates) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            view.setActive(true)
            try {
                for ((owner, payload) in candidates) {
                    val id = withContext(Dispatchers.IO) {
                        portraitFrameKey(owner, composition.portraitRenderingVersion.await(), payload)
                    }
                    if (withContext(Dispatchers.IO) {
                        composition.portraitFrames.read(id) != null && composition.portraitFrames.readGeometry(id) != null
                    }) continue
                    view.updatePayload(payload)
                    val ready = withTimeoutOrNull(95_000) {
                        snapshotFlow { view.status }.first { it != PortraitStatus.Loading } == PortraitStatus.Ready
                    } == true
                    if (ready) view.captureFrame()?.let { raw ->
                        val geometry = view.captureGeometry()
                        withContext(Dispatchers.IO) {
                            decodePortraitFrame(raw)?.let { bytes ->
                                runCatching {
                                    composition.portraitFrames.save(id, bytes)
                                    if (geometry != null) composition.portraitFrames.saveGeometry(id, geometry)
                                }
                            }
                        }
                    }
                }
            } finally {
                view.setActive(false)
            }
        }
    }
}

@Composable
private fun PortraitSurface(height: Dp? = null, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height ?: minOf(maxWidth, 200.dp) * 2),
            contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun PortraitLoading(quiet: Boolean) {
    val label = stringResource(R.string.portrait_loading)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = if (quiet) Modifier.semantics { contentDescription = label } else Modifier) {
        CircularProgressIndicator(color = if (quiet) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primary)
        if (!quiet) Text(label)
    }
}

@Composable
private fun RenderingPortraitCanvas(
    state: CharacterPortraitState,
    payload: String?,
    onRetry: () -> Unit,
    height: Dp?,
    geometry: PortraitGeometry?,
    onGeometryChanged: ((PortraitGeometry?) -> Unit)?,
    quietLoading: Boolean,
    onRendered: suspend (String, PortraitGeometry?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val interactive = LocalScreenInteractive.current
    val webViews = remember(context.applicationContext) {
        (context.applicationContext as MobileBcApplication).appComposition.portraitWebViews
    }
    val view = remember(webViews) { webViews.acquire() }
    var liveGeometry by remember(view, payload) { mutableStateOf<PortraitGeometry?>(null) }
    DisposableEffect(view, lifecycle, interactive) {
        fun updateVisibility() = view.setActive(interactive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycle.addObserver(observer)
        updateVisibility()
        onDispose {
            lifecycle.removeObserver(observer)
            view.setActive(false)
        }
    }
    LaunchedEffect(view, payload, interactive, lifecycle) {
        view.updatePayload(payload)
        if (interactive) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val completed = withTimeoutOrNull(95_000) {
                snapshotFlow { view.status }.first { it != PortraitStatus.Loading }
                if (view.status == PortraitStatus.Ready || view.status == PortraitStatus.Partial) {
                    liveGeometry = view.captureGeometry()
                    if (view.status == PortraitStatus.Ready) view.captureFrame()?.let { onRendered(it, liveGeometry) }
                }
                true
            }
            if (completed == null) view.loadingTimedOut()
        }
    }
    val effectiveGeometry = liveGeometry ?: geometry
    LaunchedEffect(view.status, effectiveGeometry) {
        onGeometryChanged?.invoke(if (view.status == PortraitStatus.Ready || view.status == PortraitStatus.Partial) effectiveGeometry else null)
    }
    val description = stringResource(R.string.portrait_description, state.character.displayName)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PortraitSurface(height) {
            AndroidView(
                factory = { view },
                onRelease = { webViews.recycle(it) },
                modifier = Modifier.fillMaxHeight().aspectRatio(0.5f)
                    .alpha(if (view.status == PortraitStatus.Ready || view.status == PortraitStatus.Partial) 1f else 0f)
                    .semantics { contentDescription = description },
            )
            when (view.status) {
                PortraitStatus.Ready, PortraitStatus.Partial -> Unit
                PortraitStatus.Loading -> PortraitLoading(quietLoading)
                else -> Column(
                    Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(if (view.status == PortraitStatus.Unsupported) R.string.portrait_unsupported else R.string.portrait_failed))
                }
            }
        }
        if (view.status == PortraitStatus.Failed) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.portrait_retry)) }
        }
        if (view.status == PortraitStatus.Partial) {
            Text(
                if (view.omittedItems.isEmpty()) stringResource(R.string.portrait_partial_images)
                else stringResource(R.string.portrait_partial, view.omittedItems.joinToString(", ", limit = 4)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
