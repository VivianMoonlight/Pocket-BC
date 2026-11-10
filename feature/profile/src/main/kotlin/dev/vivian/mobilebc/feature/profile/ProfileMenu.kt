package dev.vivian.mobilebc.feature.profile

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.ui.MobileBcIcons

@Composable
internal fun ProfileMenuHome(
    uiState: ProfileUiState,
    onOpenPage: (ProfileSettingsPage) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.profile_settings_title), style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.profile_settings_summary), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(MobileBcIcons.Profile, null, Modifier.padding(12.dp).size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (uiState.activeAccountName == null) stringResource(R.string.profile_no_account) else uiState.accountTitle,
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(uiState.activeAccountName, stringResource(uiState.statusRes)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onOpenPage(ProfileSettingsPage.Account) }) {
                        Text(stringResource(R.string.profile_switch_account))
                    }
                }
            }
            item {
                MenuSection(R.string.profile_preferences_section) {
                    MenuEntry(MobileBcIcons.Bell, R.string.profile_notifications_title, R.string.profile_notifications_summary,
                        notice = if (uiState.notificationDiagnostics.hasIssue) stringResource(R.string.profile_needs_attention) else null,
                        onClick = { onOpenPage(ProfileSettingsPage.Notifications) })
                    MenuEntry(MobileBcIcons.Appearance, R.string.profile_appearance_title, R.string.profile_appearance_summary,
                        onClick = { onOpenPage(ProfileSettingsPage.Appearance) })
                    MenuEntry(MobileBcIcons.Rooms, R.string.profile_account_title, R.string.profile_account_summary,
                        onClick = { onOpenPage(ProfileSettingsPage.Account) })
                }
            }
            item {
                Column {
                    val expandedLabel = stringResource(if (moreExpanded) R.string.profile_expanded else R.string.profile_collapsed)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { stateDescription = expandedLabel }
                        .clickable(role = Role.Button) { moreExpanded = !moreExpanded }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.profile_more), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (moreExpanded) "−" else "+", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AnimatedVisibility(moreExpanded) {
                        SettingsMenuGroup {
                            MenuEntry(MobileBcIcons.Diagnostics, R.string.profile_diagnostics_title, R.string.profile_diagnostics_summary,
                                enabled = moreExpanded, onClick = { onOpenPage(ProfileSettingsPage.Diagnostics) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuSection(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), Modifier.padding(start = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsMenuGroup(content)
    }
}

@Composable
internal fun MenuEntry(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes summary: Int,
    enabled: Boolean = true,
    notice: String? = null,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = color)
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else color)
            if (notice != null) Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = color)
    }
}
