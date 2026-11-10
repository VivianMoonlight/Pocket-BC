package dev.vivian.mobilebc.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.net.URI
import android.content.Context
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder

fun configureChatImageLoader(context: Context) {
    Coil.setImageLoader(
        ImageLoader.Builder(context.applicationContext)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .build(),
    )
}

@Composable
internal fun ChatAvatar(name: String, avatarUrl: String?, modifier: Modifier = Modifier) {
    val url = remember(avatarUrl) { chatAvatarUrl(avatarUrl) }
    var loaded by remember(url) { mutableStateOf(false) }
    val description = stringResource(R.string.core_ui_avatar, name)
    Box(
        modifier = modifier.size(36.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                text = name.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = {
                    loaded = false
                    android.util.Log.w("ChatAvatar", "Avatar request failed for ${URI(url).host}: ${it.result.throwable.javaClass.simpleName}")
                },
                onLoading = { loaded = false },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

internal fun chatAvatarUrl(value: String?): String? {
    val url = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    return url.takeIf {
        uri.scheme?.lowercase() in setOf("http", "https") &&
            uri.userInfo == null && lianImageHosts.any { host ->
                uri.host?.lowercase()?.let { it == host || it.endsWith(".$host") } == true
            }
    }?.let { if (uri.scheme.equals("http", ignoreCase = true)) "https" + it.substring(it.indexOf(':')) else it }
}

private val lianImageHosts = setOf(
    "github.io", "gitlab.io", "ibb.co", "imgbb.com", "imgchest.com", "imgur.com", "postimg.cc", "hd-r.icu",
)
