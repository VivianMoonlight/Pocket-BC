package dev.vivian.mobilebc.feature.profile

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.vivian.mobilebc.core.model.AppAppearance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.vivian.mobilebc.core.model.AppLanguage
import dev.vivian.mobilebc.core.model.BcNotificationChannelDiagnostics
import dev.vivian.mobilebc.core.model.BcNotificationChannelKind
import dev.vivian.mobilebc.core.model.BcNotificationSettings
import dev.vivian.mobilebc.core.model.BcNotificationDiagnostics
import dev.vivian.mobilebc.core.ui.StatusChip
import dev.vivian.mobilebc.core.ui.MobileBcIcons
import dev.vivian.mobilebc.core.ui.PageTransition
import dev.vivian.mobilebc.core.ui.InteractionBoundary

@Composable
fun ProfileScreen(
    uiState: ProfileUiState,
    appearance: AppAppearance,
    onAppearanceChange: (AppAppearance) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onAutoJoinRoomAfterReconnectChange: (Boolean) -> Unit,
    onExportPacketLogs: () -> Unit,
    onClearPacketLogs: () -> Unit,
    onSuppressRoomMessagesWhenForegroundChange: (Boolean) -> Unit,
    onSuppressDirectMessagesWhenForegroundChange: (Boolean) -> Unit,
    onSuppressSystemAlertsWhenForegroundChange: (Boolean) -> Unit,
    onNotificationRoutingChange: (BcNotificationSettings) -> Unit,
    onDeobfuscationEnabledChange: (Boolean) -> Unit,
    onRefreshNotificationDiagnostics: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenNotificationChannelSettings: (String) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onOpenLogin: (String?) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
) {
    var pageName by rememberSaveable { mutableStateOf(ProfileSettingsPage.Home.name) }
    val page = ProfileSettingsPage.fromSavedName(pageName)
    var confirmLogout by rememberSaveable(uiState.activeAccountName) { mutableStateOf(false) }
    LaunchedEffect(isVisible, uiState.isSwitchingAccount) {
        if (!isVisible || uiState.isSwitchingAccount) confirmLogout = false
    }
    if (isVisible && confirmLogout && !uiState.isSwitchingAccount) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.profile_logout_confirm_title)) },
            text = { Text(stringResource(R.string.profile_logout_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; onLogout() }, enabled = !uiState.isLoggingOut) {
                    Text(stringResource(R.string.profile_logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.profile_cancel)) }
            },
        )
    }
    val homeListState = rememberLazyListState()
    BackHandler(enabled = isVisible && page != ProfileSettingsPage.Home) {
        pageName = ProfileSettingsPage.Home.name
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshDiagnostics by rememberUpdatedState(onRefreshNotificationDiagnostics)
    DisposableEffect(lifecycleOwner, isVisible) {
        val observer = LifecycleEventObserver { _, event ->
            if (isVisible && event == Lifecycle.Event.ON_RESUME) refreshDiagnostics()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (isVisible) refreshDiagnostics()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PageTransition(target = page, backward = page == ProfileSettingsPage.Home) { renderedPage ->
        InteractionBoundary(isVisible && renderedPage == page) {
            if (renderedPage == ProfileSettingsPage.Home) {
                ProfileMenuHome(
                    uiState = uiState,
                    onOpenPage = { pageName = it.name },
                    listState = homeListState,
                    modifier = modifier,
                )
            } else {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            SettingsPageHeader(
                                title = stringResource(renderedPage.titleRes),
                                subtitle = stringResource(renderedPage.subtitleRes),
                                onBack = { pageName = ProfileSettingsPage.Home.name },
                            )
                        }
                        when (renderedPage) {
                            ProfileSettingsPage.Account -> {
                                item { ActiveAccountCard(uiState = uiState) }
                                item {
                                    SavedAccountsPanel(
                                        accounts = uiState.savedAccounts,
                                        activeAccountName = uiState.activeAccountName,
                                        isSwitchingAccount = uiState.isSwitchingAccount,
                                        isLoggingOut = uiState.isLoggingOut,
                                        switchingToAccountName = uiState.switchingToAccountName,
                                        accountSwitchMessage = uiState.accountSwitchMessage,
                                        accountSwitchMessageIsError = uiState.accountSwitchMessageIsError,
                                        onSwitchAccount = onSwitchAccount,
                                        onOpenLogin = onOpenLogin,
                                    )
                                }
                                item {
                                    OutlinedButton(
                                        onClick = { onOpenLogin(null) },
                                        enabled = !uiState.isLoggingOut && !uiState.isSwitchingAccount,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.profile_add_or_login))
                                    }
                                }
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        stringResource(R.string.profile_bc_version_title),
                                                        style = MaterialTheme.typography.bodyLarge,
                                                    )
                                                    Text(
                                                        stringResource(R.string.profile_bc_version_summary),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                Text(
                                                    text = uiState.systemBcVersion
                                                        .takeIf(String::isNotBlank)
                                                        ?.let { version -> "BC $version" }
                                                        ?: stringResource(R.string.profile_bc_version_unconfirmed),
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                            }
                                            TextButton(
                                                onClick = { onOpenLogin(uiState.activeAccountName) },
                                                enabled = !uiState.isLoggingOut && !uiState.isSwitchingAccount,
                                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                                            ) {
                                                Text(stringResource(R.string.profile_confirm_or_change_version))
                                            }
                                        }
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth()
                                                    .toggleable(
                                                        value = uiState.autoJoinRoomAfterReconnect,
                                                        role = Role.Switch,
                                                        onValueChange = onAutoJoinRoomAfterReconnectChange,
                                                    )
                                                    .padding(16.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        stringResource(R.string.profile_auto_rejoin_title),
                                                        style = MaterialTheme.typography.bodyLarge,
                                                    )
                                                    Text(
                                                        stringResource(R.string.profile_auto_rejoin_summary),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                Switch(
                                                    checked = uiState.autoJoinRoomAfterReconnect,
                                                    onCheckedChange = null,
                                                )
                                            }
                                        }
                                        if (uiState.autoJoinSaveFailed) {
                                            Text(
                                                text = stringResource(R.string.profile_auto_rejoin_save_failed),
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                            )
                                        }
                                    }
                                }
                                item {
                                    Button(
                                        onClick = { confirmLogout = true },
                                        enabled = !uiState.isLoggingOut && !uiState.isSwitchingAccount,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(if (uiState.isLoggingOut) R.string.profile_logging_out else R.string.profile_logout))
                                    }
                                }
                            }
                            ProfileSettingsPage.Notifications -> {
                                item(key = "notifications-${uiState.activeAccountName}") {
                                    NotificationSettingsPanel(
                                        settings = uiState.notificationSettings,
                                        onNotificationRoutingChange = onNotificationRoutingChange,
                                        onSuppressRoomMessagesWhenForegroundChange = onSuppressRoomMessagesWhenForegroundChange,
                                        onSuppressDirectMessagesWhenForegroundChange = onSuppressDirectMessagesWhenForegroundChange,
                                        onSuppressSystemAlertsWhenForegroundChange = onSuppressSystemAlertsWhenForegroundChange,
                                        diagnostics = uiState.notificationDiagnostics,
                                        onRefreshDiagnostics = onRefreshNotificationDiagnostics,
                                        onOpenNotificationSettings = onOpenNotificationSettings,
                                        onOpenNotificationChannelSettings = onOpenNotificationChannelSettings,
                                    )
                                }
                                item {
                                    DeobfuscationSettingsPanel(
                                        enabled = uiState.deobfuscationEnabled,
                                        onEnabledChange = onDeobfuscationEnabledChange,
                                    )
                                }
                            }
                            ProfileSettingsPage.Diagnostics -> item { PacketLogPanel(
                                packetLogs = uiState.packetLogs,
                                packetLogCount = uiState.packetLogCount,
                                onExport = onExportPacketLogs,
                                onClear = onClearPacketLogs,
                            ) }
                            ProfileSettingsPage.Appearance -> {
                                item { AppearanceSettingsCard(appearance, onAppearanceChange) }
                                item { LanguageSettingsCard(appLanguage, onAppLanguageChange) }
                            }
                            ProfileSettingsPage.Home -> Unit
                        }
                    }
                }
            }
        }
    }
}

internal enum class ProfileSettingsPage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
) {
    Home(R.string.profile_settings_title, R.string.profile_settings_summary),
    Account(R.string.profile_account_title, R.string.profile_account_summary),
    Notifications(R.string.profile_notifications_title, R.string.profile_notifications_summary),
    Appearance(R.string.profile_appearance_title, R.string.profile_appearance_summary),
    Diagnostics(R.string.profile_diagnostics_title, R.string.profile_diagnostics_summary);

    companion object {
        fun fromSavedName(name: String): ProfileSettingsPage = when (name) {
            "Connection" -> Account
            "TextDisplay" -> Notifications
            else -> entries.firstOrNull { it.name == name } ?: Home
        }
    }
}

@Composable
private fun LanguageSettingsCard(
    selected: AppLanguage,
    onSelected: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.selectableGroup().padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.profile_language_title),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.profile_language_summary),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppLanguage.entries.forEach { language ->
                val label = when (language) {
                    AppLanguage.System -> stringResource(R.string.profile_language_system)
                    AppLanguage.English -> stringResource(R.string.profile_language_english)
                    AppLanguage.SimplifiedChinese -> stringResource(R.string.profile_language_simplified_chinese)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = language == selected,
                            role = Role.RadioButton,
                            onClick = { if (language != selected) onSelected(language) },
                        )
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = language == selected,
                        onClick = null,
                    )
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun ContactsScreen(
    relationships: ProfileRelationshipsUiState,
    onOpenCharacterProfile: (Int) -> Unit,
    onBack: () -> Unit,
    selfMemberNumber: Int?,
) {
    var query by rememberSaveable(selfMemberNumber) { mutableStateOf("") }
    androidx.compose.material3.Scaffold(
        topBar = {
            dev.vivian.mobilebc.core.ui.MobileBcTopAppBar(
                title = stringResource(R.string.profile_relationships_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (relationships.isLoaded) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.profile_contacts_search)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                TextButton(onClick = { query = "" }) { Text(stringResource(R.string.profile_clear)) }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            relationshipSettingsItems(relationships, query, onOpenCharacterProfile)
            if (query.isBlank()) selfMemberNumber?.let { memberNumber ->
                item {
                    TextButton(onClick = { onOpenCharacterProfile(memberNumber) }) {
                        Text(stringResource(R.string.profile_character_bio))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.relationshipSettingsItems(
    relationships: ProfileRelationshipsUiState,
    query: String,
    onOpenCharacterProfile: (Int) -> Unit,
) {
    if (!relationships.isLoaded) {
        item(key = "relationships-unavailable") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.profile_relationships_unavailable), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.profile_relationships_unavailable_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    val needle = query.trim().removePrefix("#")
    val allMembers = listOfNotNull(relationships.owner) + relationships.lovers + relationships.submissives +
        relationships.friends + relationships.ignored + relationships.whitelist + relationships.blacklist
    if (needle.isNotEmpty() && allMembers.none { it.matchesContactQuery(needle) }) {
        item(key = "no-matches") { Text(stringResource(R.string.profile_contacts_no_matches)) }
    }
    relationshipMemberItems(
        sectionKey = "friends",
        titleRes = R.string.profile_friends,
        members = relationships.friends,
        emptyTextRes = R.string.profile_no_friends,
        onOpenCharacterProfile = onOpenCharacterProfile,
        query = needle,
    )
    relationshipMemberItems(
        sectionKey = "owner",
        titleRes = R.string.profile_owner,
        members = listOfNotNull(relationships.owner),
        emptyTextRes = R.string.profile_no_owner,
        onOpenCharacterProfile = onOpenCharacterProfile,
        query = needle,
    )
    relationshipMemberItems(
        sectionKey = "lovers",
        titleRes = R.string.profile_lovers,
        members = relationships.lovers,
        emptyTextRes = R.string.profile_no_lovers,
        onOpenCharacterProfile = onOpenCharacterProfile,
        query = needle,
    )
    relationshipMemberItems(
        sectionKey = "submissives",
        titleRes = R.string.profile_submissives,
        members = relationships.submissives,
        emptyTextRes = R.string.profile_no_submissives,
        onOpenCharacterProfile = onOpenCharacterProfile,
        query = needle,
    )
    relationshipMemberItems(
        sectionKey = "ignored",
        titleRes = R.string.profile_ignored,
        members = relationships.ignored,
        emptyTextRes = R.string.profile_nobody_ignored,
        onOpenCharacterProfile = onOpenCharacterProfile,
        query = needle,
    )
    relationshipMemberItems(
        sectionKey = "whitelist",
        titleRes = R.string.profile_whitelist,
        members = relationships.whitelist,
        emptyTextRes = R.string.profile_whitelist_empty,
        onOpenCharacterProfile = onOpenCharacterProfile,
        query = needle,
    )
    relationshipMemberItems(
        sectionKey = "blacklist",
        titleRes = R.string.profile_blacklist,
        members = relationships.blacklist,
        emptyTextRes = R.string.profile_blacklist_empty,
        onOpenCharacterProfile = onOpenCharacterProfile,
        query = needle,
    )
    if (needle.isEmpty()) item(key = "relationships-summary") { RelationshipSummaryCard(relationships) }
}

private fun ProfileRelationshipMemberUiItem.matchesContactQuery(query: String): Boolean =
    displayName.contains(query, ignoreCase = true) || memberNumber.toString().contains(query)

@Composable
private fun RelationshipSummaryCard(
    relationships: ProfileRelationshipsUiState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.profile_relationship_account), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RelationshipMetric(
                    label = stringResource(R.string.profile_money),
                    value = relationships.money?.toString() ?: stringResource(R.string.profile_unknown),
                    modifier = Modifier.weight(1f),
                )
                RelationshipMetric(
                    label = stringResource(R.string.profile_dominant),
                    value = relationships.dominantReputation?.toString() ?: stringResource(R.string.profile_unknown),
                    modifier = Modifier.weight(1f),
                )
                RelationshipMetric(
                    label = stringResource(R.string.profile_friend_requests),
                    value = relationships.pendingFriendRequestCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.profile_relationship_account_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun RelationshipMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

private fun LazyListScope.relationshipMemberItems(
    sectionKey: String,
    @StringRes titleRes: Int,
    members: List<ProfileRelationshipMemberUiItem>,
    @StringRes emptyTextRes: Int,
    onOpenCharacterProfile: (Int) -> Unit,
    query: String,
) {
    val visibleMembers = if (query.isEmpty()) members else members.filter { it.matchesContactQuery(query) }
    if (query.isNotEmpty() && visibleMembers.isEmpty()) return
    item(key = "$sectionKey-heading") {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Text(
                        visibleMembers.size.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (visibleMembers.isEmpty()) {
                    Text(
                        text = stringResource(emptyTextRes),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    items(visibleMembers, key = { "$sectionKey-${it.memberNumber}" }, contentType = { "relationship-member" }) { member ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenCharacterProfile(member.memberNumber) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        member.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "#${member.memberNumber} · ${member.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.profile_open),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DeobfuscationSettingsPanel(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_deobfuscation_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.profile_deobfuscation_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = null)
            }
            Text(
                stringResource(R.string.profile_deobfuscation_example),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsPageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = MobileBcIcons.Back, contentDescription = stringResource(R.string.profile_back))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SettingsMenuGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun ActiveAccountCard(uiState: ProfileUiState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (uiState.activeAccountName != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_active_account), style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = if (uiState.activeAccountName == null) {
                            stringResource(R.string.profile_no_account)
                        } else uiState.accountTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(stringResource(uiState.statusRes))
            }
            Text(
                text = if (uiState.activeAccountName == null) {
                    stringResource(R.string.profile_login_to_start)
                } else uiState.accountSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.isSwitchingAccount) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = uiState.switchingToAccountName?.let {
                            stringResource(R.string.profile_switching_to, it)
                        } ?: stringResource(R.string.profile_switching_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedAccountsPanel(
    accounts: List<ProfileSavedAccountUiItem>,
    activeAccountName: String?,
    isSwitchingAccount: Boolean,
    isLoggingOut: Boolean,
    switchingToAccountName: String?,
    accountSwitchMessage: String?,
    accountSwitchMessageIsError: Boolean,
    onSwitchAccount: (String) -> Unit,
    onOpenLogin: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_saved_accounts), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = activeAccountName?.let {
                            stringResource(R.string.profile_active_account_name, it)
                        } ?: stringResource(R.string.profile_no_active_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSwitchingAccount) {
                    StatusChip(
                        text = switchingToAccountName?.let {
                            stringResource(R.string.profile_switching_to_short, it)
                        } ?: stringResource(R.string.profile_switching),
                    )
                }
            }
            if (accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.profile_no_saved_accounts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                accounts.forEach { account ->
                    SavedAccountRow(
                        account = account,
                        onSwitch = { onSwitchAccount(account.accountName) },
                        onOpenLogin = { onOpenLogin(account.accountName) },
                        allowLogin = !isSwitchingAccount && !isLoggingOut,
                    )
                }
            }
            accountSwitchMessage?.let { message ->
                AccountSwitchMessage(
                    message = message,
                    isError = accountSwitchMessageIsError,
                )
            }
        }
    }
}

@Composable
private fun SavedAccountRow(
    account: ProfileSavedAccountUiItem,
    onSwitch: () -> Unit,
    onOpenLogin: () -> Unit,
    allowLogin: Boolean,
    modifier: Modifier = Modifier,
) {
    val canRepairLogin = allowLogin && account.status in setOf(
        ProfileSavedAccountStatus.CredentialsRequired,
        ProfileSavedAccountStatus.VersionRequired,
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (account.isCurrent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        account.accountName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(stringResource(when (account.status) {
                    ProfileSavedAccountStatus.Current -> R.string.profile_current
                    ProfileSavedAccountStatus.Switching -> R.string.profile_switching
                    ProfileSavedAccountStatus.CredentialsRequired -> R.string.profile_credentials_required
                    ProfileSavedAccountStatus.VersionRequired -> R.string.profile_version_required
                    ProfileSavedAccountStatus.Saved -> R.string.profile_saved
                }))
                if (account.isSwitchTarget) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (!account.isCurrent) {
                OutlinedButton(
                    onClick = if (canRepairLogin) onOpenLogin else onSwitch,
                    enabled = account.canSwitch || canRepairLogin,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(when (account.status) {
                        ProfileSavedAccountStatus.Switching -> R.string.profile_switching_account
                        ProfileSavedAccountStatus.CredentialsRequired -> R.string.profile_login_required
                        ProfileSavedAccountStatus.VersionRequired -> R.string.profile_confirm_version
                        else -> R.string.profile_switch
                    }))
                }
            }
        }
    }
}

@Composable
private fun AccountSwitchMessage(message: String, isError: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


@Composable
private fun NotificationSettingsPanel(
    settings: ProfileNotificationSettingsUiState,
    onSuppressRoomMessagesWhenForegroundChange: (Boolean) -> Unit,
    onSuppressDirectMessagesWhenForegroundChange: (Boolean) -> Unit,
    onSuppressSystemAlertsWhenForegroundChange: (Boolean) -> Unit,
    onNotificationRoutingChange: (BcNotificationSettings) -> Unit,
    diagnostics: BcNotificationDiagnostics,
    onRefreshDiagnostics: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenNotificationChannelSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.profile_notifications_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.profile_notifications_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            NotificationChannelSettingsRows(
                diagnostics = diagnostics,
                onOpenNotificationChannelSettings = onOpenNotificationChannelSettings,
            )

            NotificationRulesEditor(settings.routing, onNotificationRoutingChange)

            ForegroundSuppressionRow(
                title = stringResource(R.string.profile_hide_room_notifications),
                checked = settings.suppressRoomMessagesWhenForeground,
                onCheckedChange = onSuppressRoomMessagesWhenForegroundChange,
            )
            ForegroundSuppressionRow(
                title = stringResource(R.string.profile_hide_private_notifications),
                checked = settings.suppressDirectMessagesWhenForeground,
                onCheckedChange = onSuppressDirectMessagesWhenForegroundChange,
            )
            ForegroundSuppressionRow(
                title = stringResource(R.string.profile_hide_system_alerts),
                checked = settings.suppressSystemAlertsWhenForeground,
                onCheckedChange = onSuppressSystemAlertsWhenForegroundChange,
            )
            NotificationDiagnosticsSection(
                diagnostics = diagnostics,
                onRefresh = onRefreshDiagnostics,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )
        }
    }
}

@Composable
private fun NotificationChannelSettingsRows(
    diagnostics: BcNotificationDiagnostics,
    onOpenNotificationChannelSettings: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        diagnostics.channels
            .forEach { channel ->
                NotificationChannelStatusRow(
                    channel = channel,
                    onOpenChannelSettings = { onOpenNotificationChannelSettings(channel.id) },
                )
            }
    }
}

@Composable
private fun NotificationChannelStatusRow(
    channel: BcNotificationChannelDiagnostics,
    onOpenChannelSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = channel.purpose,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = channel.localizedDetailText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(channel.localizedStatusLabel())
            OutlinedButton(onClick = onOpenChannelSettings) {
                Text(stringResource(R.string.profile_open))
            }
        }
    }
}

@Composable
private fun NotificationDiagnosticsSection(
    diagnostics: BcNotificationDiagnostics,
    onRefresh: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (diagnostics.hasIssue) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (diagnostics.hasIssue) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_android_delivery_status), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = diagnostics.localizedSummary(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusChip(
                    text = stringResource(if (diagnostics.hasIssue) R.string.profile_needs_check else R.string.profile_ok),
                )
            }

            NotificationDiagnosticFactRow(
                label = stringResource(R.string.profile_app_notifications),
                value = if (diagnostics.appNotificationsEnabled) {
                    stringResource(R.string.profile_allowed)
                } else {
                    stringResource(R.string.profile_blocked)
                },
            )
            if (diagnostics.postNotificationsPermissionRequired) {
                NotificationDiagnosticFactRow(
                    label = stringResource(R.string.profile_android_permission),
                    value = if (diagnostics.postNotificationsPermissionGranted) {
                        stringResource(R.string.profile_granted)
                    } else {
                        stringResource(R.string.profile_missing)
                    },
                )
            }
            NotificationDiagnosticFactRow(
                label = stringResource(R.string.profile_notification_channels),
                value = if (diagnostics.channelsSupported) {
                    stringResource(
                        R.string.profile_channels_usable,
                        diagnostics.channels.count { !it.isBlocked && !it.isMissing },
                        diagnostics.channels.size,
                    )
                } else {
                    stringResource(R.string.profile_channels_not_used)
                },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenNotificationSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profile_system_settings))
                }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.profile_refresh))
                }
            }

        }
    }
}

@Composable
private fun NotificationDiagnosticFactRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ForegroundSuppressionRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun PacketLogPanel(
    packetLogs: List<PacketLogUiItem>,
    packetLogCount: Int,
    onExport: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var logsCleared by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(packetLogCount) {
        if (packetLogCount > 0) logsCleared = false
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.profile_packet_logs), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.profile_packet_log_summary, packetLogCount, packetLogs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onClear(); logsCleared = true },
                    enabled = packetLogCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.profile_clear))
                }
                Button(onClick = onExport, enabled = packetLogCount > 0, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_export))
                }
            }
            if (packetLogs.isEmpty()) {
                Text(
                    text = stringResource(if (logsCleared) R.string.profile_logs_cleared else R.string.profile_no_packets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                packetLogs.forEach { item ->
                    PacketLogRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun PacketLogRow(item: PacketLogUiItem, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(item.direction)
                Text(
                    text = item.eventName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = item.payloadPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BcNotificationDiagnostics.localizedSummary(): String = when {
    !appNotificationsEnabled -> stringResource(R.string.profile_notifications_disabled)
    postNotificationsPermissionRequired && !postNotificationsPermissionGranted ->
        stringResource(R.string.profile_notification_permission_missing)
    channels.any { it.isMissing } -> stringResource(R.string.profile_channels_missing_summary)
    channels.any { it.isBlocked } -> stringResource(R.string.profile_channels_blocked_summary)
    channelsSupported -> stringResource(R.string.profile_channels_available_summary)
    else -> stringResource(R.string.profile_notifications_usable_summary)
}

@Composable
private fun BcNotificationChannelDiagnostics.localizedStatusLabel(): String = when {
    isMissing -> stringResource(R.string.profile_missing)
    isBlocked -> stringResource(R.string.profile_blocked)
    else -> stringResource(R.string.profile_system)
}

@Composable
private fun BcNotificationChannelDiagnostics.localizedDetailText(): String {
    val details = buildList {
        add(stringResource(R.string.profile_android_importance, actualImportanceLabel))
        bannerEnabled?.let {
            add(stringResource(if (it) R.string.profile_banner_on else R.string.profile_banner_off))
        }
        soundEnabled?.let {
            add(stringResource(if (it) R.string.profile_sound_on else R.string.profile_sound_off))
        }
        vibrationEnabled?.let {
            add(stringResource(if (it) R.string.profile_vibration_on else R.string.profile_vibration_off))
        }
    }
    return details.joinToString(", ")
}
