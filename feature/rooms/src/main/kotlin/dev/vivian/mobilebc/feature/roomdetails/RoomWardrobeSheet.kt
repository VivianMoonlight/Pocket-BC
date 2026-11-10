package dev.vivian.mobilebc.feature.roomdetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import dev.vivian.mobilebc.core.ui.AnimatedModalSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeEntryAction
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeEntryReason
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlanEntry
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeUnavailableReason
import dev.vivian.mobilebc.feature.rooms.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomWardrobeSheet(
    state: RoomWardrobeUiState,
    onClose: () -> Unit,
    onSelectSlot: (Int) -> Unit,
    onBeginSave: () -> Unit,
    onBeginRename: () -> Unit,
    onChangeName: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onConfirmSave: () -> Unit,
    onConfirmRename: () -> Unit,
    onPreviewLoad: () -> Unit,
    onCancelLoad: () -> Unit,
    onConfirmLoad: () -> Unit,
    onClearResult: () -> Unit,
) {
    val selectedSlot = state.slots.firstOrNull { it.index == state.selectedSlotIndex }
    val preview = state.preview
    AnimatedModalSheet(onDismissRequest = onClose) { requestClose ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().imePadding().heightIn(max = 760.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.room_wardrobe_title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = requestClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.room_details_close)) }
                }
            }
            item("scope") { Text(stringResource(R.string.room_wardrobe_scope), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.busy) {
                item("busy") {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.room_wardrobe_waiting))
                        }
                        Text(stringResource(R.string.room_wardrobe_waiting_help))
                    }
                }
            } else if (state.blockedByOtherOperation) {
                item("other_busy") { Text(stringResource(R.string.room_member_busy)) }
            }
            state.result?.let { result ->
                item("result") {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(result.text(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (result is RoomWardrobeResult.Sent && result.partial || result is RoomWardrobeResult.AppearanceObserved && result.partial) {
                            Text(stringResource(R.string.room_wardrobe_partial))
                        }
                        TextButton(onClick = onClearResult, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.room_member_dismiss_result)) }
                    }
                }
            }
            state.unavailableReason?.let { reason ->
                item("unavailable") { Text(reason.text(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            when {
                state.editMode == RoomWardrobeEditMode.Save && selectedSlot != null -> item("save_confirm") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(if (selectedSlot.status == BcWardrobeSlotStatus.Saved) R.string.room_wardrobe_confirm_overwrite else R.string.room_wardrobe_confirm_save, selectedSlot.index + 1), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.room_wardrobe_save_help))
                        Button(onClick = onConfirmSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.room_wardrobe_save)) }
                        OutlinedButton(onClick = onCancelEdit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.room_wardrobe_cancel)) }
                    }
                }
                state.editMode == RoomWardrobeEditMode.Rename && selectedSlot != null -> item("rename") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.room_wardrobe_rename_slot, selectedSlot.index + 1), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = state.nameDraft,
                            onValueChange = onChangeName,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.room_wardrobe_name)) },
                            supportingText = { Text(stringResource(if (state.nameValid) R.string.room_wardrobe_name_help else R.string.room_wardrobe_invalid_name, state.nameDraft.length)) },
                            isError = !state.nameValid,
                            singleLine = true,
                        )
                        Text(stringResource(R.string.room_wardrobe_rename_help))
                        Button(onClick = onConfirmRename, enabled = state.canRename && state.nameValid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.room_wardrobe_confirm_rename)) }
                        OutlinedButton(onClick = onCancelEdit, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.room_wardrobe_cancel)) }
                    }
                }
                preview != null -> {
                    item("preview_header") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.room_wardrobe_preview), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            Text(stringResource(R.string.room_wardrobe_full_appearance))
                            if (!preview.hasChanges) Text(stringResource(R.string.room_wardrobe_no_changes))
                        }
                    }
                    itemsIndexed(preview.entries, key = { index, entry -> "$index:${entry.group}" }) { _, entry -> WardrobePreviewRow(entry) }
                    item("load_confirm") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onConfirmLoad, enabled = state.canPreview && preview.hasChanges && preview.unavailableReason == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.room_wardrobe_apply))
                            }
                            OutlinedButton(onClick = onCancelLoad, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.room_wardrobe_cancel)) }
                        }
                    }
                }
                else -> {
                    selectedSlot?.let { slot ->
                        item("selected") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.room_wardrobe_selected_slot, slot.index + 1), style = MaterialTheme.typography.titleMedium)
                                if (slot.status == BcWardrobeSlotStatus.Unsupported) Text(stringResource(R.string.room_wardrobe_unsupported_slot))
                                if (slot.status == BcWardrobeSlotStatus.Empty) Text(stringResource(R.string.room_wardrobe_empty_slot))
                                OutlinedButton(onClick = onBeginSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.room_wardrobe_save)) }
                                OutlinedButton(onClick = onBeginRename, enabled = state.canRename, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.room_wardrobe_rename)) }
                                OutlinedButton(onClick = onPreviewLoad, enabled = state.canPreview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.room_wardrobe_preview)) }
                            }
                        }
                    }
                    item("slots_header") {
                        HorizontalDivider()
                        Text(stringResource(R.string.room_wardrobe_slots), Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    }
                    items(state.slots, key = { it.index }) { slot ->
                        val selected = state.selectedSlotIndex == slot.index
                        OutlinedCard(
                            onClick = { onSelectSlot(slot.index) },
                            enabled = !state.busy && !state.blockedByOtherOperation,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = selected },
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.room_wardrobe_slot_label, slot.index + 1, slot.name ?: stringResource(R.string.room_wardrobe_name_unavailable)), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(when (slot.status) {
                                    BcWardrobeSlotStatus.Empty -> R.string.room_wardrobe_empty
                                    BcWardrobeSlotStatus.Saved -> R.string.room_wardrobe_has_appearance
                                    BcWardrobeSlotStatus.Unsupported -> R.string.room_wardrobe_unavailable
                                }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item("persistence") { Text(stringResource(R.string.room_wardrobe_persistence), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun WardrobePreviewRow(entry: BcWardrobePlanEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(entry.group, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.room_wardrobe_entry_change, entry.currentName ?: stringResource(R.string.room_wardrobe_none), entry.desiredName ?: stringResource(R.string.room_wardrobe_none)))
        Text(stringResource(when (entry.action) {
            BcWardrobeEntryAction.Applied -> R.string.room_wardrobe_entry_applied
            BcWardrobeEntryAction.Removed -> R.string.room_wardrobe_entry_removed
            BcWardrobeEntryAction.RestoredDefault -> R.string.room_wardrobe_entry_default
            BcWardrobeEntryAction.Preserved -> R.string.room_wardrobe_entry_preserved
        }), color = MaterialTheme.colorScheme.onSurfaceVariant)
        entry.reason?.let { Text(it.text(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RoomWardrobeResult.text(): String = when (this) {
    is RoomWardrobeResult.Sent -> stringResource(when (operation) {
        RoomWardrobeOperation.Save -> R.string.room_wardrobe_save_sent
        RoomWardrobeOperation.Rename -> R.string.room_wardrobe_rename_sent
        RoomWardrobeOperation.Load -> R.string.room_wardrobe_load_sent
    })
    is RoomWardrobeResult.AppearanceObserved -> stringResource(R.string.room_wardrobe_appearance_observed)
    is RoomWardrobeResult.Rejected -> reason.text()
    RoomWardrobeResult.Failed -> stringResource(R.string.room_wardrobe_failed)
}

@Composable
private fun BcWardrobeUnavailableReason.text(): String = stringResource(when (this) {
    BcWardrobeUnavailableReason.Offline -> R.string.room_wardrobe_offline
    BcWardrobeUnavailableReason.NotInRoom -> R.string.room_wardrobe_not_in_room
    BcWardrobeUnavailableReason.SelfUnavailable -> R.string.room_wardrobe_self_unavailable
    BcWardrobeUnavailableReason.AccountChanged -> R.string.room_wardrobe_account_changed
    BcWardrobeUnavailableReason.RoomChanged -> R.string.room_wardrobe_room_changed
    BcWardrobeUnavailableReason.CharacterStateChanged -> R.string.room_wardrobe_character_changed
    BcWardrobeUnavailableReason.UnsupportedAssetFamily -> R.string.room_wardrobe_unsupported_family
    BcWardrobeUnavailableReason.CatalogPreparing -> R.string.room_wardrobe_catalog_preparing
    BcWardrobeUnavailableReason.PrivateStateUnavailable -> R.string.room_wardrobe_private_unavailable
    BcWardrobeUnavailableReason.WardrobeLocked -> R.string.room_wardrobe_locked
    BcWardrobeUnavailableReason.Restrained -> R.string.room_wardrobe_restrained
    BcWardrobeUnavailableReason.ClubSlave -> R.string.room_wardrobe_club_slave
    BcWardrobeUnavailableReason.GameBlocksChange -> R.string.room_wardrobe_game_blocked
    BcWardrobeUnavailableReason.AsylumBlocksChange -> R.string.room_wardrobe_asylum_blocked
    BcWardrobeUnavailableReason.BlockChangeRule -> R.string.room_wardrobe_rule_blocked
    BcWardrobeUnavailableReason.BlockChangeOwnerRule -> R.string.room_wardrobe_owner_blocked
    BcWardrobeUnavailableReason.InvalidSlot -> R.string.room_wardrobe_invalid_slot
    BcWardrobeUnavailableReason.EmptySlot -> R.string.room_wardrobe_empty_slot
    BcWardrobeUnavailableReason.UnsupportedSlot -> R.string.room_wardrobe_unsupported_slot
    BcWardrobeUnavailableReason.RevisionChanged -> R.string.room_wardrobe_revision_changed
    BcWardrobeUnavailableReason.InvalidName -> R.string.room_wardrobe_invalid_name
    BcWardrobeUnavailableReason.NamesUnavailable -> R.string.room_wardrobe_names_unavailable
    BcWardrobeUnavailableReason.NoChanges -> R.string.room_wardrobe_no_changes
})

@Composable
private fun BcWardrobeEntryReason.text(): String = stringResource(when (this) {
    BcWardrobeEntryReason.UnknownGroup -> R.string.room_wardrobe_skip_unknown_group
    BcWardrobeEntryReason.UnknownAsset -> R.string.room_wardrobe_skip_unknown_asset
    BcWardrobeEntryReason.UnsupportedMetadata -> R.string.room_wardrobe_skip_metadata
    BcWardrobeEntryReason.DuplicateGroup -> R.string.room_wardrobe_skip_duplicate
    BcWardrobeEntryReason.ItemGroupPreserved -> R.string.room_wardrobe_skip_item
    BcWardrobeEntryReason.LockedCurrentItem -> R.string.room_wardrobe_skip_locked
    BcWardrobeEntryReason.BlockAppearanceOwnerRule -> R.string.room_wardrobe_skip_owner
    BcWardrobeEntryReason.GroupNotCustomizable -> R.string.room_wardrobe_skip_group
    BcWardrobeEntryReason.ColorNotCustomizable -> R.string.room_wardrobe_skip_color
    BcWardrobeEntryReason.InventoryUnavailable -> R.string.room_wardrobe_skip_inventory
    BcWardrobeEntryReason.UnsafeSlotProperty -> R.string.room_wardrobe_skip_slot_property
    BcWardrobeEntryReason.UnsupportedItemProperty -> R.string.room_wardrobe_skip_item_property
    BcWardrobeEntryReason.PermissionDenied -> R.string.room_wardrobe_skip_permission
    BcWardrobeEntryReason.MandatoryFallbackUnavailable -> R.string.room_wardrobe_skip_default
    BcWardrobeEntryReason.FinalAppearanceIncompatible -> R.string.room_wardrobe_skip_incompatible
})
