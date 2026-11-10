package dev.vivian.mobilebc.feature.characterprofile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.vivian.mobilebc.core.ui.MobileBcTopAppBar
import dev.vivian.mobilebc.core.ui.StatusChip
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListKind
import dev.vivian.mobilebc.feature.rooms.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun CharacterProfileScreen(
    uiState: CharacterProfileUiState,
    onBack: () -> Unit,
    onRelationshipListChange: (BcRelationshipListKind, Boolean) -> Unit,
    onRefreshRelationshipActions: () -> Unit,
    onRelationshipAction: (CharacterRelationshipContractAction, Boolean) -> Unit,
    onClearRelationshipMessage: () -> Unit,
    modifier: Modifier = Modifier,
    portraitContent: (@Composable () -> Unit)? = null,
) {
    var destructiveAction by remember(uiState.memberNumber, uiState.relationship?.ownerMemberNumber, uiState.relationship?.accountGeneration, uiState.relationship?.roomPresenceEpoch) { mutableStateOf<CharacterRelationshipContractAction?>(null) }
    LaunchedEffect(uiState.relationship?.actions) {
        if (destructiveAction !in uiState.relationship?.actions.orEmpty()) destructiveAction = null
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val nowMillis by produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            MobileBcTopAppBar(
                title = uiState.displayName,
                subtitle = "#${uiState.memberNumber}",
                labelColor = uiState.labelColor,
                onBack = onBack,
                actions = {
                    when {
                        uiState.isSelf -> StatusChip(
                            stringResource(R.string.character_you),
                            semanticLabel = stringResource(R.string.character_this_is_you),
                        )
                        uiState.isInCurrentRoom -> StatusChip(stringResource(R.string.character_same_room))
                    }
                },
            )
        },
        snackbarHost = {
            uiState.relationshipMessageRes?.let { messageRes ->
                Snackbar(
                    modifier = Modifier
                        .padding(12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    action = {
                        TextButton(onClick = onClearRelationshipMessage) {
                            Text(stringResource(R.string.character_dismiss))
                        }
                    },
                ) {
                    Text(stringResource(messageRes))
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            portraitContent?.let { portrait ->
                item("portrait") { portrait() }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProfileField(stringResource(R.string.character_username), uiState.characterName)
                        ProfileField(stringResource(R.string.character_nickname), uiState.nickname)
                        ProfileField(stringResource(R.string.character_title), uiState.title)
                        ProfileField(stringResource(R.string.character_pronouns), uiState.pronouns)
                        ProfileDescription(uiState.memberNumber, uiState.description)
                        ProfileRelations(stringResource(R.string.character_owner), listOfNotNull(uiState.owner), true, nowMillis)
                        ProfileRelations(stringResource(R.string.character_lovers), uiState.lovers, false, nowMillis)
                        if (!uiState.isInCurrentRoom) {
                            Text(
                                uiState.snapshotUpdatedAtMillis?.let {
                                    stringResource(R.string.character_snapshot_time, it.formatProfileTime(locale))
                                } ?: stringResource(R.string.character_not_in_room),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            uiState.relationship?.let { relationship ->
                item {
                    RelationshipCard(
                        state = relationship,
                        onListChange = onRelationshipListChange,
                        onRefreshActions = onRefreshRelationshipActions,
                        onAction = { action ->
                            if (action.destructive) {
                                destructiveAction = action
                            } else {
                                onRelationshipAction(action, false)
                            }
                        },
                    )
                }
            }

        }
    }

    destructiveAction?.takeIf { it in uiState.relationship?.actions.orEmpty() }?.let { action ->
        AlertDialog(
            onDismissRequest = { destructiveAction = null },
            title = { Text(stringResource(R.string.character_confirm_relationship_change)) },
            text = {
                Text(stringResource(
                    R.string.character_destructive_relationship_warning,
                    stringResource(action.labelRes),
                    uiState.displayName,
                ))
            },
            confirmButton = {
                TextButton(
                    enabled = uiState.relationship?.let { it.canChangeRelationships && !it.operationInFlight } == true,
                    onClick = {
                        destructiveAction = null
                        onRelationshipAction(action, true)
                    },
                ) { Text(stringResource(R.string.character_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { destructiveAction = null }) {
                    Text(stringResource(R.string.character_cancel))
                }
            },
        )
    }
}

@Composable
private fun RelationshipCard(
    state: CharacterRelationshipUiState,
    onListChange: (BcRelationshipListKind, Boolean) -> Unit,
    onRefreshActions: () -> Unit,
    onAction: (CharacterRelationshipContractAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.character_relationship), style = MaterialTheme.typography.titleMedium)
            ProfileField(stringResource(R.string.character_they_whitelist_me), state.theyWhitelistMe.toKnownText())
            ProfileField(stringResource(R.string.character_they_blacklist_me), state.theyBlacklistMe.toKnownText())
            if (!state.canChangeRelationships) {
                Text(
                    stringResource(R.string.character_connection_required),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.operationInFlight) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.character_operation_pending))
                }
            }

            RelationshipButtonRow(
                firstLabel = if (state.iFriendThem) {
                    stringResource(R.string.character_remove_friend)
                } else {
                    stringResource(R.string.character_add_friend)
                },
                firstClick = { onListChange(BcRelationshipListKind.Friend, !state.iFriendThem) },
                secondLabel = if (state.iIgnoreThem) {
                    stringResource(R.string.character_stop_ignoring)
                } else {
                    stringResource(R.string.character_ignore)
                },
                secondClick = { onListChange(BcRelationshipListKind.Ignore, !state.iIgnoreThem) },
                enabled = state.canChangeRelationships && !state.operationInFlight,
            )
            RelationshipButtonRow(
                firstLabel = if (state.iWhitelistThem) {
                    stringResource(R.string.character_remove_whitelist)
                } else {
                    stringResource(R.string.character_whitelist)
                },
                firstClick = { onListChange(BcRelationshipListKind.White, !state.iWhitelistThem) },
                secondLabel = if (state.iBlacklistThem) {
                    stringResource(R.string.character_remove_blacklist)
                } else {
                    stringResource(R.string.character_blacklist)
                },
                secondClick = { onListChange(BcRelationshipListKind.Black, !state.iBlacklistThem) },
                enabled = state.canChangeRelationships && !state.operationInFlight,
            )

            state.actions.forEach { action ->
                OutlinedButton(
                    onClick = { onAction(action) },
                    enabled = state.canChangeRelationships && !state.operationInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(action.labelRes)) }
            }
            if (state.canRefreshCapabilities) {
                TextButton(
                    onClick = onRefreshActions,
                    enabled = !state.operationInFlight,
                ) {
                    Text(
                        if (state.capabilitiesLoaded) {
                            stringResource(R.string.character_refresh_relationship_actions)
                        } else {
                            stringResource(R.string.character_load_relationship_actions)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationshipButtonRow(
    firstLabel: String,
    firstClick: () -> Unit,
    secondLabel: String,
    secondClick: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = firstClick,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { Text(firstLabel) }
        OutlinedButton(
            onClick = secondClick,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { Text(secondLabel) }
    }
}

@Composable
private fun ProfileDescription(memberNumber: Int, description: String?) {
    var expanded by rememberSaveable(memberNumber, description) { mutableStateOf(false) }
    var truncated by remember(memberNumber, description) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.character_description),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded || truncated) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.character_collapse_description else R.string.character_expand_description))
                }
            }
        }
        Text(
            description?.takeIf(String::isNotBlank) ?: stringResource(R.string.character_no_description),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
        )
    }
}

@Composable
private fun ProfileField(label: String, value: String?, placeholder: String = stringResource(R.string.character_not_available)) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.takeIf(String::isNotBlank) ?: placeholder, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Long.formatProfileTime(locale: Locale): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.SHORT)
    .withLocale(locale)
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(this))

@Composable
private fun Boolean?.toKnownText(): String = when (this) {
    true -> stringResource(R.string.character_yes)
    false -> stringResource(R.string.character_no)
    null -> stringResource(R.string.character_unknown_outside_room)
}

@Composable
private fun ProfileRelations(
    label: String,
    relations: List<dev.vivian.mobilebc.core.model.BcProfileRelation>,
    ownership: Boolean,
    nowMillis: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (relations.isEmpty()) Text(stringResource(R.string.character_not_available), style = MaterialTheme.typography.bodyMedium)
        relations.forEach { relation ->
            Text(relation.memberNumber?.let { stringResource(R.string.character_relation_member, relation.name, it) }
                ?: relation.name, style = MaterialTheme.typography.bodyMedium)
            val minutes = relationDurationMinutes(relation.startEpochMillis, nowMillis)
            val duration = minutes?.let {
                stringResource(R.string.character_relation_duration, it / 1440, (it / 60) % 24, it % 60)
            } ?: stringResource(R.string.character_relation_duration_unknown)
            Text(
                stringResource(R.string.character_relation_details,
                    stringResource(relationStageLabel(ownership, relation.stage)), duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
