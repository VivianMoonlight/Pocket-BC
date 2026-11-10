package dev.vivian.mobilebc.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

@Composable
internal fun ChatImages(text: String, enabled: Boolean) {
    val urls = remember(text) { chatImageLinks(text) }
    urls.forEach { url ->
        key(url) {
            var requested by rememberSaveable(url) { mutableStateOf(false) }
            var expanded by remember(url) { mutableStateOf(false) }
            var failed by remember(url) { mutableStateOf(false) }
            var loaded by remember(url) { mutableStateOf(false) }
            if (!requested || failed) {
                TextButton(enabled = enabled, onClick = { failed = false; requested = true }) {
                    Text(stringResource(if (failed) R.string.core_ui_image_retry else R.string.core_ui_image_load))
                }
            }
            if (requested && !failed) {
                if (!loaded) CircularProgressIndicator(Modifier.size(24.dp))
                AsyncImage(
                    model = url,
                    contentDescription = stringResource(R.string.core_ui_image_expand),
                    contentScale = ContentScale.Fit,
                    onSuccess = { loaded = true },
                    onError = { failed = true; loaded = false },
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                        .clickable(enabled = enabled && loaded) { expanded = true },
                )
            }
            if (expanded && enabled) {
                Dialog(onDismissRequest = { expanded = false }) {
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column {
                            AsyncImage(model = url, contentDescription = stringResource(R.string.core_ui_image),
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp))
                            TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.core_ui_image_close)) }
                        }
                    }
                }
            }
        }
    }
}
