package dev.vivian.mobilebc.feature.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.core.model.ConversationAvailability
import dev.vivian.mobilebc.core.ui.ConversationRow
import dev.vivian.mobilebc.core.ui.SessionStatusBanner
import dev.vivian.mobilebc.core.ui.SessionStatusTone
import dev.vivian.mobilebc.core.ui.StatusChip
import dev.vivian.mobilebc.feature.chat.R

@Composable
fun ConversationsScreen(
    uiState: ConversationsUiState,
    onOpenConversation: (BcConversationId) -> Unit,
    onSaveScrollPosition: (Int, Int) -> Unit,
    onAcceptFriendRequest: (Int) -> Unit,
    onDismissFriendRequest: (Int) -> Unit,
    onClearFriendRequestMessage: () -> Unit,
    modifier: Modifier = Modifier,
    sessionStatusText: String? = null,
    sessionStatusTone: SessionStatusTone = SessionStatusTone.Neutral,
    sessionStatusInProgress: Boolean = false,
    onOpenRooms: (() -> Unit)? = null,
    onOpenContacts: (() -> Unit)? = null,
) {
    var otherExpanded by rememberSaveable { mutableStateOf(false) }
    val latestDirectConversations = remember(uiState.conversations) {
        uiState.conversations.filter { it.type == ConversationType.Direct && it.hasMessages }
    }
    val whisperContacts = remember(uiState.conversations) {
        uiState.conversations.filter {
            it.type == ConversationType.Direct &&
                !it.hasMessages &&
                ConversationAvailability.SameRoom in it.availability
        }
    }
    val onlineFriendContacts = remember(uiState.conversations) {
        uiState.conversations.filter {
            it.type == ConversationType.Direct &&
                !it.hasMessages &&
                ConversationAvailability.OnlineFriend in it.availability &&
                ConversationAvailability.SameRoom !in it.availability
        }
    }
    val historicalRooms = remember(uiState.conversations, uiState.currentRoomConversationId) {
        uiState.conversations.filter {
            it.type == ConversationType.CurrentRoom && it.id != uiState.currentRoomConversationId
        }
    }
    val systemConversations = remember(uiState.conversations) {
        uiState.conversations.filter { it.type == ConversationType.System }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.firstVisibleConversationIndex,
        initialFirstVisibleItemScrollOffset = uiState.firstVisibleConversationScrollOffset,
    )
    DisposableEffect(listState) {
        onDispose {
            onSaveScrollPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    uiState.accountTitle,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(uiState.accountSubtitle, style = MaterialTheme.typography.bodyMedium)
            }
            onOpenContacts?.let { open ->
                TextButton(onClick = open) { Text(stringResource(R.string.chat_contacts)) }
            }
            StatusChip(uiState.statusText)
        }
        sessionStatusText
            ?.takeIf { it.isNotBlank() }
            ?.let { message ->
                SessionStatusBanner(
                    text = message,
                    tone = sessionStatusTone,
                    showProgress = sessionStatusInProgress,
                )
            }
        if (uiState.persistenceFailure != null) {
            SessionStatusBanner(
                text = stringResource(R.string.timeline_storage_unavailable),
                tone = SessionStatusTone.Error,
            )
        }
        if (latestDirectConversations.isEmpty() && whisperContacts.isEmpty() && onlineFriendContacts.isEmpty() &&
            historicalRooms.isEmpty() && systemConversations.isEmpty() &&
            uiState.friendRequests.isEmpty() && uiState.friendRequestMessage == null
        ) {
            Text(
                text = stringResource(R.string.conversations_empty),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onOpenRooms?.let { openRooms ->
                Button(onClick = openRooms, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.conversations_find_room))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (uiState.friendRequests.isNotEmpty()) {
                    item("section-friend-requests") {
                        ConversationSectionTitle(stringResource(R.string.conversations_friend_requests))
                    }
                    items(uiState.friendRequests, key = { "friend-request-${it.memberNumber}" }) { request ->
                        FriendRequestCard(
                            request = request,
                            enabled = uiState.acceptingFriendRequestMemberNumber == null,
                            accepting = uiState.acceptingFriendRequestMemberNumber == request.memberNumber,
                            onAccept = { onAcceptFriendRequest(request.memberNumber) },
                            onDismiss = { onDismissFriendRequest(request.memberNumber) },
                        )
                    }
                }
                uiState.friendRequestMessage?.let { message ->
                    item("friend-request-message") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(message, modifier = Modifier.weight(1f))
                                TextButton(onClick = onClearFriendRequestMessage) {
                                    Text(stringResource(R.string.conversations_close_message))
                                }
                            }
                        }
                    }
                }
                if (latestDirectConversations.isNotEmpty()) {
                    item("section-direct") {
                        ConversationSectionTitle(stringResource(R.string.conversations_recent))
                    }
                    items(latestDirectConversations, key = { it.id.value }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            highlighted = false,
                            supportingText = conversation.subtitle.takeIf { conversation.hasMessages },
                            trailingText = conversation.presenceLabel(uiState.directPresenceKnown),
                            onClick = {
                                onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                onOpenConversation(conversation.id)
                            },
                        )
                    }
                }
                if (whisperContacts.isNotEmpty()) {
                    item("section-whisper") {
                        ConversationSectionTitle(stringResource(R.string.conversations_whisper_available))
                    }
                    items(whisperContacts, key = { it.id.value }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            highlighted = false,
                            supportingText = null,
                            trailingText = stringResource(R.string.conversations_current_room),
                            onClick = {
                                onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                onOpenConversation(conversation.id)
                            },
                        )
                    }
                }
                if (onlineFriendContacts.isNotEmpty()) {
                    item("section-online-friends") {
                        ConversationSectionTitle(stringResource(R.string.conversations_online_friends))
                    }
                    items(onlineFriendContacts, key = { it.id.value }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            highlighted = false,
                            supportingText = null,
                            trailingText = conversation.presenceLabel(uiState.directPresenceKnown),
                            onClick = {
                                onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                onOpenConversation(conversation.id)
                            },
                        )
                    }
                }
                if (systemConversations.isNotEmpty()) {
                    item("section-other") {
                        TextButton(onClick = { otherExpanded = !otherExpanded }) {
                            Text(stringResource(if (otherExpanded) R.string.conversations_hide_other else R.string.conversations_show_other))
                        }
                    }
                }
                if (historicalRooms.isNotEmpty()) {
                    item("section-history-rooms") {
                        ConversationSectionTitle(stringResource(R.string.conversations_previous_rooms))
                    }
                    items(historicalRooms, key = { it.id.value }) { conversation ->
                        ConversationRow(conversation = conversation, highlighted = false, onClick = {
                            onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            onOpenConversation(conversation.id)
                        })
                    }
                }
                if (otherExpanded && systemConversations.isNotEmpty()) {
                    item("section-system") {
                        ConversationSectionTitle(stringResource(R.string.conversations_system))
                    }
                    items(systemConversations, key = { it.id.value }) { conversation ->
                        ConversationRow(conversation = conversation, highlighted = false, onClick = {
                            onSaveScrollPosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            onOpenConversation(conversation.id)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRequestCard(
    request: FriendRequestUiState,
    enabled: Boolean,
    accepting: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(request.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.conversations_friend_request_detail, request.memberNumber),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, enabled = enabled) {
                    if (accepting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    Text(stringResource(if (accepting) R.string.conversations_accepting else R.string.conversations_accept))
                }
                OutlinedButton(onClick = onDismiss, enabled = enabled) {
                    Text(stringResource(R.string.conversations_dismiss))
                }
            }
        }
    }
}

@Composable
private fun dev.vivian.mobilebc.core.model.BcConversation.historySubtitle(): String? = when {
    !hasMessages -> null
    subtitle == "No longer in this room" -> stringResource(R.string.conversations_left_room)
    else -> subtitle
}

@Composable
private fun dev.vivian.mobilebc.core.model.BcConversation.presenceLabel(presenceKnown: Boolean): String = when {
    ConversationAvailability.SameRoom in availability -> stringResource(R.string.conversations_current_room)
    ConversationAvailability.OnlineFriend in availability && !onlineRoomName.isNullOrBlank() ->
        stringResource(R.string.conversations_online_in_room, onlineRoomName.orEmpty())
    ConversationAvailability.OnlineFriend in availability -> stringResource(R.string.conversations_online)
    presenceKnown -> stringResource(R.string.conversations_offline)
    else -> stringResource(R.string.conversations_unknown)
}

@Composable
private fun ConversationSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
