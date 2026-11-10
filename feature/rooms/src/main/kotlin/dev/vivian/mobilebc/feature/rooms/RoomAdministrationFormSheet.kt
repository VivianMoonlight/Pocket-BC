package dev.vivian.mobilebc.feature.rooms

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import dev.vivian.mobilebc.core.ui.AnimatedModalSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.model.BcRoomAccess
import dev.vivian.mobilebc.core.model.BcRoomBasicField
import dev.vivian.mobilebc.core.model.BcRoomLanguage
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomMutationUnknownReason
import dev.vivian.mobilebc.core.model.BcRoomSpace
import dev.vivian.mobilebc.core.model.BcRoomVisibility

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomAdministrationFormSheet(
    state: RoomAdministrationFormState,
    onDraftChange: (RoomAdministrationDraft) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onReload: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    val draft = state.draft
    val editing = state.mode == RoomAdministrationFormMode.Edit
    val backgroundOptions = remember(state.backgroundNames) {
        state.backgroundNames.map { RoomAdministrationOption(it, it) }
    }
    AnimatedModalSheet(onDismissRequest = onClose) { requestClose ->
        Column(modifier.fillMaxWidth().imePadding().heightIn(max = 760.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (editing) R.string.room_admin_edit_title else R.string.room_admin_create_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = requestClose, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.room_details_close))
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("scope") {
                    Text(
                        stringResource(if (editing) R.string.room_admin_edit_scope else R.string.room_admin_create_scope),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item("feedback") { RoomAdministrationFeedback(state) }
                item("name") {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { onDraftChange(draft.copy(name = it)) },
                        label = { Text(stringResource(R.string.room_admin_name)) },
                        supportingText = { Text(stringResource(R.string.room_admin_name_help)) },
                        enabled = !state.busy,
                        isError = state.validation?.field == BcRoomBasicField.Name,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item("description") {
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { onDraftChange(draft.copy(description = it)) },
                        label = { Text(stringResource(R.string.room_admin_description)) },
                        supportingText = { Text(stringResource(R.string.room_admin_description_help, draft.description.length)) },
                        enabled = !state.busy,
                        isError = state.validation?.field == BcRoomBasicField.Description,
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item("limit") {
                    OutlinedTextField(
                        value = draft.memberLimit,
                        onValueChange = { onDraftChange(draft.copy(memberLimit = it)) },
                        label = { Text(stringResource(R.string.room_admin_limit)) },
                        supportingText = { Text(stringResource(R.string.room_admin_limit_help)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !state.busy,
                        isError = state.validation?.field == BcRoomBasicField.MemberLimit,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item("background") {
                    val known = draft.background in state.backgroundNames
                    RoomAdministrationChoice(
                        label = stringResource(R.string.room_admin_background),
                        valueLabel = draft.background.ifEmpty { stringResource(R.string.room_admin_background_unavailable) },
                        customValue = editing && !known,
                        enabled = !state.busy && state.backgroundNames.isNotEmpty(),
                        options = backgroundOptions,
                        selected = draft.background,
                        onSelect = { onDraftChange(draft.copy(background = it)) },
                    )
                }
                item("language") {
                    val language = BcRoomLanguage.fromWire(draft.language)
                    RoomAdministrationChoice(
                        label = stringResource(R.string.room_admin_language),
                        valueLabel = language?.let { roomLanguageLabel(it) } ?: draft.language,
                        customValue = language == null,
                        enabled = !state.busy,
                        options = BcRoomLanguage.entries.map { RoomAdministrationOption(it.wireValue, roomLanguageLabel(it)) },
                        selected = draft.language,
                        onSelect = { onDraftChange(draft.copy(language = it)) },
                    )
                }
                item("visibility") {
                    val visibility = BcRoomVisibility.fromWire(draft.visibility)
                    RoomAdministrationChoice(
                        label = stringResource(R.string.room_admin_visibility),
                        description = stringResource(R.string.room_admin_visibility_help),
                        valueLabel = visibility?.let { roomVisibilityLabel(it) } ?: roomRolesLabel(draft.visibility),
                        customValue = visibility == null,
                        enabled = !state.busy,
                        options = BcRoomVisibility.entries.map { RoomAdministrationOption(it.wireValues, roomVisibilityLabel(it)) },
                        selected = visibility?.wireValues ?: draft.visibility,
                        onSelect = { onDraftChange(draft.copy(visibility = it)) },
                    )
                }
                item("access") {
                    val access = BcRoomAccess.fromWire(draft.access)
                    RoomAdministrationChoice(
                        label = stringResource(R.string.room_admin_access),
                        description = stringResource(R.string.room_admin_access_help),
                        valueLabel = access?.let { roomAccessLabel(it) } ?: roomRolesLabel(draft.access),
                        customValue = access == null,
                        enabled = !state.busy,
                        options = BcRoomAccess.entries.map { RoomAdministrationOption(it.wireValues, roomAccessLabel(it)) },
                        selected = access?.wireValues ?: draft.access,
                        onSelect = { onDraftChange(draft.copy(access = it)) },
                    )
                }
                if (!editing) {
                    item("space") {
                        RoomAdministrationChoice(
                            label = stringResource(R.string.rooms_zone),
                            valueLabel = roomSpaceLabel(draft.space),
                            enabled = !state.busy,
                            options = BcRoomSpace.entries.map { RoomAdministrationOption(it, roomSpaceLabel(it)) },
                            selected = draft.space,
                            onSelect = { onDraftChange(draft.copy(space = it)) },
                        )
                    }
                }
                if (editing) {
                    item("reload") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.room_admin_reload_help),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = onReload, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.room_admin_reload))
                            }
                        }
                    }
                }
            }
            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).heightIn(min = 48.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(
                    stringResource(
                        if (state.busy) R.string.room_admin_waiting
                        else if (editing) R.string.room_admin_save else R.string.rooms_create_room,
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RoomAdministrationFeedback(state: RoomAdministrationFormState) {
    val message = when (val failure = state.failure) {
        is RoomAdministrationFormFailure.Rejected -> stringResource(roomRejectionMessage(failure.rejection.reason))
        is RoomAdministrationFormFailure.OutcomeUnknown -> stringResource(
            when (failure.reason) {
                BcRoomMutationUnknownReason.TimedOut -> R.string.room_admin_unknown_timeout
                BcRoomMutationUnknownReason.Disconnected -> R.string.room_admin_unknown_disconnected
                BcRoomMutationUnknownReason.Cancelled -> R.string.room_admin_unknown_cancelled
                BcRoomMutationUnknownReason.InvalidResponse -> R.string.room_admin_unknown_response
            },
        )
        RoomAdministrationFormFailure.Failed -> stringResource(R.string.room_admin_failed)
        null -> state.validation?.let { stringResource(roomRejectionMessage(it.reason)) }
    }
    if (message != null) {
        Text(
            message,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (state.validation?.reason == BcRoomMutationRejectionReason.NothingToUpdate && state.failure == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (state.busy) {
        Text(stringResource(R.string.room_admin_waiting_help), style = MaterialTheme.typography.bodySmall)
    }
}

private data class RoomAdministrationOption<T>(val value: T, val label: String)

@Composable
private fun <T> RoomAdministrationChoice(
    label: String,
    valueLabel: String,
    options: List<RoomAdministrationOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    customValue: Boolean = false,
    description: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(valueLabel)
        }
        if (customValue) {
            Text(
                stringResource(R.string.room_admin_custom_value, valueLabel.ifEmpty { stringResource(R.string.room_admin_empty_value) }),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (expanded && enabled) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(label) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp).selectableGroup()) {
                    itemsIndexed(options) { _, option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = option.value == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.value); expanded = false },
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = option.value == selected, onClick = null)
                            Text(option.label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.room_details_close))
                }
            },
        )
    }
}

@Composable
private fun roomRolesLabel(roles: List<String>): String =
    roles.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.room_admin_empty_roles)

@Composable
private fun roomSpaceLabel(value: BcRoomSpace): String = stringResource(
    when (value) {
        BcRoomSpace.Female -> R.string.rooms_zone_female
        BcRoomSpace.Mixed -> R.string.rooms_zone_mixed
        BcRoomSpace.Male -> R.string.rooms_zone_male
        BcRoomSpace.Asylum -> R.string.rooms_zone_asylum
    },
)

@Composable
private fun roomLanguageLabel(value: BcRoomLanguage): String = stringResource(
    when (value) {
        BcRoomLanguage.English -> R.string.room_admin_language_en
        BcRoomLanguage.German -> R.string.room_admin_language_de
        BcRoomLanguage.French -> R.string.room_admin_language_fr
        BcRoomLanguage.Spanish -> R.string.room_admin_language_es
        BcRoomLanguage.Chinese -> R.string.room_admin_language_cn
        BcRoomLanguage.Russian -> R.string.room_admin_language_ru
        BcRoomLanguage.Ukrainian -> R.string.room_admin_language_ua
    },
)

@Composable
private fun roomVisibilityLabel(value: BcRoomVisibility): String = stringResource(
    when (value) {
        BcRoomVisibility.Public -> R.string.room_admin_everyone
        BcRoomVisibility.AdministratorsAndWhitelist -> R.string.room_admin_admin_whitelist
        BcRoomVisibility.Administrators -> R.string.room_admin_admin_only
        BcRoomVisibility.Unlisted -> R.string.room_admin_unlisted
    },
)

@Composable
private fun roomAccessLabel(value: BcRoomAccess): String = stringResource(
    when (value) {
        BcRoomAccess.Public -> R.string.room_admin_everyone
        BcRoomAccess.AdministratorsAndWhitelist -> R.string.room_admin_admin_whitelist
        BcRoomAccess.Administrators -> R.string.room_admin_admin_only
    },
)

private fun roomRejectionMessage(reason: BcRoomMutationRejectionReason): Int = when (reason) {
    BcRoomMutationRejectionReason.InvalidName -> R.string.room_admin_invalid_name
    BcRoomMutationRejectionReason.DescriptionTooLong -> R.string.room_admin_description_too_long
    BcRoomMutationRejectionReason.InvalidMemberLimit -> R.string.room_admin_invalid_limit
    BcRoomMutationRejectionReason.BackgroundCatalogUnavailable -> R.string.room_admin_background_unavailable
    BcRoomMutationRejectionReason.UnknownBackground -> R.string.room_admin_unknown_background
    BcRoomMutationRejectionReason.UnsupportedLanguage -> R.string.room_admin_unsupported_language
    BcRoomMutationRejectionReason.UnsupportedVisibility -> R.string.room_admin_unsupported_visibility
    BcRoomMutationRejectionReason.UnsupportedAccess -> R.string.room_admin_unsupported_access
    BcRoomMutationRejectionReason.NothingToUpdate -> R.string.room_admin_nothing_to_update
    BcRoomMutationRejectionReason.ConnectionUnavailable -> R.string.room_admin_offline
    BcRoomMutationRejectionReason.AccountUnavailable -> R.string.room_admin_account_changed
    BcRoomMutationRejectionReason.AlreadyInRoom -> R.string.rooms_create_requires_no_room
    BcRoomMutationRejectionReason.RoomUnavailable -> R.string.room_admin_room_unavailable
    BcRoomMutationRejectionReason.RoomChanged -> R.string.room_admin_room_changed
    BcRoomMutationRejectionReason.NotAdministrator -> R.string.room_admin_not_administrator
    BcRoomMutationRejectionReason.SettingsUnavailable -> R.string.room_admin_settings_unavailable
    BcRoomMutationRejectionReason.EditConflict -> R.string.room_admin_edit_conflict
    BcRoomMutationRejectionReason.Busy -> R.string.room_admin_busy
    BcRoomMutationRejectionReason.PendingResponse -> R.string.room_admin_pending_response
    BcRoomMutationRejectionReason.ServerRejected -> R.string.room_admin_server_rejected
}
