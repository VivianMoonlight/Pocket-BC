package dev.vivian.mobilebc.feature.profile

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.core.ui.ChatWallpaper
import dev.vivian.mobilebc.core.ui.MobileBcTheme

@Composable
internal fun AdvancedAppearanceSettings(appearance: AppAppearance, onChange: (AppAppearance) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.advanced_hide else R.string.advanced_show))
    }
    if (expanded) {
        BackgroundEditor(stringResource(R.string.background_room), appearance.roomBackground) {
            onChange(appearance.copy(roomBackground = it))
        }
        BackgroundEditor(stringResource(R.string.background_direct), appearance.directBackground) {
            onChange(appearance.copy(directBackground = it))
        }
        HorizontalDivider()
        Text(stringResource(R.string.custom_colors), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.custom_colors_help), style = MaterialTheme.typography.bodySmall)
        var editing by remember { mutableStateOf<ThemeColorRole?>(null) }
        val labels = androidx.compose.ui.res.stringArrayResource(R.array.theme_color_roles)
        ThemeColorRole.entries.forEachIndexed { index, role ->
            val color = appearance.customColors[role]?.let(::Color) ?: roleColor(MaterialTheme.colorScheme, role)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { editing = role }, modifier = Modifier.weight(1f)) {
                    Text(labels[index], modifier = Modifier.weight(1f))
                    Box(Modifier.size(24.dp).background(color, RoundedCornerShape(4.dp)))
                }
                if (role in appearance.customColors) TextButton(onClick = {
                    onChange(appearance.copy(customColors = appearance.customColors - role))
                }) { Text(stringResource(R.string.color_reset)) }
            }
        }
        if (appearance.customColors.isNotEmpty()) TextButton(onClick = {
            onChange(appearance.copy(customColors = emptyMap()))
        }) { Text(stringResource(R.string.colors_reset_all)) }
        editing?.let { role ->
            ColorEditor(labels[role.ordinal], role, appearance, appearance.customColors[role]?.let(::Color) ?: roleColor(MaterialTheme.colorScheme, role),
                onDismiss = { editing = null }, onSave = { color ->
                    onChange(appearance.copy(customColors = appearance.customColors + (role to color)))
                    editing = null
                })
        }
    }
}

@Composable
private fun BackgroundEditor(title: String, background: ChatBackground, onChange: (ChatBackground) -> Unit) {
    val context = LocalContext.current
    val failure = stringResource(R.string.background_access_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                onChange(background.copy(imageUri = uri.toString()))
            } catch (_: SecurityException) {
                Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
            }
        }
    }
    Text(title, style = MaterialTheme.typography.titleSmall)
    if (background.imageUri != null) {
        Box(Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.background)) {
            ChatWallpaper(background, Modifier.matchParentSize())
            Text(stringResource(R.string.background_preview), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onBackground)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.background_choose)) }
        if (background.imageUri != null) TextButton(onClick = { onChange(background.copy(imageUri = null)) }) {
            Text(stringResource(R.string.background_remove))
        }
    }
    if (background.imageUri != null) {
        Text(stringResource(R.string.background_overlay, background.overlayPercent), style = MaterialTheme.typography.bodySmall)
        Slider(value = background.overlayPercent.toFloat(), onValueChange = {
            onChange(background.copy(overlayPercent = it.toInt()))
        }, valueRange = 0f..100f)
    }
}

@Composable
private fun ColorEditor(title: String, role: ThemeColorRole, appearance: AppAppearance, initial: Color,
                        onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var text by remember(role) { mutableStateOf("%06X".format(initial.toArgb() and 0xFFFFFF)) }
    val value = text.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.or(0xFF000000L)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(stringResource(R.string.color_hex)) },
                isError = value == null, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            if (value != null) {
                listOf(R.string.color_red, R.string.color_green, R.string.color_blue).forEachIndexed { index, label ->
                    val shift = 16 - 8 * index
                    val component = ((value shr shift) and 0xFF).toInt()
                    Text(stringResource(label, component), style = MaterialTheme.typography.bodySmall)
                    Slider(value = component.toFloat(), onValueChange = { next ->
                        val changed = (value and (0xFFL shl shift).inv()) or (next.toInt().toLong() shl shift)
                        text = "%06X".format(changed and 0xFFFFFF)
                    }, valueRange = 0f..255f)
                }
            }
            if (value != null) MobileBcTheme(appearance = appearance.copy(customColors = appearance.customColors + (role to value))) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.color_preview), color = MaterialTheme.colorScheme.onBackground)
                        Surface(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                            Text(stringResource(R.string.color_preview_message), Modifier.padding(8.dp))
                        }
                        val previewColors = appearance.customColors + (role to value)
                        Surface(color = previewColors[ThemeColorRole.ChatHeader]?.let(::Color) ?: MaterialTheme.colorScheme.surface,
                            contentColor = previewColors[ThemeColorRole.ChatHeaderText]?.let(::Color) ?: MaterialTheme.colorScheme.onSurface) {
                            Text(stringResource(R.string.background_room), Modifier.fillMaxWidth().padding(8.dp))
                        }
                        Surface(color = previewColors[ThemeColorRole.ChatMessage]?.let(::Color) ?: MaterialTheme.colorScheme.surface,
                            contentColor = previewColors[ThemeColorRole.ChatMessageText]?.let(::Color) ?: MaterialTheme.colorScheme.onSurface) {
                            Text(stringResource(R.string.color_preview_message), Modifier.fillMaxWidth().padding(8.dp))
                        }
                        Surface(color = previewColors[ThemeColorRole.ChatComposer]?.let(::Color) ?: MaterialTheme.colorScheme.surface,
                            contentColor = previewColors[ThemeColorRole.ChatComposerText]?.let(::Color) ?: MaterialTheme.colorScheme.onSurface) {
                            Text(stringResource(R.string.color_preview_composer), Modifier.fillMaxWidth().padding(8.dp))
                        }
                        Button(onClick = {}) { Text(stringResource(R.string.color_preview_button)) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text(stringResource(R.string.color_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.color_cancel)) } })
}

private fun roleColor(c: ColorScheme, role: ThemeColorRole): Color = when (role) {
    ThemeColorRole.Primary -> c.primary
    ThemeColorRole.OnPrimary -> c.onPrimary
    ThemeColorRole.PrimaryContainer -> c.primaryContainer
    ThemeColorRole.OnPrimaryContainer -> c.onPrimaryContainer
    ThemeColorRole.Secondary -> c.secondary
    ThemeColorRole.OnSecondary -> c.onSecondary
    ThemeColorRole.SecondaryContainer -> c.secondaryContainer
    ThemeColorRole.OnSecondaryContainer -> c.onSecondaryContainer
    ThemeColorRole.Background -> c.background
    ThemeColorRole.OnBackground -> c.onBackground
    ThemeColorRole.Surface -> c.surface
    ThemeColorRole.OnSurface -> c.onSurface
    ThemeColorRole.SurfaceVariant -> c.surfaceVariant
    ThemeColorRole.OnSurfaceVariant -> c.onSurfaceVariant
    ThemeColorRole.Outline -> c.outline
    ThemeColorRole.Error -> c.error
    ThemeColorRole.OnError -> c.onError
    ThemeColorRole.ChatHeader, ThemeColorRole.ChatComposer, ThemeColorRole.ChatMessage -> c.surface
    ThemeColorRole.ChatHeaderText, ThemeColorRole.ChatComposerText, ThemeColorRole.ChatMessageText -> c.onSurface
}
