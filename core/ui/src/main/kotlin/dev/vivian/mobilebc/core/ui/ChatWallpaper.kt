package dev.vivian.mobilebc.core.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import dev.vivian.mobilebc.core.model.ChatBackground

@Composable
fun ChatWallpaper(background: ChatBackground, modifier: Modifier = Modifier) {
    background.imageUri?.let { uri ->
        Box(modifier) {
            AsyncImage(model = Uri.parse(uri), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(
                MaterialTheme.colorScheme.background.copy(alpha = background.overlayPercent.coerceIn(0, 100) / 100f),
            ))
        }
    }
}
