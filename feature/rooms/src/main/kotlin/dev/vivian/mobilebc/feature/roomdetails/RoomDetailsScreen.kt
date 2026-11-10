package dev.vivian.mobilebc.feature.roomdetails

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import dev.vivian.mobilebc.core.ui.AnimatedModalSheet
import androidx.compose.runtime.key
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcRoomMapType
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.ui.bcLabelColor
import dev.vivian.mobilebc.core.ui.AvatarToken
import dev.vivian.mobilebc.core.ui.MobileBcTopAppBar
import dev.vivian.mobilebc.core.ui.StatusChip
import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementAvailabilityReason
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.feature.rooms.R
import dev.vivian.mobilebc.feature.rooms.RoomAdministrationDraft
import dev.vivian.mobilebc.feature.rooms.RoomAdministrationFormSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomDetailsScreen(
    uiState: RoomDetailsUiState,
    onBack: () -> Unit,
    onOpenBio: (Int) -> Unit,
    onOpenInteract: (Int) -> Unit,
    onCloseInteract: () -> Unit,
    onSelectInteractionGroup: (String) -> Unit,
    onSendActivity: (String, String, String?, String?) -> Unit,
    onWearItem: (String, String, String?, Int?) -> Unit,
    onRemoveItem: (String) -> Unit,
    onOpenItemPicker: (Int, String, BcAppearanceGroupKind) -> Unit,
    onClearInteractionMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRoomSettings: () -> Unit = {},
    onCloseRoomSettings: () -> Unit = {},
    onChangeRoomSettingsDraft: (RoomAdministrationDraft) -> Unit = {},
    onSubmitRoomSettings: () -> Unit = {},
    onReloadRoomSettings: () -> Unit = {},
    onOpenMemberManagement: (Int) -> Unit = {},
    onCloseMemberManagement: () -> Unit = {},
    onSelectMemberAdminAction: (BcRoomMemberAdminAction) -> Unit = {},
    onCancelMemberAdminAction: () -> Unit = {},
    onConfirmMemberAdminAction: () -> Unit = {},
    onClearMemberAdminResult: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            MobileBcTopAppBar(
                title = uiState.roomTitle ?: stringResource(R.string.room_details_no_current_room),
                subtitle = roomSubtitle(uiState),
                onBack = onBack,
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                RoomSummaryCard(
                    uiState = uiState,
                    onOpenRoomSettings = onOpenRoomSettings,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.room_details_characters),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .semantics { heading() }
                        .padding(top = 8.dp, bottom = 2.dp),
                )
            }
            if (uiState.characters.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.room_details_no_online_characters),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(uiState.characters, key = { it.memberNumber }) { character ->
                    CharacterRow(
                        character = character,
                        onOpenBio = onOpenBio,
                        onOpenInteract = onOpenInteract,
                        canManage = character.memberNumber in uiState.manageableMemberNumbers,
                        managementEnabled = uiState.wardrobe?.busy != true && uiState.roomForm?.busy != true && (!uiState.memberAdministrationBusy ||
                            uiState.memberManagement?.target?.memberNumber == character.memberNumber),
                        interactionEnabled = uiState.wardrobe?.busy != true,
                        onManage = onOpenMemberManagement,
                    )
                }
            }
        }
    }

    uiState.interactionSheet?.takeIf {
        uiState.interactionMode == RoomDetailsInteractionMode.FullSheet
    }?.let { sheet ->
        key(sheet.target.memberNumber) {
            AnimatedModalSheet(onDismissRequest = onCloseInteract, skipPartiallyExpanded = false) { requestClose ->
                CharacterInteractionSheet(
                    uiState = sheet,
                    onClose = requestClose,
                    onSelectGroup = onSelectInteractionGroup,
                    onSendActivity = onSendActivity,
                    onWearItem = onWearItem,
                    onRemoveItem = onRemoveItem,
                    onOpenItemPicker = onOpenItemPicker,
                    onClearMessage = onClearInteractionMessage,
                )
            }
        }
    }

    uiState.roomForm?.takeIf { it.visible }?.let { form ->
        RoomAdministrationFormSheet(
            state = form,
            onDraftChange = onChangeRoomSettingsDraft,
            onSubmit = onSubmitRoomSettings,
            onClose = onCloseRoomSettings,
            onReload = onReloadRoomSettings,
        )
    }
    uiState.memberManagement?.takeIf { it.visible }?.let { state ->
        RoomMemberManagementSheet(
            state = state,
            onClose = onCloseMemberManagement,
            onSelectAction = onSelectMemberAdminAction,
            onCancelAction = onCancelMemberAdminAction,
            onConfirm = onConfirmMemberAdminAction,
            onClearResult = onClearMemberAdminResult,
        )
    }
}

@Composable
fun ChatRoomActionPickerPanel(
    uiState: RoomDetailsUiState,
    onSelectCharacter: (Int) -> Unit,
    onResetCharacter: () -> Unit,
    onSelectGroup: (String) -> Unit,
    onToggleUnavailable: () -> Unit,
    onSendActivity: (String, String, String?, String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenItems: ((Int) -> Unit)? = null,
    onOpenClothing: ((Int) -> Unit)? = null,
    onWhisper: ((Int) -> Unit)? = null,
    onOpenBio: ((Int) -> Unit)? = null,
    portraitContent: (@Composable (dev.vivian.mobilebc.feature.characterprofile.CharacterPortraitState?, List<InteractionGroupUiState>, (String) -> Unit) -> Unit)? = null,
) = FlatRoomActionDock(
    uiState = uiState,
    onSelectCharacter = onSelectCharacter,
    onResetCharacter = onResetCharacter,
    onSelectGroup = onSelectGroup,
    onToggleUnavailable = onToggleUnavailable,
    onSendActivity = onSendActivity,
    onClose = onClose,
    modifier = modifier,
    onOpenItems = onOpenItems,
    onOpenClothing = onOpenClothing,
    onWhisper = onWhisper,
    onOpenBio = onOpenBio,
    portraitContent = portraitContent,
)

@Composable
internal fun InteractionFeedback(
    uiState: CharacterInteractionSheetUiState,
    onClearMessage: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (uiState.busy) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.room_details_sending), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        uiState.catalogMessage?.let { message ->
            Text(message.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } ?: if (!uiState.catalogReady || !uiState.permissionReady) {
            Text(
                stringResource(R.string.room_feedback_data_preparing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else Unit
        uiState.actionMessage?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message.text(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onClearMessage?.let { clear ->
                    TextButton(onClick = clear) { Text(stringResource(R.string.character_dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun PickerPanelHeader(
    title: String,
    onClose: () -> Unit,
    navigation: @Composable (Modifier) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
        )
        navigation(Modifier.weight(1f))
        TextButton(
            onClick = onClose,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .defaultMinSize(minWidth = 0.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Text(
                text = stringResource(R.string.room_details_close),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ActionPickerNavigation(
    hasTarget: Boolean,
    hasGroup: Boolean,
    onResetCharacter: () -> Unit,
    onResetGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerNavigationStep(
            current = !hasTarget,
            onClick = onResetCharacter,
            label = stringResource(R.string.room_details_step_role),
        )
        PickerNavigationStep(
            current = hasTarget && !hasGroup,
            onClick = onResetGroup,
            enabled = hasTarget,
            label = stringResource(R.string.room_details_step_body_part),
        )
        PickerNavigationStep(
            current = hasTarget && hasGroup,
            onClick = {},
            enabled = false,
            label = stringResource(R.string.room_details_step_action),
        )
    }
}

@Composable
private fun PickerNavigationStep(
    label: String,
    current: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    if (current) {
        StatusChip(label, modifier = Modifier.semantics { selected = true })
    } else {
        FilterChip(selected = false, enabled = enabled, onClick = onClick, label = { Text(label) })
    }
}

@Composable
internal fun ActionPickerCharacterStep(
    characters: List<BcCharacterSnapshot>,
    onSelectCharacter: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.room_details_choose_role), style = MaterialTheme.typography.titleSmall)
        if (characters.isEmpty()) {
            Text(
                stringResource(R.string.room_details_no_online_characters),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            characters.chunked(2).forEach { rowCharacters ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowCharacters.forEach { character ->
                        CharacterPickerCard(
                            character = character,
                            onSelectCharacter = onSelectCharacter,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowCharacters.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterPickerCard(
    character: BcCharacterSnapshot,
    onSelectCharacter: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectCharacter(character.memberNumber) }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarToken(character.displayName)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        character.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = character.accountName?.takeIf { it.isNotBlank() } ?: "#${character.memberNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = bcLabelColor(character.labelColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (character.isSelf) {
                    StatusChip(stringResource(R.string.character_you))
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                Text(
                    text = stringResource(R.string.room_details_choose),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ActionPickerGroupStep(
    target: BcCharacterSnapshot,
    groups: List<InteractionGroupUiState>,
    onSelectGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.room_details_choose_body_part_for, target.displayName),
            style = MaterialTheme.typography.titleSmall,
        )
        if (groups.isEmpty()) {
            Text(
                text = stringResource(R.string.room_details_no_action_body_parts),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BodyPartArrangementGrid(
                groups = groups,
                mode = BodyPartArrangementMode.Action,
                onSelectGroup = onSelectGroup,
            )
        }
    }
}

@Composable
private fun ActionPickerActivityStep(
    target: BcCharacterSnapshot,
    group: InteractionGroupUiState,
    activities: List<InteractionActivityUiState>,
    busy: Boolean,
    showUnavailable: Boolean,
    onToggleUnavailable: () -> Unit,
    onSendActivity: (String, String, String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingActivity by remember(target.memberNumber, group.name) {
        mutableStateOf<InteractionActivityUiState?>(null)
    }
    fun send(activity: InteractionActivityUiState) {
        onSendActivity(
            activity.group,
            activity.name,
            activity.activityAssetGroup,
            activity.activityAssetName,
        )
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${target.displayName} / ${group.displayName}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(
                            if (showUnavailable) R.string.room_details_showing_unavailable_actions
                            else R.string.room_details_only_enabled_actions,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            FilterChip(
                selected = showUnavailable,
                onClick = onToggleUnavailable,
                label = {
                    Text(
                        if (showUnavailable) {
                            stringResource(R.string.room_details_showing_unavailable_actions)
                        } else {
                            stringResource(R.string.room_details_show_unavailable_actions)
                        },
                    )
                },
            )
        }
        if (activities.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.room_details_no_allowed_actions),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(activities, key = { "${it.group}:${it.name}" }) { activity ->
                ActivityActionRow(
                    activity = activity,
                    busy = busy,
                    onClick = {
                        if (activity.effects.isEmpty()) send(activity) else pendingActivity = activity
                    },
                )
            }
        }
    }
    pendingActivity?.let { activity ->
        ActivityConfirmationDialog(
            activity = activity,
            target = target,
            enabled = !busy && activity.enabled,
            onDismiss = { pendingActivity = null },
            onConfirm = {
                pendingActivity = null
                send(activity)
            },
        )
    }
}

@Composable
internal fun ActivityActionRow(
    activity: InteractionActivityUiState,
    busy: Boolean,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    InteractionActionRow(
        title = if (favorite) "⭐ ${activity.displayName}" else activity.displayName,
        subtitle = listOfNotNull(
            activity.effectSummary(),
            stringResource(R.string.room_details_uses_compatible_item).takeIf { activity.usesWornItem },
            activity.warningMessage?.text(),
            activity.permissionMessage?.text(),
        ).joinToString(" • "),
        buttonText = if (!activity.usesWornItem) {
            stringResource(R.string.room_details_send)
        } else {
            stringResource(R.string.room_details_use_item)
        },
        enabled = !busy && activity.enabled,
        modifier = modifier,
        onClick = onClick,
        onLongClick = onToggleFavorite,
        onLongClickLabel = stringResource(if (favorite) R.string.dock_unfavorite else R.string.dock_favorite),
        compact = compact,
    )
}

@Composable
internal fun ActivityConfirmationDialog(
    activity: InteractionActivityUiState,
    target: BcCharacterSnapshot,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.room_details_confirm_action)) },
        text = {
            Text(
                stringResource(
                    R.string.room_details_confirm_action_effect,
                    activity.displayName,
                    target.displayName,
                    activity.effectSummary().orEmpty(),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(stringResource(R.string.room_details_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.room_details_cancel)) }
        },
    )
}

@Composable
private fun InteractionActivityUiState.effectSummary(): String? {
    if (effects.isEmpty()) return null
    return effects.map { effect ->
        when (effect) {
            InteractionActivityEffectUiState.Pose -> stringResource(R.string.room_details_effect_pose)
            InteractionActivityEffectUiState.Expression -> stringResource(R.string.room_details_effect_expression)
            InteractionActivityEffectUiState.PairedItem -> stringResource(R.string.room_details_effect_paired_item)
        }
    }.joinToString(stringResource(R.string.room_details_effect_separator))
}


@Composable
fun ChatRoomItemPickerPanel(
    uiState: RoomDetailsUiState,
    workflowKind: BcAppearanceGroupKind = BcAppearanceGroupKind.Item,
    onSelectCharacter: (Int) -> Unit,
    onResetCharacter: () -> Unit,
    onSelectGroup: (String) -> Unit,
    onResetGroup: () -> Unit,
    onSelectOperation: (String) -> Unit,
    onSelectItem: (String) -> Unit,
    onBackToCatalog: () -> Unit,
    onUpdateQuery: (String) -> Unit,
    onToggleUnavailable: () -> Unit,
    onSendEntry: (InteractionItemPickerEntryUiState) -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenActions: ((Int) -> Unit)? = null,
    onOpenBio: ((Int) -> Unit)? = null,
    portraitContent: (@Composable (dev.vivian.mobilebc.feature.characterprofile.CharacterPortraitState) -> Unit)? = null,
    interactionPortraitContent: (@Composable (dev.vivian.mobilebc.feature.characterprofile.CharacterPortraitState?, List<InteractionGroupUiState>, (String) -> Unit) -> Unit)? = null,
    onEditText: ((BcCharacterSnapshot, String, Map<String, String>) -> Unit)? = null,
    onUnlockWithCode: ((BcCharacterSnapshot, String, String) -> Unit)? = null,
    onConfigureLock: ((BcCharacterSnapshot, String, dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration) -> Unit)? = null,
    onChangeTimer: ((BcCharacterSnapshot, String, dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange) -> Unit)? = null,
) = FlatRoomItemDock(
    uiState = uiState,
    workflowKind = workflowKind,
    onSelectCharacter = onSelectCharacter,
    onResetCharacter = onResetCharacter,
    onSelectGroup = onSelectGroup,
    onResetGroup = onResetGroup,
    onSelectOperation = onSelectOperation,
    onSelectItem = onSelectItem,
    onBackToCatalog = onBackToCatalog,
    onUpdateQuery = onUpdateQuery,
    onToggleUnavailable = onToggleUnavailable,
    onSendEntry = onSendEntry,
    onLoadMore = onLoadMore,
    onClose = onClose,
    modifier = modifier,
    onOpenActions = onOpenActions,
    onOpenBio = onOpenBio,
    portraitContent = portraitContent,
    interactionPortraitContent = interactionPortraitContent,
    onEditText = onEditText,
    onUnlockWithCode = onUnlockWithCode,
    onConfigureLock = onConfigureLock,
    onChangeTimer = onChangeTimer,
)

@Composable
private fun ItemPickerNavigation(
    hasTarget: Boolean,
    hasGroup: Boolean,
    hasOptions: Boolean,
    onResetCharacter: () -> Unit,
    onResetGroup: () -> Unit,
    onBackToCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerNavigationStep(
            current = !hasTarget,
            onClick = onResetCharacter,
            label = stringResource(R.string.room_details_step_role),
        )
        PickerNavigationStep(
            current = hasTarget && !hasGroup,
            onClick = onResetGroup,
            enabled = hasTarget,
            label = stringResource(R.string.room_details_step_group),
        )
        PickerNavigationStep(
            current = hasGroup && !hasOptions,
            onClick = onBackToCatalog,
            enabled = hasGroup,
            label = stringResource(R.string.room_details_step_item),
        )
        PickerNavigationStep(
            current = hasOptions,
            onClick = {},
            enabled = false,
            label = stringResource(R.string.room_details_step_option),
        )
    }
}

@Composable
private fun ItemPickerGroupStep(
    target: BcCharacterSnapshot,
    groups: List<InteractionGroupUiState>,
    workflowKind: BcAppearanceGroupKind,
    onSelectGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.room_details_choose_manage_for, target.displayName),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = if (workflowKind == BcAppearanceGroupKind.Clothing) {
                stringResource(R.string.room_details_clothing_workflow_explanation)
            } else {
                stringResource(R.string.room_details_item_workflow_explanation)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (groups.isEmpty()) {
            Text(
                stringResource(R.string.room_details_no_item_groups),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (workflowKind == BcAppearanceGroupKind.Clothing) {
                WardrobeGroupGrid(
                    groups = groups.filter { it.kind == BcAppearanceGroupKind.Clothing },
                    onSelectGroup = onSelectGroup,
                )
            } else {
                BodyPartArrangementGrid(
                    groups = groups.filter { it.kind == BcAppearanceGroupKind.Item },
                    mode = BodyPartArrangementMode.Item,
                    onSelectGroup = onSelectGroup,
                )
            }
        }
    }
}

private enum class WardrobeCategory {
    Head,
    Upper,
    Lower,
    Underwear,
    Hands,
    Legs,
    Shoes,
    Outerwear,
    Accessories,
}

@Composable
private fun WardrobeGroupGrid(
    groups: List<InteractionGroupUiState>,
    onSelectGroup: (String) -> Unit,
) {
    val categorized = remember(groups) {
        groups.groupBy { it.wardrobeCategory() }.mapValues { (_, categoryGroups) ->
            categoryGroups.sortedBy { it.displayName.lowercase() }.chunked(2)
        }
    }
    WardrobeCategory.entries.forEach { category ->
        val categoryGroups = categorized[category].orEmpty()
        if (categoryGroups.isNotEmpty()) {
            Text(category.label(), style = MaterialTheme.typography.labelLarge)
            categoryGroups.forEach { rowGroups ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowGroups.forEach { group ->
                        BodyPartGridButton(
                            group = group,
                            mode = BodyPartArrangementMode.Item,
                            onSelectGroup = onSelectGroup,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowGroups.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun InteractionGroupUiState.wardrobeCategory(): WardrobeCategory {
    val token = name.lowercase()
    return when {
        token.contains("hair") || token.contains("hat") || token.contains("head") -> WardrobeCategory.Head
        token.contains("bra") || token.contains("pant") || token.contains("corset") || token.contains("under") -> WardrobeCategory.Underwear
        token.contains("clothlower") || token.contains("skirt") || token.contains("short") -> WardrobeCategory.Lower
        token.contains("glove") || token.contains("hand") -> WardrobeCategory.Hands
        token.contains("sock") || token.contains("stocking") || token.contains("leg") -> WardrobeCategory.Legs
        token.contains("shoe") || token.contains("boot") -> WardrobeCategory.Shoes
        token.contains("suit") || token.contains("jacket") || token.contains("coat") -> WardrobeCategory.Outerwear
        token == "cloth" || token.contains("top") || token.contains("shirt") -> WardrobeCategory.Upper
        else -> WardrobeCategory.Accessories
    }
}

@Composable
private fun WardrobeCategory.label(): String = stringResource(
    when (this) {
        WardrobeCategory.Head -> R.string.room_details_wardrobe_head
        WardrobeCategory.Upper -> R.string.room_details_wardrobe_upper
        WardrobeCategory.Lower -> R.string.room_details_wardrobe_lower
        WardrobeCategory.Underwear -> R.string.room_details_wardrobe_underwear
        WardrobeCategory.Hands -> R.string.room_details_wardrobe_hands
        WardrobeCategory.Legs -> R.string.room_details_wardrobe_legs
        WardrobeCategory.Shoes -> R.string.room_details_wardrobe_shoes
        WardrobeCategory.Outerwear -> R.string.room_details_wardrobe_outerwear
        WardrobeCategory.Accessories -> R.string.room_details_wardrobe_accessories
    },
)

internal fun LazyListScope.itemPickerEntries(
    target: BcCharacterSnapshot,
    group: InteractionGroupUiState,
    picker: InteractionItemPickerUiState,
    busy: Boolean,
    onSelectOperation: (String) -> Unit,
    onSelectItem: (String) -> Unit,
    onUpdateQuery: (String) -> Unit,
    onToggleUnavailable: () -> Unit,
    onSendEntry: (InteractionItemPickerEntryUiState) -> Unit,
    onLoadMore: () -> Unit,
    onClearFocus: () -> Unit,
    onPreview: ((InteractionItemPickerEntryUiState) -> Unit)? = null,
    onEditText: (() -> Unit)? = null,
    onUnlockInput: (() -> Unit)? = null,
    onConfigureLock: (() -> Unit)? = null,
    onChangeTimer: (() -> Unit)? = null,
) {
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(
                        picker.selectedOperation?.let { itemOperationLabel(it.name, group.kind) },
                        picker.selectedItemName?.let {
                            stringResource(R.string.room_details_options_for, picker.selectedItemDisplayName ?: it.toHumanLabel())
                        },
                    ).filterNot {
                        !picker.configuringCurrentItem && !picker.lockingCurrentItem &&
                            it == picker.selectedOperation?.let { operation -> itemOperationLabel(operation.name, group.kind) }
                    }
                        .joinToString(" • ")
                        .ifBlank { stringResource(R.string.room_details_choose_item) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    item {
        ItemPickerCurrentCard(
            group = group,
            picker = picker,
            busy = busy,
            onConfigure = { onSelectOperation("Modify") },
            onLock = { onSelectOperation("Lock") },
            onRemove = onSendEntry,
            onUnlockInput = onUnlockInput,
        )
    }
    if (onEditText != null && picker.currentItemTextFields.isNotEmpty()) item {
        TextButton(onClick = onEditText, enabled = !busy && picker.currentItemTextEditEntry?.enabled == true) {
            Text(stringResource(R.string.room_details_edit_item_text))
        }
        picker.currentItemTextEditEntry?.permissionMessage?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
    }
    if (onChangeTimer != null && picker.currentItemTimer != null) item {
        TextButton(onClick = onChangeTimer, enabled = !busy && picker.currentItemTimer.canOpen) { Text(stringResource(R.string.room_details_timer_controls)) }
        if (!picker.currentItemTimer.canOpen) picker.currentItemTimer.permissionMessage?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
    }
    if (onConfigureLock != null && picker.currentItemLockConfigurationMode != null) item {
        TextButton(onClick = onConfigureLock, enabled = !busy && picker.currentItemLockConfigurationEntry?.enabled == true) {
            Text(stringResource(when (picker.currentItemLockConfigurationMode) {
                dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfigurationMode.UpdateKeyholders -> R.string.room_details_lock_keyholders
                dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfigurationMode.ChangeCombination -> R.string.room_details_change_lock_combination
                else -> R.string.room_details_set_lock_password
            }))
        }
        picker.currentItemLockConfigurationEntry?.permissionMessage?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
    }
    if (onUnlockInput != null && picker.currentItemUnlockEntry?.requiresUnlockInput == false && picker.currentItemCredentialUnlockEntry != null) item {
        TextButton(onClick = onUnlockInput, enabled = !busy && picker.currentItemCredentialUnlockEntry.enabled) {
            Text(stringResource(R.string.room_details_unlock_with_code))
        }
        picker.currentItemCredentialUnlockEntry.permissionMessage?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
    }

    item {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = picker.showUnavailable,
                onClick = onToggleUnavailable,
                enabled = !busy,
                label = {
                    Text(
                        if (picker.showUnavailable) {
                            stringResource(R.string.room_details_showing_unavailable)
                        } else {
                            stringResource(R.string.room_details_show_unavailable)
                        },
                    )
                },
            )
            if (picker.query.isNotBlank()) {
                TextButton(
                    onClick = { onUpdateQuery("") },
                    enabled = !busy,
                ) { Text(stringResource(R.string.room_details_clear_search)) }
            }
        }
    }

    item {
        OutlinedTextField(
            value = picker.query,
            onValueChange = onUpdateQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onClearFocus() }),
            label = {
                Text(
                    if (picker.lockingCurrentItem) {
                        stringResource(R.string.room_details_search_locks)
                    } else if (picker.selectedItemName == null && !picker.configuringCurrentItem) {
                        if (group.kind == BcAppearanceGroupKind.Clothing) {
                            stringResource(R.string.room_details_search_clothing)
                        } else {
                            stringResource(R.string.dock_search_current_items, group.displayName)
                        }
                    } else {
                        stringResource(R.string.room_details_search_options)
                    },
                )
            },
        )
    }

    picker.emptyMessage?.let { message ->
        item { Text(text = message.text(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (picker.entries.isNotEmpty()) {
        item {
            Text(
                text = itemPickerPageSummary(picker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    itemsIndexed(picker.entries, key = { index, entry -> "${entry.group}:${entry.operation}:$index" }) { _, entry ->
        if (onPreview != null && entry.itemName != null && entry.operation in setOf("Wear", "Replace", "Modify")) {
            TextButton(onClick = { onPreview(entry) }) {
                Text(stringResource(R.string.room_details_preview_item, entry.label))
            }
        }
        val buttonText = when {
            entry.requiresUnlockInput -> stringResource(R.string.room_details_unlock_with_code)
            entry.requiresTextInput -> stringResource(R.string.room_details_edit_item_text)
            else -> itemPickerButtonText(entry, picker.selectedItemName != null, group.kind)
        }
        val enabled = !busy && itemPickerEntryEnabled(entry, picker.selectedItemName != null) &&
            (!entry.requiresTextInput || onEditText != null) && (!entry.requiresUnlockInput || onUnlockInput != null)
        InteractionActionRow(
            title = when {
                entry.requiresTextInput -> stringResource(R.string.room_details_edit_item_text)
                entry.operation == "Lock" && entry.itemName == "Heart Padlock" -> stringResource(R.string.room_details_afc_heart_lock)
                else -> entry.label
            },
            subtitle = listOfNotNull(
                entry.subtitle,
                entry.currentItemName?.let {
                    stringResource(R.string.room_details_current_value, picker.currentItemDisplayName ?: it.toHumanLabel())
                },
                stringResource(R.string.room_details_adjustable_style).takeIf { entry.canOpenOptions },
                entry.permissionMessage?.text(),
            ).joinToString(" • ").ifBlank { stringResource(R.string.room_details_ready_to_use) },
            buttonText = buttonText,
            enabled = enabled,
            onClick = {
                if (entry.requiresUnlockInput) {
                    onUnlockInput?.invoke()
                } else if (entry.requiresTextInput) {
                    onEditText?.invoke()
                } else if ((entry.operation == "Wear" || entry.operation == "Replace") && entry.canOpenOptions && picker.selectedItemName == null) {
                    entry.itemName?.let(onSelectItem)
                } else {
                    onSendEntry(entry)
                }
            },
        )
    }

    if (picker.hasMore) {
        item {
            OutlinedButton(
                onClick = onLoadMore,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (picker.totalKnownEntries > 0) {
                        stringResource(
                            R.string.room_details_load_more_progress,
                            picker.loadedCount,
                            picker.totalKnownEntries,
                        )
                    } else {
                        stringResource(R.string.room_details_load_more)
                    },
                )
            }
        }
    }
}

internal enum class BodyPartArrangementMode {
    Action,
    Item,
}

@Composable
internal fun BodyPartArrangementGrid(
    groups: List<InteractionGroupUiState>,
    mode: BodyPartArrangementMode,
    onSelectGroup: (String) -> Unit,
    enabled: Boolean = true,
    selectedGroup: String? = null,
) {
    val regions = remember(groups) { RoomBodyPartLayout.regions(groups) }
    val extraRows = remember(groups, regions) {
        val rendered = regions.map { it.group.name.lowercase() }.toSet()
        groups.filterNot { rendered.contains(it.name.lowercase()) }
            .sortedBy { it.displayName.lowercase() }
            .chunked(2)
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (regions.isNotEmpty()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Enlarge the whole R133 grid, never individual zones: adjacent slots must not overlap.
                val canvasWidth = maxWidth.coerceAtLeast(360.dp * fontScale)
                val canvasHeight = canvasWidth * (RoomBodyPartLayout.height / RoomBodyPartLayout.width)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Box(Modifier.width(canvasWidth).height(canvasHeight)) {
                        regions.forEach { region ->
                            BodyPartZoneButton(
                                group = region.group,
                                mode = mode,
                                onSelectGroup = onSelectGroup,
                                enabled = enabled,
                                isSelected = region.group.name == selectedGroup,
                                modifier = Modifier
                                    .absoluteOffset(x = canvasWidth * region.x, y = canvasHeight * region.y)
                                    .width(canvasWidth * region.width)
                                    .height(canvasHeight * region.height),
                            )
                        }
                    }
                }
            }
        }

        if (extraRows.isNotEmpty() && regions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.room_details_other_body_parts),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        extraRows.forEach { rowGroups ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowGroups.forEach { group ->
                    BodyPartGridButton(
                        group = group,
                        mode = mode,
                        onSelectGroup = onSelectGroup,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowGroups.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BodyPartZoneButton(
    group: InteractionGroupUiState,
    mode: BodyPartArrangementMode,
    onSelectGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSelected: Boolean = false,
) {
    val accessible = enabled && when (mode) {
        BodyPartArrangementMode.Action -> group.hasActivities
        BodyPartArrangementMode.Item -> group.hasItems || group.hasCurrentItem
    }
    val shape = RoundedCornerShape(2.dp)
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        group.hasCurrentItem -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    val backgroundColor = when {
        group.hasCurrentItem -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
        accessible -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    Box(
        modifier = modifier
            .border(if (isSelected) 3.dp else if (group.hasCurrentItem) 2.dp else 1.dp, borderColor, shape)
            .background(backgroundColor, shape)
            .semantics { selected = isSelected }
            .clickable(enabled = accessible, role = Role.RadioButton) { onSelectGroup(group.name) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = group.displayName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (group.hasCurrentItem) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BodyPartGridButton(
    group: InteractionGroupUiState,
    mode: BodyPartArrangementMode,
    onSelectGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accessible = enabled && when (mode) {
        BodyPartArrangementMode.Action -> group.hasActivities
        BodyPartArrangementMode.Item -> group.hasItems || group.hasCurrentItem
    }
    val shape = RoundedCornerShape(14.dp)
    val borderColor = when {
        group.hasCurrentItem -> MaterialTheme.colorScheme.primary
        accessible -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val backgroundColor = when {
        group.hasCurrentItem -> MaterialTheme.colorScheme.primaryContainer
        mode == BodyPartArrangementMode.Item && group.hasItems -> MaterialTheme.colorScheme.secondaryContainer
        accessible -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val subtitle = when (mode) {
        BodyPartArrangementMode.Action -> listOfNotNull(
            if (group.hasActivities) stringResource(R.string.room_details_reachable) else stringResource(R.string.room_details_no_action),
            if (group.hasCurrentItem) stringResource(R.string.room_details_worn) else null,
        )
        BodyPartArrangementMode.Item -> listOfNotNull(
            when (group.kind) {
                BcAppearanceGroupKind.Item -> stringResource(R.string.room_details_item_lower)
                BcAppearanceGroupKind.Clothing -> stringResource(R.string.room_details_clothing_lower)
                BcAppearanceGroupKind.Appearance -> stringResource(R.string.room_details_appearance_lower)
            },
            if (group.hasCurrentItem) stringResource(R.string.room_details_worn) else null,
            if (group.hasItems) stringResource(R.string.room_details_catalog) else null,
            if (!group.hasItems && !group.hasCurrentItem) stringResource(R.string.room_details_empty) else null,
        )
    }.joinToString(" • ")

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 64.dp)
            .border(width = if (group.hasCurrentItem) 2.dp else 1.dp, color = borderColor, shape = shape)
            .background(color = backgroundColor, shape = shape)
            .clickable(enabled = accessible) { onSelectGroup(group.name) }
            .padding(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = group.displayName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (group.hasCurrentItem) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle.ifBlank { stringResource(R.string.room_details_unavailable) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ItemPickerCurrentCard(
    group: InteractionGroupUiState,
    picker: InteractionItemPickerUiState,
    busy: Boolean,
    onConfigure: () -> Unit,
    onLock: () -> Unit,
    onRemove: (InteractionItemPickerEntryUiState) -> Unit,
    onUnlockInput: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(R.string.room_details_current_state), style = MaterialTheme.typography.labelMedium)
                Text(
                    text = when {
                        picker.currentItemName != null && group.kind == BcAppearanceGroupKind.Clothing -> stringResource(
                            R.string.room_details_current_clothing_value,
                            picker.currentItemDisplayName ?: picker.currentItemName.toHumanLabel(),
                        )
                        picker.currentItemName != null -> stringResource(
                            R.string.room_details_current_item_value,
                            picker.currentItemDisplayName ?: picker.currentItemName.toHumanLabel(),
                        )
                        group.kind == BcAppearanceGroupKind.Clothing -> stringResource(R.string.room_details_no_clothing_worn)
                        else -> stringResource(R.string.room_details_no_current_item)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (picker.currentItemConfigurable) {
                TextButton(
                    onClick = onConfigure,
                    enabled = !busy && !picker.configuringCurrentItem,
                ) {
                    Text(
                        if (group.kind == BcAppearanceGroupKind.Clothing) {
                            stringResource(R.string.room_details_adjust)
                        } else {
                            stringResource(R.string.room_details_configure)
                        },
                    )
                }
            }
            picker.currentItemRemoveEntry?.let { entry ->
                TextButton(
                    onClick = { onRemove(entry) },
                    enabled = !busy && entry.enabled,
                ) {
                    Text(
                        if (group.kind == BcAppearanceGroupKind.Clothing) {
                            stringResource(R.string.room_details_take_off)
                        } else {
                            stringResource(R.string.room_details_remove)
                        },
                    )
                }
            }
        }
        picker.currentItemRemoveEntry?.permissionMessage?.let { message ->
            Text(
                text = message.text(),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (picker.currentItemIsAfcHeartLock) {
            Text(
                text = picker.currentItemLockOwnerNumber?.let { stringResource(R.string.room_details_afc_heart_lock_owner, it) }
                    ?: stringResource(R.string.room_details_afc_heart_lock),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        picker.currentItemUnlockEntry?.let { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.permissionMessage?.text() ?: stringResource(if (entry.requiresUnlockInput && picker.currentItemRemoveOnUnlock &&
                        picker.currentItemLockInputKind == dev.vivian.mobilebc.domain.bcitemproperty.BcLockInputKind.Password)
                        R.string.room_details_unlock_removes_item else R.string.room_details_unlock_keeps_item),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { if (entry.requiresUnlockInput) onUnlockInput?.invoke() else onRemove(entry) },
                    enabled = !busy && entry.enabled && (!entry.requiresUnlockInput || onUnlockInput != null)) {
                    Text(stringResource(if (entry.requiresUnlockInput) R.string.room_details_unlock_with_code else R.string.room_details_unlock))
                }
            }
        }
        if (picker.currentItemCanLock) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.room_details_choose_lock), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onLock, enabled = !busy && !picker.lockingCurrentItem) {
                    Text(stringResource(R.string.room_details_lock))
                }
            }
        }
        if (picker.currentItemDescription != null || picker.currentItemTextLines.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                picker.currentItemDescription?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                picker.currentItemTextLines.forEachIndexed { index, text ->
                    Text(
                        text = if (picker.currentItemTextLines.size == 1) stringResource(R.string.room_details_item_text_value, text)
                            else stringResource(R.string.room_details_item_text_line_value, index + 1, text),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun itemOperationLabel(
    operation: String,
    groupKind: BcAppearanceGroupKind,
): String = when (operation) {
    "Wear" -> if (groupKind == BcAppearanceGroupKind.Clothing) {
        stringResource(R.string.room_details_put_on)
    } else {
        stringResource(R.string.room_details_wear)
    }
    "Replace" -> if (groupKind == BcAppearanceGroupKind.Clothing) {
        stringResource(R.string.room_details_change)
    } else {
        stringResource(R.string.room_details_replace)
    }
    "Modify" -> if (groupKind == BcAppearanceGroupKind.Clothing) {
        stringResource(R.string.room_details_adjust)
    } else {
        stringResource(R.string.room_details_configure)
    }
    "Remove" -> if (groupKind == BcAppearanceGroupKind.Clothing) {
        stringResource(R.string.room_details_take_off)
    } else {
        stringResource(R.string.room_details_remove)
    }
    "Lock" -> stringResource(R.string.room_details_lock)
    else -> stringResource(R.string.room_details_choose)
}

@Composable
private fun itemPickerButtonText(
    entry: InteractionItemPickerEntryUiState,
    itemSelected: Boolean,
    groupKind: BcAppearanceGroupKind,
): String = when (entry.operation) {
    "Lock" -> stringResource(R.string.room_details_lock)
    "Wear" -> if (entry.canOpenOptions && !itemSelected) stringResource(R.string.room_details_options) else if (groupKind == BcAppearanceGroupKind.Clothing) stringResource(R.string.room_details_put_on) else stringResource(R.string.room_details_wear)
    "Replace" -> if (entry.canOpenOptions && !itemSelected) stringResource(R.string.room_details_options) else if (groupKind == BcAppearanceGroupKind.Clothing) stringResource(R.string.room_details_change) else stringResource(R.string.room_details_replace)
    "Modify" -> if (entry.selected) stringResource(R.string.room_details_current) else if (groupKind == BcAppearanceGroupKind.Clothing) stringResource(R.string.room_details_adjust) else stringResource(R.string.room_details_apply)
    "Remove" -> if (groupKind == BcAppearanceGroupKind.Clothing) stringResource(R.string.room_details_take_off) else stringResource(R.string.room_details_remove)
    else -> stringResource(R.string.room_details_choose)
}

@Composable
internal fun InteractionUserMessage.text(): String = when (this) {
    is InteractionUserMessage.General -> when (key) {
        InteractionUserMessageKey.ChooseItemOperation -> stringResource(R.string.room_feedback_choose_item_operation)
        InteractionUserMessageKey.ChooseAction -> stringResource(R.string.room_feedback_choose_action)
        InteractionUserMessageKey.ChooseItem -> stringResource(R.string.room_feedback_choose_item)
        InteractionUserMessageKey.ChooseCurrentItemOption -> stringResource(R.string.room_feedback_choose_item_option)
        InteractionUserMessageKey.ChooseBodyPart -> stringResource(R.string.room_feedback_choose_body_part)
        InteractionUserMessageKey.ChooseItemGroup -> stringResource(R.string.room_feedback_choose_item_group)
        InteractionUserMessageKey.ItemUnavailable -> stringResource(R.string.room_feedback_item_unavailable)
        InteractionUserMessageKey.ItemPickerInvalid -> stringResource(R.string.room_feedback_picker_invalid)
        InteractionUserMessageKey.ItemPickerStale -> stringResource(R.string.room_feedback_picker_stale)
        InteractionUserMessageKey.ItemForDifferentBodyPart -> stringResource(R.string.room_feedback_picker_body_part_changed)
        InteractionUserMessageKey.ItemForDifferentCharacter -> stringResource(R.string.room_feedback_picker_character_changed)
        InteractionUserMessageKey.InteractionDataPreparing -> stringResource(R.string.room_feedback_data_preparing)
        InteractionUserMessageKey.SelfCharacterUnavailable -> stringResource(R.string.room_feedback_self_unavailable)
        InteractionUserMessageKey.TargetLeftRoom -> stringResource(R.string.room_feedback_target_left)
        InteractionUserMessageKey.ActionSent -> stringResource(R.string.room_feedback_action_sent)
        InteractionUserMessageKey.ItemUpdated -> stringResource(R.string.room_feedback_item_updated)
        InteractionUserMessageKey.ItemReplaced -> stringResource(R.string.room_feedback_item_replaced)
        InteractionUserMessageKey.ItemConfigured -> stringResource(R.string.room_feedback_item_configured)
        InteractionUserMessageKey.ItemRemoved -> stringResource(R.string.room_feedback_item_removed)
        InteractionUserMessageKey.ItemUnlocked -> stringResource(R.string.room_feedback_item_unlocked)
        InteractionUserMessageKey.ItemLocked -> stringResource(R.string.room_feedback_item_locked)
        InteractionUserMessageKey.RequestRejected -> stringResource(R.string.room_feedback_request_rejected)
        InteractionUserMessageKey.RequestFailed -> stringResource(R.string.room_feedback_request_failed)
        InteractionUserMessageKey.ResourceSyncFailed -> stringResource(R.string.room_feedback_resource_sync_failed)
        InteractionUserMessageKey.NoMatchingItems -> stringResource(
            R.string.room_feedback_no_matching_items,
            argument.orEmpty(),
        )
        InteractionUserMessageKey.NoAllowedMatchingItems -> stringResource(
            R.string.room_feedback_no_allowed_matching_items,
            argument.orEmpty(),
        )
        InteractionUserMessageKey.NoItems -> stringResource(R.string.room_feedback_no_items)
        InteractionUserMessageKey.NoAllowedItems -> stringResource(R.string.room_feedback_no_allowed_items)
    }

    is InteractionUserMessage.ItemAvailability -> when (reason) {
        BcItemManagementAvailabilityReason.SlotAlreadyOccupied -> stringResource(R.string.room_feedback_slot_occupied)
        BcItemManagementAvailabilityReason.SlotEmpty -> stringResource(R.string.room_feedback_slot_empty)
        BcItemManagementAvailabilityReason.ItemAlreadyWorn -> stringResource(R.string.room_feedback_item_already_worn)
        BcItemManagementAvailabilityReason.ItemNoLongerWorn -> stringResource(R.string.room_feedback_item_no_longer_worn)
        BcItemManagementAvailabilityReason.NoConfigurableOptions -> stringResource(R.string.room_feedback_no_configurable_options)
        BcItemManagementAvailabilityReason.ExtendedItemUnsupported -> stringResource(R.string.room_feedback_extended_item_unsupported)
        BcItemManagementAvailabilityReason.CurrentSelection -> stringResource(R.string.room_feedback_current_selection)
    }

    is InteractionUserMessage.Permission -> when (code) {
        BcPermissionReasonCode.ActorNotInRoom -> stringResource(R.string.room_feedback_self_unavailable)
        BcPermissionReasonCode.TargetNotInRoom -> stringResource(R.string.room_feedback_target_left)
        BcPermissionReasonCode.BlankGroup,
        BcPermissionReasonCode.BlankActivityName,
        BcPermissionReasonCode.BlankItemName,
        BcPermissionReasonCode.UnknownGroup,
        BcPermissionReasonCode.UnknownActivity,
        BcPermissionReasonCode.UnknownAsset,
        -> stringResource(R.string.room_permission_data_unavailable)
        BcPermissionReasonCode.ActivityNotAllowedOnGroup -> stringResource(R.string.room_permission_action_not_available_here)
        BcPermissionReasonCode.RoomBlocksActivities -> stringResource(R.string.room_permission_room_blocks_activities)
        BcPermissionReasonCode.TargetArousalInactive -> stringResource(R.string.room_permission_target_activities_disabled)
        BcPermissionReasonCode.TargetArousalZoneDisabled,
        BcPermissionReasonCode.ActivityPreferenceBlocked,
        -> stringResource(R.string.room_permission_target_preference_blocked)
        BcPermissionReasonCode.UnsupportedActivityPrerequisite,
        BcPermissionReasonCode.UnsupportedAssetPrerequisite,
        BcPermissionReasonCode.UnsupportedOptionPrerequisite,
        -> stringResource(R.string.room_permission_not_supported_safely)
        BcPermissionReasonCode.MissingRequiredActorItem,
        BcPermissionReasonCode.MissingRequiredTargetItem,
        BcPermissionReasonCode.MissingShockTriggerItem,
        BcPermissionReasonCode.MissingShockReceiverItem,
        -> stringResource(R.string.room_permission_required_item_missing)
        BcPermissionReasonCode.SelectedActivityItemUnavailable -> stringResource(R.string.room_permission_selected_item_unavailable)
        BcPermissionReasonCode.ActorCannotUseHands -> stringResource(R.string.room_permission_actor_cannot_use_hands)
        BcPermissionReasonCode.ActorCannotUseArms -> stringResource(R.string.room_permission_actor_cannot_use_arms)
        BcPermissionReasonCode.ActorCannotUseFeet -> stringResource(R.string.room_permission_actor_cannot_use_feet)
        BcPermissionReasonCode.ActorCannotUseMouth -> stringResource(R.string.room_permission_actor_cannot_use_mouth)
        BcPermissionReasonCode.TargetCannotUseTongue -> stringResource(R.string.room_permission_target_cannot_use_tongue)
        BcPermissionReasonCode.TargetMouthBlocked -> stringResource(R.string.room_permission_target_mouth_blocked)
        BcPermissionReasonCode.TargetMouthNotBlocked -> stringResource(R.string.room_permission_target_mouth_not_blocked)
        BcPermissionReasonCode.TargetMouthNotOpen -> stringResource(R.string.room_permission_target_mouth_not_open)
        BcPermissionReasonCode.ActorNotGagged -> stringResource(R.string.room_permission_actor_not_gagged)
        BcPermissionReasonCode.TargetNotKneeling -> stringResource(R.string.room_permission_target_not_kneeling)
        BcPermissionReasonCode.TargetZoneBlocked -> stringResource(R.string.room_permission_target_part_blocked)
        BcPermissionReasonCode.TargetZoneNotNaked -> stringResource(R.string.room_permission_target_part_not_exposed)
        BcPermissionReasonCode.TargetZoneOccupied -> stringResource(R.string.room_permission_target_part_occupied)
        BcPermissionReasonCode.ActorZoneOccupied -> stringResource(R.string.room_permission_actor_part_occupied)
        BcPermissionReasonCode.ActorZoneBlocked -> stringResource(R.string.room_permission_actor_part_blocked)
        BcPermissionReasonCode.ActorZoneNotNaked -> stringResource(R.string.room_permission_actor_part_not_exposed)
        BcPermissionReasonCode.AssetDisabled -> stringResource(R.string.room_permission_item_disabled)
        BcPermissionReasonCode.ItemExclusivePermissionDenied -> stringResource(R.string.room_permission_item_restricted)
        BcPermissionReasonCode.GroupDoesNotAllowNone -> stringResource(R.string.room_permission_cannot_remove_without_replacement)
        BcPermissionReasonCode.ServerAllowItemRequired -> stringResource(R.string.room_permission_server_check_on_send)
        BcPermissionReasonCode.ServerAllowItemDenied -> stringResource(R.string.room_permission_server_denied)
        BcPermissionReasonCode.LocalAllowItemDenied -> stringResource(R.string.room_permission_target_setting_denied)
        BcPermissionReasonCode.TargetBlacklistedActor -> stringResource(R.string.room_permission_target_blacklisted_you)
        BcPermissionReasonCode.TargetItemNotWorn -> stringResource(R.string.room_feedback_item_no_longer_worn)
        BcPermissionReasonCode.TargetItemPermissionBlocked -> stringResource(R.string.room_permission_item_blocked)
        BcPermissionReasonCode.TargetItemPermissionLimited -> stringResource(R.string.room_permission_item_limited)
        BcPermissionReasonCode.TargetItemLocked -> stringResource(R.string.room_permission_item_locked)
        BcPermissionReasonCode.ItemUnlockUnavailable -> stringResource(R.string.room_permission_unlock_unavailable)
        BcPermissionReasonCode.IncorrectLockCredential -> stringResource(R.string.room_permission_lock_code_incorrect)
        BcPermissionReasonCode.LockInputUnavailable -> stringResource(R.string.room_permission_lock_input_unavailable)
        BcPermissionReasonCode.LockConfigurationUnavailable -> stringResource(R.string.room_permission_lock_configuration_unavailable)
        BcPermissionReasonCode.InvalidLockConfiguration -> stringResource(R.string.room_permission_lock_configuration_invalid)
        BcPermissionReasonCode.ItemLockUnavailable -> stringResource(R.string.room_permission_lock_unavailable)
        BcPermissionReasonCode.ItemDoesNotAllowLock -> stringResource(R.string.room_permission_lock_mode_unavailable)
        BcPermissionReasonCode.ItemAlreadyLocked -> stringResource(R.string.room_permission_already_locked)
        BcPermissionReasonCode.LockConfigurationUnsupported -> stringResource(R.string.room_permission_lock_unsupported)
        BcPermissionReasonCode.LockTimerUnavailable -> stringResource(R.string.room_permission_lock_clock_unavailable)
        BcPermissionReasonCode.LockTimerExpired -> stringResource(R.string.room_permission_timer_expired)
        BcPermissionReasonCode.LockTimerPermissionDenied -> stringResource(R.string.room_permission_timer_denied)
        BcPermissionReasonCode.InvalidLockTimerChange -> stringResource(R.string.room_permission_timer_invalid)
        BcPermissionReasonCode.AfcHeartLockPermissionDenied -> stringResource(R.string.room_permission_afc_heart_lock)
        BcPermissionReasonCode.InvalidItemText -> stringResource(R.string.room_permission_item_text_invalid)
        BcPermissionReasonCode.ActorPrerequisiteNotMet -> stringResource(R.string.room_permission_actor_state_not_ready)
        BcPermissionReasonCode.TargetPrerequisiteNotMet -> stringResource(R.string.room_permission_target_state_not_ready)
        BcPermissionReasonCode.TargetPoseNotAllowed -> stringResource(R.string.room_permission_pose_not_compatible)
    }
}

private fun itemPickerEntryEnabled(
    entry: InteractionItemPickerEntryUiState,
    itemSelected: Boolean,
): Boolean = when (entry.operation) {
    "Wear",
    "Replace" -> entry.enabled || (entry.canOpenOptions && !itemSelected)
    "Modify" -> entry.enabled && !entry.selected && entry.propertyJson != null
    "Remove" -> entry.enabled
    else -> entry.enabled
}

@Composable
private fun itemPickerPageSummary(picker: InteractionItemPickerUiState): String {
    val prefix = when {
        picker.totalKnownEntries > 0 -> stringResource(
            R.string.room_details_showing_of,
            picker.loadedCount.coerceAtMost(picker.totalKnownEntries),
            picker.totalKnownEntries,
        )
        else -> stringResource(R.string.room_details_showing_count, picker.loadedCount)
    }
    val query = picker.query.trim().takeIf { it.isNotBlank() }?.let {
        stringResource(R.string.room_details_for_query, it)
    }.orEmpty()
    val unavailable = if (picker.showUnavailable) {
        stringResource(R.string.room_details_including_unavailable)
    } else {
        stringResource(R.string.room_details_allowed_only)
    }
    return prefix + query + unavailable
}

private fun String.toHumanLabel(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .ifBlank { this }

@Composable
private fun roomSubtitle(uiState: RoomDetailsUiState): String {
    val memberLimit = uiState.memberLimit
        ?: return stringResource(R.string.room_details_join_room_hint)
    val mapType = when (uiState.mapType) {
        BcRoomMapType.ChatRoom -> stringResource(R.string.rooms_type_chat)
        BcRoomMapType.Hybrid -> stringResource(R.string.rooms_type_hybrid)
        BcRoomMapType.Map -> stringResource(R.string.rooms_type_map)
        BcRoomMapType.Unknown,
        null,
        -> stringResource(R.string.room_details_unknown_map_type)
    }
    return stringResource(
        R.string.room_details_room_status,
        uiState.onlineCount,
        memberLimit,
        mapType,
    )
}

@Composable
private fun RoomSummaryCard(
    uiState: RoomDetailsUiState,
    onOpenRoomSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.room_details_room), style = MaterialTheme.typography.titleMedium)
            Text(
                uiState.roomTitle ?: stringResource(R.string.room_details_no_current_room),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = uiState.roomDescription.ifBlank { stringResource(R.string.room_details_no_description) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.canEditRoomSettings) {
                OutlinedButton(
                    onClick = onOpenRoomSettings,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.room_details_edit_settings))
                }
            }
        }
    }
}

@Composable
private fun CharacterRow(
    character: BcCharacterSnapshot,
    onOpenBio: (Int) -> Unit,
    onOpenInteract: (Int) -> Unit,
    canManage: Boolean,
    managementEnabled: Boolean,
    interactionEnabled: Boolean,
    onManage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { onOpenBio(character.memberNumber) },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AvatarToken(character.displayName)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = character.displayName,
                        color = bcLabelColor(character.labelColor) ?: androidx.compose.ui.graphics.Color.Unspecified,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = character.accountName?.takeIf { it.isNotBlank() } ?: "#${character.memberNumber}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = bcLabelColor(character.labelColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (character.isSelf) StatusChip(stringResource(R.string.character_you))
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { onOpenInteract(character.memberNumber) }, enabled = interactionEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.character_interact))
                }
                if (canManage) {
                    OutlinedButton(
                        onClick = { onManage(character.memberNumber) },
                        enabled = managementEnabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.room_member_manage))
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterInteractionSheet(
    uiState: CharacterInteractionSheetUiState,
    onClose: () -> Unit,
    onSelectGroup: (String) -> Unit,
    onSendActivity: (String, String, String?, String?) -> Unit,
    onWearItem: (String, String, String?, Int?) -> Unit,
    onRemoveItem: (String) -> Unit,
    onOpenItemPicker: (Int, String, BcAppearanceGroupKind) -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingActivity by remember(uiState.target.memberNumber, uiState.selectedGroup?.name) {
        mutableStateOf<InteractionActivityUiState?>(null)
    }
    fun send(activity: InteractionActivityUiState) {
        onSendActivity(activity.group, activity.name, activity.activityAssetGroup, activity.activityAssetName)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarToken(uiState.target.displayName)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.character_interact), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${uiState.target.displayName} #${uiState.target.memberNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = bcLabelColor(uiState.target.labelColor) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.room_details_close)) }
        }

        InteractionFeedback(uiState, onClearMessage)

        if (uiState.groups.isEmpty()) {
            Text(
                text = when {
                    !uiState.catalogReady -> stringResource(R.string.room_details_catalog_not_loaded)
                    !uiState.permissionReady -> stringResource(R.string.room_details_permissions_not_loaded)
                    else -> stringResource(R.string.room_details_no_interaction_groups)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(R.string.room_details_body_group), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                uiState.groups.forEach { group ->
                    FilterChip(
                        selected = uiState.selectedGroup?.name.equals(group.name, ignoreCase = true),
                        onClick = { onSelectGroup(group.name) },
                        label = {
                            val wornLabel = stringResource(R.string.room_details_worn)
                            Text(
                                text = buildString {
                                    append(group.displayName)
                                    if (group.hasCurrentItem) {
                                        append(" • ")
                                        append(wornLabel)
                                    }
                                },
                                maxLines = 1,
                            )
                        },
                    )
                }
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CurrentItemSection(
                        currentItem = uiState.currentItem,
                        selectedGroup = uiState.selectedGroup,
                        busy = uiState.busy,
                        onRemoveItem = onRemoveItem,
                        onConfigure = { group, kind -> onOpenItemPicker(uiState.target.memberNumber, group, kind) },
                    )
                }
                item {
                    Text(stringResource(R.string.room_details_actions), style = MaterialTheme.typography.titleMedium)
                }
                if (uiState.activities.isEmpty()) {
                    item {
                        Text(stringResource(R.string.room_details_no_group_actions), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(uiState.activities, key = { "activity:${it.group}:${it.name}" }) { activity ->
                    ActivityActionRow(activity = activity, busy = uiState.busy) {
                        if (activity.effects.isEmpty()) send(activity) else pendingActivity = activity
                    }
                }
                item { ItemSectionHeader(items = uiState.items, selectedGroup = uiState.selectedGroup) }
                items(uiState.items, key = { "item:${it.group}:${it.name}" }) { item ->
                    ItemActionRow(
                        item = item,
                        groupKind = uiState.selectedGroup?.kind ?: BcAppearanceGroupKind.Appearance,
                        busy = uiState.busy,
                        onWearItem = onWearItem,
                    )
                }
            }
        }
    }
    pendingActivity?.let { activity ->
        ActivityConfirmationDialog(
            activity = activity,
            target = uiState.target,
            enabled = !uiState.busy && activity.enabled,
            onDismiss = { pendingActivity = null },
            onConfirm = {
                pendingActivity = null
                send(activity)
            },
        )
    }
}

@Composable
private fun CurrentItemSection(
    currentItem: InteractionCurrentItemUiState?,
    selectedGroup: InteractionGroupUiState?,
    busy: Boolean,
    onRemoveItem: (String) -> Unit,
    onConfigure: (String, BcAppearanceGroupKind) -> Unit,
) {
    val groupKind = selectedGroup?.kind ?: BcAppearanceGroupKind.Appearance
    val currentSectionTitle = when (groupKind) {
        BcAppearanceGroupKind.Item -> stringResource(R.string.room_details_current_item)
        BcAppearanceGroupKind.Clothing -> stringResource(R.string.room_details_current_clothing)
        BcAppearanceGroupKind.Appearance -> stringResource(R.string.room_details_current_appearance)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(currentSectionTitle, style = MaterialTheme.typography.titleMedium)
        if (selectedGroup == null) {
            Text(stringResource(R.string.room_details_choose_body_group), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (currentItem == null) {
            Text(
                stringResource(R.string.room_details_nothing_worn_on, selectedGroup.displayName),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentItem.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = listOfNotNull(
                                stringResource(R.string.room_details_adjustable_style)
                                    .takeIf { currentItem.extendedKind != null },
                                currentItem.selectedOptionSummary?.let {
                                    stringResource(R.string.room_details_selected_value, it)
                                },
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        OutlinedButton(
                            enabled = !busy && currentItem.removeEnabled,
                            onClick = { onRemoveItem(currentItem.group) },
                        ) {
                            Text(
                                when (groupKind) {
                                    BcAppearanceGroupKind.Item -> stringResource(R.string.room_details_remove)
                                    BcAppearanceGroupKind.Clothing -> stringResource(R.string.room_details_take_off)
                                    BcAppearanceGroupKind.Appearance -> stringResource(R.string.room_details_clear)
                                },
                            )
                        }
                        currentItem.removePermissionMessage?.let { message ->
                            Text(
                                text = message.text(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (currentItem.configurable && groupKind != BcAppearanceGroupKind.Appearance) {
                OutlinedButton(
                    onClick = { onConfigure(currentItem.group, groupKind) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (groupKind == BcAppearanceGroupKind.Clothing) R.string.room_details_adjust_in_wardrobe
                            else R.string.room_details_configure_in_item_tool,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemSectionHeader(
    items: List<InteractionItemUiState>,
    selectedGroup: InteractionGroupUiState?,
) {
    val groupKind = selectedGroup?.kind ?: BcAppearanceGroupKind.Appearance
    val sectionTitle = when (groupKind) {
        BcAppearanceGroupKind.Item -> stringResource(R.string.room_details_items)
        BcAppearanceGroupKind.Clothing -> stringResource(R.string.room_details_clothing)
        BcAppearanceGroupKind.Appearance -> stringResource(R.string.room_details_appearance)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(sectionTitle, style = MaterialTheme.typography.titleMedium)
        if (items.isEmpty()) {
            Text(
                when (groupKind) {
                    BcAppearanceGroupKind.Item -> stringResource(R.string.room_details_no_catalog_items)
                    BcAppearanceGroupKind.Clothing -> stringResource(R.string.room_details_no_catalog_clothing)
                    BcAppearanceGroupKind.Appearance -> stringResource(R.string.room_details_no_catalog_appearance)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = when (groupKind) {
                    BcAppearanceGroupKind.Clothing ->
                        stringResource(R.string.room_details_clothing_chat_hint)
                    BcAppearanceGroupKind.Item ->
                        stringResource(R.string.room_details_items_chat_hint)
                    BcAppearanceGroupKind.Appearance ->
                        stringResource(R.string.room_details_appearance_access_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ItemActionRow(
    item: InteractionItemUiState,
    groupKind: BcAppearanceGroupKind,
    busy: Boolean,
    onWearItem: (String, String, String?, Int?) -> Unit,
) {
    InteractionActionRow(
        title = item.displayName,
        subtitle = listOfNotNull(
            stringResource(R.string.room_details_adjustable_style).takeIf { item.extendedKind != null },
            item.permissionMessage?.text(),
        ).joinToString(" • "),
        buttonText = when (groupKind) {
            BcAppearanceGroupKind.Item -> stringResource(R.string.room_details_wear)
            BcAppearanceGroupKind.Clothing -> stringResource(R.string.room_details_put_on)
            BcAppearanceGroupKind.Appearance -> stringResource(R.string.room_details_apply)
        },
        enabled = !busy && item.enabled,
        onClick = { onWearItem(item.group, item.name, item.defaultPropertyJson, item.defaultDifficulty) },
    )
}

@Composable
private fun InteractionActionRow(
    title: String,
    subtitle: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    compact: Boolean = false,
) {
    if (compact) {
        Column(
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(4.dp))
                .combinedClickable(
                    enabled = enabled || onLongClick != null,
                    role = Role.Button,
                    onClick = { if (enabled) onClick() },
                    onLongClick = onLongClick,
                    onLongClickLabel = onLongClickLabel,
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).then(
            if (onLongClick != null) Modifier.combinedClickable(
                onClick = { if (enabled) onClick() },
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            ) else Modifier,
        )) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(enabled = enabled, onClick = onClick) {
            Text(buttonText)
        }
    }
}
