package dev.vivian.mobilebc.feature.rooms

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.vivian.mobilebc.core.model.BcRoomTypeFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.ui.RoomRow

@Composable
fun RoomsScreen(
    uiState: RoomsUiState,
    onQueryChange: (String) -> Unit,
    onSpaceChange: (RoomSpaceOption) -> Unit,
    onRoomTypeChange: (BcRoomTypeFilter) -> Unit,
    onIncludeFullRoomsChange: (Boolean) -> Unit,
    onIncludeLockedRoomsChange: (Boolean) -> Unit,
    onSearchDescriptionChange: (Boolean) -> Unit,
    onSearchRooms: () -> Unit,
    onJoinRoom: (BcRoomSummary) -> Unit,
    onLeaveRoom: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCreateRoom: () -> Unit = {},
    onCloseRoomForm: () -> Unit = {},
    onRoomFormDraftChange: (RoomAdministrationDraft) -> Unit = {},
    onSubmitRoomForm: () -> Unit = {},
) {
    val canEditSearch = !uiState.isSearching && uiState.joiningRoomName == null && uiState.leavingRoomName == null &&
        !uiState.roomMutationBusy && uiState.roomForm == null
    val canSearch = uiState.online && canEditSearch
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val search = {
        if (canSearch) {
            focusManager.clearFocus()
            keyboardController?.hide()
            onSearchRooms()
        }
    }
    LaunchedEffect(uiState.online) {
        if (uiState.online && !uiState.hasSearched) onSearchRooms()
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.rooms_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Button(
                onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onOpenCreateRoom()
                },
                enabled = uiState.canCreateRoom && uiState.roomForm == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.rooms_create_room)) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = canEditSearch,
                label = { Text(stringResource(R.string.rooms_search_placeholder)) },
                trailingIcon = if (uiState.query.isNotEmpty()) {
                    {
                        TextButton(onClick = { onQueryChange("") }, enabled = canEditSearch) {
                            Text(stringResource(R.string.rooms_clear_search))
                        }
                    }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { search() },
                ),
            )
            Button(
                onClick = search,
                enabled = canSearch,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (uiState.isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        if (uiState.isSearching) {
                            stringResource(R.string.rooms_searching)
                        } else {
                            stringResource(R.string.rooms_search)
                        },
                    )
                }
            }
        }
        FilterRow(
            title = stringResource(R.string.rooms_zone),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            RoomSpaceOption.entries.forEach { option ->
                FilterChip(
                    selected = uiState.space == option,
                    enabled = canEditSearch,
                    onClick = { onSpaceChange(option) },
                    label = { Text(option.displayLabel()) },
                )
            }
        }
        FilterRow(
            title = stringResource(R.string.rooms_type),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            BcRoomTypeFilter.entries.forEach { option ->
                FilterChip(
                    selected = uiState.roomType == option,
                    enabled = canEditSearch,
                    onClick = { onRoomTypeChange(option) },
                    label = { Text(option.displayLabel()) },
                )
            }
        }
        FilterRow(
            title = stringResource(R.string.rooms_search_options),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            FilterChip(
                selected = uiState.includeFullRooms,
                enabled = canEditSearch,
                onClick = { onIncludeFullRoomsChange(!uiState.includeFullRooms) },
                label = { Text(stringResource(R.string.rooms_include_full)) },
            )
            FilterChip(
                selected = uiState.includeLockedRooms,
                enabled = canEditSearch,
                onClick = { onIncludeLockedRoomsChange(!uiState.includeLockedRooms) },
                label = { Text(stringResource(R.string.rooms_include_locked)) },
            )
            FilterChip(
                selected = uiState.searchDescription,
                enabled = canEditSearch,
                onClick = { onSearchDescriptionChange(!uiState.searchDescription) },
                label = { Text(stringResource(R.string.rooms_search_descriptions)) },
            )
        }
        if (!uiState.online) {
            Text(
                text = stringResource(R.string.rooms_connection_required),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        uiState.errorMessageRes?.let { messageRes ->
            Text(
                text = stringResource(messageRes),
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Assertive }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        uiState.joiningRoomName?.let { roomName ->
            Text(
                text = stringResource(R.string.rooms_joining, roomName),
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.currentRoomName?.let { roomName ->
                item("current-room") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.rooms_current_room, roomName),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    onLeaveRoom()
                                },
                                enabled = canSearch,
                            ) {
                                if (uiState.leavingRoomName != null) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                                Text(
                                    stringResource(
                                        if (uiState.leavingRoomName != null) R.string.rooms_leaving else R.string.rooms_leave,
                                    ),
                                    modifier = Modifier.padding(start = if (uiState.leavingRoomName != null) 8.dp else 0.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (uiState.rooms.isEmpty() && !uiState.isSearching && uiState.errorMessageRes == null && uiState.online) {
                item("rooms-empty") {
                    Text(
                        text = if (uiState.hasSearched) {
                            stringResource(R.string.rooms_no_matches)
                        } else if (!uiState.searchDescription) {
                            stringResource(R.string.rooms_search_name_hint)
                        } else {
                            stringResource(R.string.rooms_search_hint)
                        },
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(uiState.rooms, key = { "room:${it.name.value}" }) { room ->
                    Column {
                        RoomRow(
                            room = room,
                            enabled = canSearch && room.canJoin != false && room.memberCount < room.memberLimit,
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onJoinRoom(room)
                            },
                        )
                        val unavailableMessage = when {
                            room.canJoin == false -> R.string.rooms_cannot_join
                            room.memberCount >= room.memberLimit -> R.string.rooms_room_full
                            else -> null
                        }
                        unavailableMessage?.let { message ->
                            Text(
                                text = stringResource(message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    uiState.roomForm?.let { form ->
        RoomAdministrationFormSheet(
            state = form,
            onDraftChange = onRoomFormDraftChange,
            onSubmit = onSubmitRoomForm,
            onClose = onCloseRoomForm,
        )
    }
}

@Composable
private fun FilterRow(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun RoomSpaceOption.displayLabel(): String = when (this) {
    RoomSpaceOption.Mixed -> stringResource(R.string.rooms_zone_mixed)
    RoomSpaceOption.FemaleOnly -> stringResource(R.string.rooms_zone_female)
    RoomSpaceOption.MaleOnly -> stringResource(R.string.rooms_zone_male)
    RoomSpaceOption.Asylum -> stringResource(R.string.rooms_zone_asylum)
}

@Composable
private fun BcRoomTypeFilter.displayLabel(): String =
    when (this) {
        BcRoomTypeFilter.All -> stringResource(R.string.rooms_type_all)
        BcRoomTypeFilter.ChatRoom -> stringResource(R.string.rooms_type_chat)
        BcRoomTypeFilter.Hybrid -> stringResource(R.string.rooms_type_hybrid)
        BcRoomTypeFilter.Map -> stringResource(R.string.rooms_type_map)
    }
