package dev.vivian.mobilebc.feature.profile

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.model.AppAppearance
import dev.vivian.mobilebc.core.model.ThemeMode
import dev.vivian.mobilebc.core.model.ThemePalette
import dev.vivian.mobilebc.core.ui.MobileBcTheme

@Composable
internal fun AppearanceSettingsCard(appearance: AppAppearance, onChange: (AppAppearance) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.profile_theme_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.appearance_mode), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.System -> R.string.appearance_system
                        ThemeMode.Light -> R.string.appearance_light
                        ThemeMode.Dark -> R.string.appearance_dark
                        ThemeMode.Black -> R.string.appearance_black
                    }
                    AppearanceOption(stringResource(label), appearance.mode == mode,
                        { onChange(appearance.copy(mode = mode)) })
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.appearance_palette), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                ThemePalette.entries.filter { it != ThemePalette.Dynamic || Build.VERSION.SDK_INT >= 31 }.forEach { palette ->
                    val label = when (palette) {
                        ThemePalette.Rose -> R.string.appearance_rose
                        ThemePalette.Ocean -> R.string.appearance_ocean
                        ThemePalette.Forest -> R.string.appearance_forest
                        ThemePalette.Lavender -> R.string.appearance_lavender
                        ThemePalette.Graphite -> R.string.appearance_graphite
                        ThemePalette.Dynamic -> R.string.appearance_dynamic
                    }
                    AppearanceOption(stringResource(label), appearance.palette == palette,
                        { onChange(appearance.copy(palette = palette)) }) {
                        MobileBcTheme(appearance = appearance.copy(palette = palette, customColors = emptyMap())) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondary).forEach { color ->
                                    Box(Modifier.size(16.dp).background(color, CircleShape))
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            AdvancedAppearanceSettings(appearance, onChange)
        }
    }
}

@Composable
private fun AppearanceOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        preview()
    }
}
