package dev.vivian.mobilebc.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.ChatBackground
import dev.vivian.mobilebc.core.model.ThemeColorRole
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcRoomChatRejectionReason
import dev.vivian.mobilebc.core.model.bcEmoteDisplayText
import dev.vivian.mobilebc.core.ui.MessageBubble
import dev.vivian.mobilebc.core.ui.ChatWallpaper
import dev.vivian.mobilebc.core.ui.MobileBcIcons
import dev.vivian.mobilebc.core.ui.SessionStatusBanner
import dev.vivian.mobilebc.core.ui.SessionStatusTone
import dev.vivian.mobilebc.core.ui.InteractionBoundary
import dev.vivian.mobilebc.core.ui.AnimatedModalSheet
import dev.vivian.mobilebc.core.ui.InteractionPanelControls
import dev.vivian.mobilebc.core.ui.LocalInteractionPanelControls
import dev.vivian.mobilebc.core.ui.LocalScreenInteractive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val CollapsedHistoryMessageCount = 50

enum class ChatComposerTool {
    Action,
    Item,
    Clothing,
}

private data class ToolPanelContent(val tool: ChatComposerTool?, val content: (@Composable () -> Unit)?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onComposerModeChange: (ChatComposerMode) -> Unit,
    onSend: () -> Unit,
    onReply: (String) -> Unit,
    onCancelReply: () -> Unit,
    onOpenRoomDetails: () -> Unit,
    onOpenCharacterProfile: (Int) -> Unit,
    onOpenRoomMembers: (() -> Unit)? = null,
    roomMemberCount: Int = 0,
    selfCharacterName: String? = null,
    onOpenActionPicker: (() -> Unit)? = null,
    onOpenItemPicker: (() -> Unit)? = null,
    onOpenClothingPicker: (() -> Unit)? = null,
    onCloseToolPanel: () -> Unit = {},
    onToolBack: () -> Unit = onCloseToolPanel,
    activeTool: ChatComposerTool? = null,
    actionPickerContent: (@Composable () -> Unit)? = null,
    itemPickerContent: (@Composable () -> Unit)? = null,
    clothingPickerContent: (@Composable () -> Unit)? = null,
    roomPortraitControl: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    sessionStatusText: String? = null,
    sessionStatusTone: SessionStatusTone = SessionStatusTone.Neutral,
    sessionStatusInProgress: Boolean = false,
    inputEnabled: Boolean = true,
    isVisible: Boolean = true,
    ownsPage: Boolean = isVisible,
    onOpenConversations: (() -> Unit)? = null,
    onOpenSelf: (() -> Unit)? = null,
    onOpenMyProfile: (() -> Unit)? = null,
    onOpenContacts: (() -> Unit)? = null,
    onReturnToRoom: (() -> Unit)? = null,
    onInteractWithCharacter: ((Int) -> Unit)? = null,
    onWhisperTargetChange: (Int) -> Unit = {},
    onReturnToPublic: () -> Unit = {},
    onBrowseRooms: (() -> Unit)? = null,
    onLeaveRoom: (() -> Unit)? = null,
    leaveInProgress: Boolean = false,
    leaveErrorMessage: String? = null,
    onOpenSelfState: (() -> Unit)? = null,
    onOpenWardrobe: (() -> Unit)? = null,
    onLoadOlderMessages: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    chatBackground: ChatBackground = ChatBackground(),
    customColors: Map<ThemeColorRole, Long> = emptyMap(),
) {
    fun chatColor(role: ThemeColorRole): Color? = customColors[role]?.let(::Color)
    val conversationKey = uiState.conversationId.value
    val listState = rememberSaveable(conversationKey, saver = LazyListState.Saver) { LazyListState() }
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    val inputFocusRequester = remember(conversationKey) { FocusRequester() }
    var historyExpanded by rememberSaveable(conversationKey) { mutableStateOf(false) }
    var selectedMessageIds by remember(conversationKey) { mutableStateOf(emptySet<String>()) }
    var stickToBottom by rememberSaveable(conversationKey) { mutableStateOf(true) }
    val userScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    stickToBottom = !listState.canScrollBackward
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                stickToBottom = !listState.canScrollBackward
                return Velocity.Zero
            }
        }
    }
    var pendingReplyJumpId by remember(conversationKey) { mutableStateOf<String?>(null) }
    var requestInputFocus by remember(conversationKey) { mutableStateOf(false) }
    var roomMenuExpanded by remember(conversationKey) { mutableStateOf(false) }
    var confirmLeave by remember(conversationKey) { mutableStateOf(false) }
    var selfMenuExpanded by remember(conversationKey) { mutableStateOf(false) }
    var toolExpanded by rememberSaveable(conversationKey) { mutableStateOf(false) }
    var showToolDialog by remember(conversationKey) { mutableStateOf(false) }
    var halfPanelTop by remember(conversationKey) { mutableStateOf(0) }
    val toolStateHolder = rememberSaveableStateHolder()
    LaunchedEffect(activeTool, isVisible) {
        if (activeTool == null || !isVisible) toolExpanded = false
    }
    LaunchedEffect(toolExpanded, activeTool, isVisible) {
        if (toolExpanded && activeTool != null && isVisible) {
            // Let the half-screen provider save its state before the dialog restores it.
            withFrameNanos { }
            showToolDialog = true
        }
        else {
            if (activeTool != null && isVisible) delay(220)
            showToolDialog = false
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                requestInputFocus = false
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(isVisible) {
        onDispose {
            if (isVisible) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }
    }
    var wasOwner by remember { mutableStateOf(ownsPage) }
    LaunchedEffect(ownsPage) {
        if (wasOwner && !ownsPage) {
            selectedMessageIds = emptySet()
            roomMenuExpanded = false
            selfMenuExpanded = false
            confirmLeave = false
            requestInputFocus = false
        }
        wasOwner = ownsPage
    }
    LaunchedEffect(isVisible) {
        if (!isVisible) {
            roomMenuExpanded = false
            selfMenuExpanded = false
            confirmLeave = false
            requestInputFocus = false
        }
    }
    val hiddenHistoryCount = if (historyExpanded) {
        0
    } else {
        (uiState.messages.size - CollapsedHistoryMessageCount).coerceAtLeast(0)
    }
    val visibleMessages = remember(uiState.messages, hiddenHistoryCount) {
        if (hiddenHistoryCount > 0) uiState.messages.takeLast(CollapsedHistoryMessageCount)
        else uiState.messages
    }
    val reversedVisibleMessages = remember(visibleMessages) { visibleMessages.asReversed() }
    val totalListItems = visibleMessages.size + if (hiddenHistoryCount > 0) 1 else 0
    val selectedMessages = remember(uiState.messages, selectedMessageIds) {
        if (selectedMessageIds.isEmpty()) emptyList()
        else uiState.messages.filter { it.chatUiKey in selectedMessageIds }
    }
    val realtimeInputEnabled = inputEnabled && isVisible
    val replyTarget = selectedMessages.singleOrNull()
        ?.takeIf { uiState.isCurrentRoom }
        ?.takeIf { it.chatUiKey in uiState.replyableMessageIds }
        ?.takeIf { realtimeInputEnabled && uiState.composer is ChatComposerState.Ready }

    fun handleBack() {
        when (chatBackAction(imeBottom > navigationBottom, selectedMessageIds.isNotEmpty(), activeTool, uiState.reply != null)) {
            ChatBackAction.HideKeyboard -> {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
            ChatBackAction.ClearSelection -> selectedMessageIds = emptySet()
            ChatBackAction.ToolBack -> onToolBack()
            ChatBackAction.CancelReply -> onCancelReply()
            ChatBackAction.Navigate -> onBack()
        }
    }

    BackHandler(enabled = isVisible && (imeBottom > navigationBottom || selectedMessageIds.isNotEmpty() || activeTool != null || uiState.reply != null)) { handleBack() }
    LaunchedEffect(uiState.messages) {
        selectedMessageIds = selectedMessageIds.intersect(uiState.messages.mapTo(mutableSetOf()) { it.chatUiKey })
    }

    fun toggleSelection(messageId: String) {
        selectedMessageIds = if (messageId in selectedMessageIds) {
            selectedMessageIds - messageId
        } else {
            selectedMessageIds + messageId
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.distinctUntilChanged().collect {
            if (stickToBottom && listState.layoutInfo.totalItemsCount > 0) {
                listState.requestScrollToItem(0)
            }
        }
    }
    LaunchedEffect(listState, uiState.messages.lastOrNull()?.chatUiKey) {
        if (stickToBottom && totalListItems > 0) {
            // Override the old message key anchor when new messages are inserted at index zero.
            listState.requestScrollToItem(0)
        }
    }
    LaunchedEffect(pendingReplyJumpId, historyExpanded, uiState.messages) {
        val messageId = pendingReplyJumpId ?: return@LaunchedEffect
        val index = reversedVisibleMessages.indexOfFirst { it.chatUiKey == messageId }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
        pendingReplyJumpId = null
    }
    LaunchedEffect(isVisible, requestInputFocus, activeTool, uiState.composer is ChatComposerState.Ready, realtimeInputEnabled) {
        if (requestInputFocus && (!realtimeInputEnabled || uiState.composer !is ChatComposerState.Ready)) {
            requestInputFocus = false
        } else if (isVisible && requestInputFocus && activeTool == null) {
            inputFocusRequester.requestFocus()
            keyboardController?.show()
            requestInputFocus = false
        }
    }

    if (isVisible && selfMenuExpanded) {
        AnimatedModalSheet(onDismissRequest = { selfMenuExpanded = false }) { _ ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.chat_my_profile), style = MaterialTheme.typography.titleLarge)
                selfCharacterName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.chat_my_character_section), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
                onOpenMyProfile?.let { open ->
                    TextButton(onClick = { selfMenuExpanded = false; open() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chat_my_bio), modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { selfMenuExpanded = false; onOpenWardrobe?.invoke() }, enabled = onOpenWardrobe != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chat_my_wardrobe), modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider()
                Text(stringResource(R.string.chat_room_state_section), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { selfMenuExpanded = false; onOpenSelfState?.invoke() }, enabled = onOpenSelfState != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chat_my_state), modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider()
                Text(stringResource(R.string.chat_account_section), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
                onOpenContacts?.let { open ->
                    TextButton(onClick = { selfMenuExpanded = false; open() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chat_contacts), modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { selfMenuExpanded = false; onOpenSelf?.invoke() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chat_my_settings), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val toolPanelHeight = chatToolPanelHeight(maxHeight)
        var panelHasFocus by remember(conversationKey) { mutableStateOf(false) }
        val panelContent = when (activeTool) {
            ChatComposerTool.Action -> actionPickerContent
            ChatComposerTool.Item -> itemPickerContent
            ChatComposerTool.Clothing -> clothingPickerContent
            null -> null
        }
        if (showToolDialog && activeTool != null && isVisible && panelContent != null) {
            Dialog(
                onDismissRequest = { toolExpanded = false },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                val view = LocalView.current
                val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                val visibility = remember { MutableTransitionState(false) }
                visibility.targetState = toolExpanded
                val slideOffset = (halfPanelTop - WindowInsets.safeDrawing.getTop(density)).coerceAtLeast(0)
                SideEffect {
                    (view.parent as? DialogWindowProvider)?.window?.let { window ->
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = lightSurface
                            isAppearanceLightNavigationBars = lightSurface
                        }
                    }
                }
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = slideInVertically(animationSpec = tween(220), initialOffsetY = { slideOffset }),
                    exit = slideOutVertically(animationSpec = tween(220), targetOffsetY = { slideOffset }),
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            toolStateHolder.SaveableStateProvider(activeTool.name) {
                                CompositionLocalProvider(LocalInteractionPanelControls provides InteractionPanelControls(toolExpanded) { toolExpanded = !toolExpanded }) {
                                    panelContent()
                                }
                            }
                        }
                    }
                }
            }
        }
        Scaffold(
            // Insets belong to the whole layout. Padding only the bottom bar makes
            // Scaffold count the keyboard height as part of the composer itself.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars),
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = chatColor(ThemeColorRole.ChatHeader) ?: MaterialTheme.colorScheme.surface,
                        titleContentColor = chatColor(ThemeColorRole.ChatHeaderText) ?: MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = chatColor(ThemeColorRole.ChatHeaderText) ?: MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = chatColor(ThemeColorRole.ChatHeaderText) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    title = {
                        Column(
                            modifier = Modifier.then(
                                if (uiState.isCurrentRoom) Modifier.clickable { roomMenuExpanded = true } else Modifier,
                            ).heightIn(min = 48.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(uiState.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(uiState.subtitle, style = MaterialTheme.typography.bodySmall,
                                color = chatColor(ThemeColorRole.ChatHeaderText) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            DropdownMenu(expanded = isVisible && roomMenuExpanded, onDismissRequest = { roomMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_room_members_settings)) },
                                    onClick = { roomMenuExpanded = false; onOpenRoomDetails() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.history_title)) },
                                    onClick = {
                                        roomMenuExpanded = false
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        onOpenHistory()
                                    },
                                )
                                onBrowseRooms?.let { browse ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_find_room)) },
                                        onClick = { roomMenuExpanded = false; browse() },
                                    )
                                }
                                onLeaveRoom?.let {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_leave_room), color = MaterialTheme.colorScheme.error) },
                                        onClick = { roomMenuExpanded = false; confirmLeave = true },
                                        enabled = realtimeInputEnabled && !leaveInProgress,
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        if (uiState.isCurrentRoom && onOpenSelf != null) {
                            IconButton(onClick = { selfMenuExpanded = true }) {
                                Icon(MobileBcIcons.Profile, stringResource(R.string.chat_my_profile))
                            }
                        } else {
                            IconButton(onClick = ::handleBack) {
                                Icon(MobileBcIcons.Back, stringResource(R.string.chat_back))
                            }
                        }
                    },
                    actions = {
                        if (uiState.isCurrentRoom) onOpenRoomMembers?.let { open ->
                            TextButton(onClick = open) {
                                Text(stringResource(R.string.chat_room_people_count, roomMemberCount))
                            }
                        }
                        if (uiState.isCurrentRoom) roomPortraitControl?.invoke()
                        if (!uiState.isCurrentRoom) {
                            TextButton(onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onOpenHistory()
                            }) { Text(stringResource(R.string.history_title)) }
                        }
                        uiState.directTarget?.let { memberNumber ->
                            TextButton(onClick = { onOpenCharacterProfile(memberNumber) }) {
                                Text(stringResource(R.string.chat_profile))
                            }
                        }
                        onOpenConversations?.let { open ->
                            IconButton(onClick = open) {
                                Icon(MobileBcIcons.Chats, stringResource(R.string.chat_conversations))
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 0.dp,
                    color = chatColor(ThemeColorRole.ChatComposer) ?: MaterialTheme.colorScheme.surface,
                    contentColor = chatColor(ThemeColorRole.ChatComposerText) ?: MaterialTheme.colorScheme.onSurface,
                ) {
                    ComposerBottomLayout(panelHasFocus = panelHasFocus && activeTool != null) {
                        if (!uiState.isCurrentRoom) {
                            onReturnToRoom?.let { returnToRoom ->
                                TextButton(onClick = returnToRoom, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.chat_return_to_room))
                                }
                            }
                        }
                        AnimatedContent(
                            targetState = ToolPanelContent(activeTool.takeUnless { toolExpanded || showToolDialog }, panelContent),
                            contentKey = { it.tool },
                            modifier = Modifier.fillMaxWidth().layoutId("tool"),
                            transitionSpec = {
                                val duration = when {
                                    targetState.tool == null -> 180
                                    initialState.tool == null -> 220
                                    else -> 120
                                }
                                (fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)))
                                    .using(SizeTransform { _, _ -> tween(duration) })
                            },
                            label = "chatTool",
                        ) { rendered ->
                            if (rendered.tool != null && !toolExpanded && !showToolDialog) {
                                InteractionBoundary(isVisible && activeTool == rendered.tool) {
                                    Column(Modifier.fillMaxWidth().heightIn(max = toolPanelHeight)
                                        .onGloballyPositioned { halfPanelTop = it.positionInWindow().y.toInt() }
                                        .onFocusChanged { panelHasFocus = it.hasFocus }.focusGroup()) {
                                        HorizontalDivider()
                                        toolStateHolder.SaveableStateProvider(rendered.tool.name) {
                                            CompositionLocalProvider(LocalInteractionPanelControls provides InteractionPanelControls(false) { toolExpanded = true }) {
                                                rendered.content?.invoke()
                                            }
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.fillMaxWidth().height(0.dp))
                            }
                        }
                        // AnimatedContent retains the outgoing reply until its height reaches zero.
                        AnimatedContent(
                            targetState = uiState.reply.takeIf { activeTool == null },
                            modifier = Modifier.fillMaxWidth(),
                            transitionSpec = {
                                (fadeIn(tween(180)) togetherWith fadeOut(tween(180)))
                                    .using(SizeTransform { _, _ -> tween(180) })
                            },
                            label = "reply",
                        ) { reply ->
                            if (reply != null) InteractionBoundary(isVisible && uiState.reply == reply) {
                                ChatReplyComposerBar(reply = reply, onCancel = onCancelReply)
                            } else Spacer(Modifier.height(0.dp))
                        }
                        if (activeTool == null) ChatComposerBar(
                            chatUiState = uiState,
                            customText = chatColor(ThemeColorRole.ChatComposerText),
                            onWhisperTargetChange = onWhisperTargetChange,
                            onReturnToPublic = onReturnToPublic,
                            state = if (selectedMessages.isEmpty()) {
                                uiState.composer
                            } else {
                                ChatComposerState.SelectingMessages(selectedMessages.size)
                            },
                            inputEnabled = realtimeInputEnabled,
                            inputFocusRequester = inputFocusRequester,
                            onTextChange = onTextChange,
                            onModeChange = onComposerModeChange,
                            onInputFocused = {
                                if (activeTool != null) onCloseToolPanel()
                            },
                            onSend = {
                                stickToBottom = true
                                if (totalListItems > 0) listState.requestScrollToItem(0)
                                onSend()
                            },
                            onOpenActionPicker = onOpenActionPicker.withKeyboardClosed(
                                focusManager = focusManager,
                                keyboardController = keyboardController,
                            ),
                            onOpenItemPicker = onOpenItemPicker.withKeyboardClosed(
                                focusManager = focusManager,
                                keyboardController = keyboardController,
                            ),
                            onOpenClothingPicker = onOpenClothingPicker.withKeyboardClosed(
                                focusManager = focusManager,
                                keyboardController = keyboardController,
                            ),
                            onCopySelected = {
                                clipboardManager.setText(
                                    AnnotatedString(
                                        selectedMessages.joinToString("\n") { it.copyText(it.displayConversationTitle(uiState)) },
                                    ),
                                )
                                selectedMessageIds = emptySet()
                            },
                            onReplySelected = replyTarget?.let { message ->
                                {
                                    onReply(message.chatUiKey)
                                    selectedMessageIds = emptySet()
                                    if (activeTool != null) onCloseToolPanel()
                                    requestInputFocus = true
                                }
                            },
                            onClearSelection = { selectedMessageIds = emptySet() },
                        )
                    }
                }
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                sessionStatusText
                    ?.takeIf { it.isNotBlank() }
                    ?.let { message ->
                        SessionStatusBanner(
                            text = message,
                            tone = sessionStatusTone,
                            showProgress = sessionStatusInProgress,
                        )
                    }
                if (uiState.isCurrentRoom) {
                    if (leaveInProgress) {
                        SessionStatusBanner(
                            text = stringResource(R.string.chat_leaving_room),
                            tone = SessionStatusTone.Neutral,
                            showProgress = true,
                        )
                    } else {
                        leaveErrorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                            SessionStatusBanner(text = message, tone = SessionStatusTone.Error)
                        }
                    }
                }
                if (uiState.persistenceFailure != null) {
                    SessionStatusBanner(
                        text = stringResource(R.string.timeline_storage_unavailable),
                        tone = SessionStatusTone.Error,
                    )
                }
                uiState.roomUnavailableMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    ChatWallpaper(chatBackground, Modifier.matchParentSize())
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().nestedScroll(userScrollConnection),
                        state = listState,
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        reverseLayout = true,
                    ) {
                        items(reversedVisibleMessages, key = { it.chatUiKey }) { message ->
                            SelectionContainer {
                                MessageBubble(
                                    message = message,
                                    customBackground = chatColor(ThemeColorRole.ChatMessage),
                                    customText = chatColor(ThemeColorRole.ChatMessageText),
                                    conversationTitle = message.displayConversationTitle(uiState),
                                    selected = message.chatUiKey in selectedMessageIds,
                                    onClick = if (selectedMessageIds.isNotEmpty()) {
                                        { toggleSelection(message.chatUiKey) }
                                    } else {
                                        null
                                    },
                                    onLongClick = { toggleSelection(message.chatUiKey) },
                                    onSenderClick = message.sender.memberNumber
                                        ?.takeIf { !message.sender.isSelf }
                                        ?.let { memberNumber ->
                                            {
                                                if (uiState.isCurrentRoom && realtimeInputEnabled && onInteractWithCharacter != null) {
                                                    focusManager.clearFocus(force = true)
                                                    keyboardController?.hide()
                                                    onInteractWithCharacter(memberNumber)
                                                } else onOpenCharacterProfile(memberNumber)
                                            }
                                        },
                                    replyContent = uiState.replyPreviews[message.chatUiKey]?.let { preview ->
                                        {
                                            ChatReplyQuote(
                                                preview = preview,
                                                onJump = if (preview.canJump && selectedMessageIds.isEmpty()) {
                                                    {
                                                        stickToBottom = false
                                                        historyExpanded = true
                                                        pendingReplyJumpId = preview.messageUiKey
                                                    }
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        if (hiddenHistoryCount == 0 && uiState.canLoadOlderMessages) {
                            item(key = "load-older") {
                                TextButton(
                                    onClick = {
                                        stickToBottom = false
                                        onLoadOlderMessages()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.chat_load_older_messages)) }
                            }
                        }
                        if (hiddenHistoryCount > 0) {
                            item(key = "history-collapsed") {
                                Text(
                                    text = stringResource(R.string.chat_show_older_messages, hiddenHistoryCount),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { historyExpanded = true }
                                        .padding(vertical = 12.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    if (!stickToBottom && totalListItems > 0) {
                        Button(
                            onClick = {
                                stickToBottom = true
                                listState.requestScrollToItem(0)
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(12.dp),
                        ) { Text(stringResource(R.string.chat_bottom)) }
                    }
                }
            }
        }
    }
    if (isVisible && confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.chat_leave_room)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.chat_leave_room_confirm, uiState.title))
                    if (!realtimeInputEnabled) {
                        sessionStatusText?.takeIf { it.isNotBlank() }?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmLeave = false; onLeaveRoom?.invoke() },
                    enabled = uiState.isCurrentRoom && realtimeInputEnabled && !leaveInProgress && onLeaveRoom != null,
                ) {
                    Text(stringResource(R.string.chat_leave_room))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

@Composable
private fun ChatComposerBar(
    chatUiState: ChatUiState,
    customText: Color?,
    onWhisperTargetChange: (Int) -> Unit,
    onReturnToPublic: () -> Unit,
    state: ChatComposerState,
    inputEnabled: Boolean,
    inputFocusRequester: FocusRequester,
    onTextChange: (String) -> Unit,
    onModeChange: (ChatComposerMode) -> Unit,
    onInputFocused: () -> Unit,
    onSend: () -> Unit,
    onOpenActionPicker: (() -> Unit)?,
    onOpenItemPicker: (() -> Unit)?,
    onOpenClothingPicker: (() -> Unit)?,
    onCopySelected: () -> Unit,
    onReplySelected: (() -> Unit)?,
    onClearSelection: () -> Unit,
) {
    when (state) {
        is ChatComposerState.SelectingMessages -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_selected_count, state.count),
                style = MaterialTheme.typography.titleSmall,
            )
            onReplySelected?.let { onReply ->
                TextButton(onClick = onReply, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.chat_reply))
                }
            }
            TextButton(onClick = onCopySelected, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.chat_copy))
            }
            TextButton(onClick = onClearSelection, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.chat_clear))
            }
        }

        is ChatComposerState.Disabled -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.reason.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ChatComposerState.Ready, is ChatComposerState.Sending -> {
            val editorState = when (state) {
                is ChatComposerState.Ready -> state
                is ChatComposerState.Sending -> ChatComposerState.Ready(
                    draft = state.draft,
                    mode = state.mode,
                    availableModes = listOf(ChatComposerModeOption(state.mode, enabled = false)),
                    canSend = false,
                )
                else -> error("Expected an editable composer")
            }
            ChatInputComposer(
                chatUiState = chatUiState,
                customText = customText,
                onWhisperTargetChange = onWhisperTargetChange,
                onReturnToPublic = onReturnToPublic,
                state = editorState,
                isSending = state is ChatComposerState.Sending,
                inputEnabled = inputEnabled,
                inputFocusRequester = inputFocusRequester,
                onTextChange = onTextChange,
                onModeChange = onModeChange,
                onInputFocused = onInputFocused,
                onSend = onSend,
                onOpenActionPicker = onOpenActionPicker,
                onOpenItemPicker = onOpenItemPicker,
                onOpenClothingPicker = onOpenClothingPicker,
            )
        }
    }
}

@Composable
private fun ChatInputComposer(
    chatUiState: ChatUiState,
    customText: Color?,
    onWhisperTargetChange: (Int) -> Unit,
    onReturnToPublic: () -> Unit,
    state: ChatComposerState.Ready,
    isSending: Boolean,
    inputEnabled: Boolean,
    inputFocusRequester: FocusRequester,
    onTextChange: (String) -> Unit,
    onModeChange: (ChatComposerMode) -> Unit,
    onInputFocused: () -> Unit,
    onSend: () -> Unit,
    onOpenActionPicker: (() -> Unit)?,
    onOpenItemPicker: (() -> Unit)?,
    onOpenClothingPicker: (() -> Unit)?,
) {
    var modeMenuExpanded by remember { mutableStateOf(false) }
    var commandHelpVisible by remember { mutableStateOf(false) }
    val interactive = LocalScreenInteractive.current
    LaunchedEffect(interactive) {
        if (!interactive) {
            modeMenuExpanded = false
            commandHelpVisible = false
        }
    }
    val controlsEnabled = inputEnabled && !isSending
    val hasRoomTools = chatUiState.isCurrentRoom &&
        (onOpenActionPicker != null || onOpenItemPicker != null)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val errorMessage = when (state.validationError) {
            ChatComposerValidationError.InvalidWhisperCommand -> stringResource(R.string.chat_invalid_whisper_command)
            ChatComposerValidationError.WhisperTargetUnavailable -> stringResource(R.string.chat_whisper_target_unavailable)
            ChatComposerValidationError.EmptyEmote -> stringResource(R.string.chat_empty_emote)
            ChatComposerValidationError.UnsupportedRoomCommand -> stringResource(R.string.chat_unsupported_room_command)
            ChatComposerValidationError.MessageTooLong -> stringResource(R.string.chat_message_too_long)
            ChatComposerValidationError.ReplyUnavailable -> stringResource(R.string.chat_reply_unavailable)
            ChatComposerValidationError.ReplyModeUnsupported -> stringResource(R.string.chat_reply_mode_unsupported)
            ChatComposerValidationError.SendFailed -> stringResource(R.string.chat_send_failed)
            ChatComposerValidationError.WhisperTargetReset -> stringResource(R.string.chat_whisper_target_reset)
            null -> state.roomChatRejection?.reason?.let { reason ->
                stringResource(
                    when (reason) {
                        BcRoomChatRejectionReason.SelfStateUnavailable -> R.string.chat_self_state_unavailable
                        BcRoomChatRejectionReason.AccountStateUnavailable -> R.string.chat_account_state_unavailable
                        BcRoomChatRejectionReason.OwnerRulesUnavailable -> R.string.chat_owner_rules_unavailable
                        BcRoomChatRejectionReason.TalkBlockedByOwner -> R.string.chat_talk_blocked_by_owner
                        BcRoomChatRejectionReason.TalkMutedByForbiddenWord -> R.string.chat_talk_muted_by_forbidden_word
                        BcRoomChatRejectionReason.ForbiddenWord -> R.string.chat_forbidden_word
                        BcRoomChatRejectionReason.EmoteBlockedByOwner -> R.string.chat_emote_blocked_by_owner
                        BcRoomChatRejectionReason.WhisperBlockedByOwner -> R.string.chat_whisper_blocked_by_owner
                        BcRoomChatRejectionReason.SpeechStateUnavailable -> R.string.chat_speech_state_unavailable
                    },
                )
            } ?: state.errorMessage
        }
        errorMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!state.modeAvailable) {
            Text(
                text = stringResource(R.string.chat_mode_unavailable, state.mode.label()),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TextButton(onClick = { modeMenuExpanded = true }, enabled = controlsEnabled) {
                    Text(
                        text = if (state.mode == ChatComposerMode.Whisper && chatUiState.whisperTargetName != null) {
                            stringResource(R.string.chat_whisper_to, chatUiState.whisperTargetName)
                        } else state.mode.label(),
                        color = if (state.mode == ChatComposerMode.Whisper) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = modeMenuExpanded && controlsEnabled, onDismissRequest = { modeMenuExpanded = false }) {
                    state.availableModes.filterNot { chatUiState.isCurrentRoom && it.mode == ChatComposerMode.Whisper }
                        .forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.mode.label()) },
                                enabled = option.enabled,
                                onClick = { modeMenuExpanded = false; onModeChange(option.mode) },
                            )
                        }
                    if (chatUiState.whisperTargets.isNotEmpty()) HorizontalDivider()
                    chatUiState.whisperTargets.forEach { target ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_whisper_to, target.displayName)) },
                            onClick = { modeMenuExpanded = false; onWhisperTargetChange(target.memberNumber) },
                        )
                    }
                    if (chatUiState.isCurrentRoom) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_commands)) },
                            onClick = { modeMenuExpanded = false; commandHelpVisible = true },
                        )
                    }
                }
            }
            if (chatUiState.isCurrentRoom && state.mode == ChatComposerMode.Whisper) {
                TextButton(onClick = onReturnToPublic, enabled = controlsEnabled) {
                    Text(stringResource(R.string.chat_return_public))
                }
            }
            if (hasRoomTools) {
                TextButton(
                    onClick = { (onOpenActionPicker ?: onOpenItemPicker)?.invoke() },
                    enabled = controlsEnabled,
                ) { Text(stringResource(R.string.chat_interact)) }
            }
            androidx.compose.runtime.key(chatUiState.conversationId, chatUiState.whisperTarget, state.mode) {
                ChatImageUploadButton(enabled = controlsEnabled && interactive) { url ->
                    onTextChange(listOf(state.draft.trimEnd(), url).filter { it.isNotEmpty() }.joinToString(" "))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = onTextChange,
                colors = if (customText == null) OutlinedTextFieldDefaults.colors() else OutlinedTextFieldDefaults.colors(
                    focusedTextColor = customText,
                    unfocusedTextColor = customText,
                    focusedPlaceholderColor = customText.copy(alpha = 0.7f),
                    unfocusedPlaceholderColor = customText.copy(alpha = 0.7f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(inputFocusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) onInputFocused()
                    },
                minLines = 1,
                maxLines = 3,
                enabled = inputEnabled,
                isError = errorMessage != null,
                placeholder = { Text(state.mode.placeholder()) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputEnabled && state.canSend) onSend()
                    },
                ),
            )
            Button(
                onClick = onSend,
                enabled = controlsEnabled && state.canSend,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = if (state.mode == ChatComposerMode.Whisper) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) else ButtonDefaults.buttonColors(),
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(if (state.mode == ChatComposerMode.Whisper) R.string.chat_whisper_mode else R.string.chat_send))
                }
            }
        }

    }
    if (interactive && commandHelpVisible) {
        AlertDialog(
            onDismissRequest = { commandHelpVisible = false },
            title = { Text(stringResource(R.string.chat_commands)) },
            text = {
                Text(
                    stringResource(R.string.chat_commands_help),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { commandHelpVisible = false }) {
                    Text(stringResource(R.string.chat_close_help))
                }
            },
        )
    }
}

@Composable
private fun ChatReplyComposerBar(
    reply: ChatReplySelection,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_replying_to, reply.author),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = reply.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.chat_cancel_reply))
        }
    }
}

@Composable
private fun ChatReplyQuote(
    preview: ChatReplyPreview,
    onJump: (() -> Unit)?,
) {
    val jumpLabel = stringResource(R.string.chat_jump_to_original)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (onJump != null) {
                    Modifier.clickable(onClickLabel = jumpLabel, onClick = onJump)
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            preview.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = preview.text ?: stringResource(R.string.chat_reply_original_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChatComposerMode.label(): String = stringResource(
    when (this) {
        ChatComposerMode.RoomMessage -> R.string.chat_room_mode
        ChatComposerMode.RoomEmote -> R.string.chat_emote_mode
        ChatComposerMode.CustomAction -> R.string.chat_custom_action
        ChatComposerMode.DirectMessage -> R.string.chat_message_mode
        ChatComposerMode.Whisper -> R.string.chat_whisper_mode
    },
)

@Composable
private fun ChatComposerMode.placeholder(): String = stringResource(
    when (this) {
        ChatComposerMode.RoomEmote -> R.string.chat_emote_placeholder
        ChatComposerMode.CustomAction -> R.string.chat_action_text
        else -> R.string.chat_message_placeholder
    },
)

@Composable
private fun ChatComposerDisabledReason.label(): String = stringResource(
    when (this) {
        ChatComposerDisabledReason.NotInRoom -> R.string.chat_disabled_not_in_room
        ChatComposerDisabledReason.Reconnecting -> R.string.chat_disabled_reconnecting
        ChatComposerDisabledReason.ConnectionStale -> R.string.chat_disabled_connection_stale
        ChatComposerDisabledReason.VerifyingConnection -> R.string.chat_disabled_verifying
        ChatComposerDisabledReason.DirectMessageUnavailable -> R.string.chat_disabled_direct
    },
)

private fun (() -> Unit)?.withKeyboardClosed(
    focusManager: FocusManager,
    keyboardController: SoftwareKeyboardController?,
): (() -> Unit)? {
    val openTool = this ?: return null
    return {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        openTool()
    }
}

internal fun chatToolPanelHeight(viewportHeight: Dp): Dp =
    (viewportHeight * 0.45f).coerceIn(120.dp, 360.dp)

private fun BcMessage.copyText(conversationTitle: String?): String {
    val body = when (kind) {
        BcMessageKind.Action,
        BcMessageKind.Activity,
        -> "($text)"

        BcMessageKind.Emote -> "*${bcEmoteDisplayText(text, sender.displayName)}*"
        else -> text
    }
    return when {
        transport == dev.vivian.mobilebc.core.model.BcMessageTransport.RoomWhisper && sender.isSelf ->
            "Whisper to ${conversationTitle?.takeIf(String::isNotBlank) ?: "#${targetMemberNumber ?: "unknown"}"}: $body"
        transport == dev.vivian.mobilebc.core.model.BcMessageTransport.RoomWhisper ->
            "Whisper from ${sender.displayName}: $body"
        kind == BcMessageKind.Action || kind == BcMessageKind.Activity || kind == BcMessageKind.Emote -> body
        kind == BcMessageKind.System || kind == BcMessageKind.Error -> body
        else -> "${sender.displayName.takeIf { it.isNotBlank() } ?: "System"}: $body"
    }
}

private fun BcMessage.displayConversationTitle(uiState: ChatUiState): String =
    if (kind == BcMessageKind.Whisper && uiState.directTarget == null && sender.isSelf) {
        uiState.whisperTargets.firstOrNull { it.memberNumber == targetMemberNumber }?.displayName
            ?: "#${targetMemberNumber ?: "?"}"
    } else uiState.title
