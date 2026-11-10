package dev.vivian.mobilebc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import dev.vivian.mobilebc.core.model.AppAppearance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vivian.mobilebc.core.ui.SessionStatusTone
import dev.vivian.mobilebc.core.ui.MobileBcIcons
import dev.vivian.mobilebc.core.ui.PageTransition
import dev.vivian.mobilebc.core.ui.InteractionBoundary
import dev.vivian.mobilebc.core.ui.LocalScreenInteractive
import dev.vivian.mobilebc.feature.chat.ChatComposerTool
import dev.vivian.mobilebc.core.model.AppLanguage
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.feature.auth.LoginScreen
import dev.vivian.mobilebc.feature.chat.ChatScreen
import dev.vivian.mobilebc.feature.characterprofile.CharacterProfileScreen
import dev.vivian.mobilebc.portrait.BcCharacterPortrait
import dev.vivian.mobilebc.portrait.PrewarmRoomPortraits
import dev.vivian.mobilebc.portrait.InteractivePortrait
import dev.vivian.mobilebc.portrait.PortraitInteractionMode
import dev.vivian.mobilebc.portrait.PortraitVisibilityIcon
import dev.vivian.mobilebc.feature.conversations.ConversationsScreen
import dev.vivian.mobilebc.feature.profile.ProfileScreen
import dev.vivian.mobilebc.feature.profile.ContactsScreen
import dev.vivian.mobilebc.feature.roomdetails.RoomPersonalSheets
import dev.vivian.mobilebc.feature.roomdetails.ChatRoomActionPickerPanel
import dev.vivian.mobilebc.feature.roomdetails.ChatRoomItemPickerPanel
import dev.vivian.mobilebc.feature.roomdetails.RoomDetailsInteractionMode
import dev.vivian.mobilebc.feature.roomdetails.RoomDetailsScreen
import dev.vivian.mobilebc.feature.rooms.RoomsScreen
import dev.vivian.mobilebc.feature.rooms.R as RoomsR

/** Renders app navigation and connects feature screens to their screen-level ViewModels. */
@Composable
internal fun MobileBcApp(
    viewModel: MobileBcAppViewModel,
    appearance: AppAppearance,
    onAppearanceChange: (AppAppearance) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
) {
    val appUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val portraitCandidates by viewModel.portraitCandidates.collectAsStateWithLifecycle()
    val screenStateHolder = rememberSaveableStateHolder()
    PrewarmRoomPortraits(portraitCandidates)

    if (!appUiState.sessionReady) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.starting_bc_session_service))
            }
        }
        return
    }

    BackHandler(
        enabled = viewModel.canNavigateBack,
    ) {
        viewModel.onBack()
    }

    fun containerKey(screen: AppScreen): String =
        if (screen.mainPage(appUiState.currentRoomConversationId) != null) "main-pager" else screen.toRoute()
    key(appUiState.activeAccountName, appUiState.currentRoomConversationId, appUiState.accountGeneration,
        appUiState.roomPresenceEpoch, appUiState.sessionAvailability == SessionAvailability.SwitchingAccount) {
        val reopenMembers = remember { mutableStateOf(false) }
        val membersListState = rememberLazyListState()
        PageTransition(
            target = appUiState.screen,
            backward = appUiState.navigationMotion == NavigationMotion.Backward,
            contentKey = ::containerKey,
        ) { target ->
            val current = containerKey(target) == containerKey(appUiState.screen) &&
                appUiState.sessionAvailability != SessionAvailability.SwitchingAccount
            val page = target.mainPage(appUiState.currentRoomConversationId)
            InteractionBoundary(current) {
                if (page != null) {
                    MainPager(viewModel, appUiState, page, current, reopenMembers, membersListState) { screen, ownsPage, interactive ->
                        screenStateHolder.SaveableStateProvider("${appUiState.activeAccountName}/${screen.toRoute()}") {
                            CompositionLocalProvider(LocalScreenInteractive provides interactive) {
                                AppScreenContent(viewModel, appUiState, screen, appearance, onAppearanceChange, appLanguage, onAppLanguageChange, interactive, true, ownsPage)
                            }
                        }
                    }
                } else {
                    screenStateHolder.SaveableStateProvider("${appUiState.activeAccountName}/${target.toRoute()}") {
                        CompositionLocalProvider(LocalScreenInteractive provides current) {
                            AppScreenContent(viewModel, appUiState, target, appearance, onAppearanceChange, appLanguage, onAppLanguageChange, current, false, current)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppScreenContent(
    viewModel: MobileBcAppViewModel,
    appUiState: MobileBcAppUiState,
    current: AppScreen,
    appearance: AppAppearance,
    onAppearanceChange: (AppAppearance) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    isVisible: Boolean,
    inPager: Boolean,
    ownsPage: Boolean,
) {
    val context = LocalContext.current
    val profileStateHolder = rememberSaveableStateHolder()
    val sessionStatusBanner = appUiState.mainSessionStatusBanner()
    val realtimeInputEnabled = appUiState.realtimeInputEnabled()

    when (current) {
        AppScreen.Login -> {
            val uiState by viewModel.loginViewModel.uiState.collectAsStateWithLifecycle()
            LoginScreen(
                uiState = uiState,
                onAccountMenuExpandedChange = viewModel.loginViewModel::onAccountMenuExpandedChange,
                onSelectNewAccount = viewModel.loginViewModel::selectNewAccount,
                onSelectSavedAccount = viewModel.loginViewModel::selectSavedAccount,
                onAccountNameChange = viewModel.loginViewModel::onAccountNameChange,
                onPasswordChange = viewModel.loginViewModel::onPasswordChange,
                onServerUrlChange = viewModel.loginViewModel::onServerUrlChange,
                onBcVersionChange = viewModel.loginViewModel::onBcVersionChange,
                onSaveCredentialsChange = viewModel.loginViewModel::onSaveCredentialsChange,
                onDeleteSavedAccount = viewModel.loginViewModel::deleteCurrentSavedAccount,
                onLogin = viewModel.loginViewModel::login,
                onBack = if (viewModel.canNavigateBackFromLogin) viewModel::onBack else null,
            )
        }

        is AppScreen.Chat -> {
            val chatViewModel = viewModel.chatViewModel(current.conversationId)
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(chatViewModel, lifecycleOwner, isVisible) {
                val visibilityOwner = Any()
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> if (isVisible) chatViewModel.activate(visibilityOwner)
                        Lifecycle.Event.ON_STOP -> chatViewModel.deactivate(visibilityOwner)
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                if (isVisible && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    chatViewModel.activate(visibilityOwner)
                }
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    chatViewModel.deactivate(visibilityOwner)
                }
            }
            DisposableEffect(chatViewModel) {
                val release = viewModel.retainChatViewModel(current.conversationId, chatViewModel)
                onDispose(release)
            }
            val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
            val historyState by chatViewModel.history.uiState.collectAsStateWithLifecycle()
            if (historyState.visible && isVisible && ownsPage) {
                dev.vivian.mobilebc.feature.chat.ChatHistoryScreen(
                    state = historyState,
                    title = uiState.title,
                    onClose = chatViewModel.history::close,
                    onSearch = chatViewModel.history::search,
                    onDate = chatViewModel.history::selectDate,
                    onContext = chatViewModel.history::showContext,
                    onBackToResults = chatViewModel.history::backToResults,
                    onLoadMore = chatViewModel.history::loadMore,
                )
            }
            val roomDetailsUiState by viewModel.roomDetailsViewModel.uiState.collectAsStateWithLifecycle()
            val roomsUiState by viewModel.roomsViewModel.uiState.collectAsStateWithLifecycle()
            val interactionMode = roomDetailsUiState.interactionMode
            val matchesCurrentRoom = uiState.isCurrentRoom &&
                roomsUiState.currentRoomName?.let(::bcRoomConversationId) == current.conversationId
            ChatScreen(
                uiState = uiState,
                chatBackground = if (uiState.directTarget == null) appearance.roomBackground else appearance.directBackground,
                customColors = appearance.customColors,
                onBack = viewModel::onBack,
                onTextChange = chatViewModel::onTextChange,
                onComposerModeChange = chatViewModel::onComposerModeChange,
                onSend = chatViewModel::send,
                onReply = chatViewModel::onReply,
                onCancelReply = chatViewModel::onCancelReply,
                onLoadOlderMessages = chatViewModel::loadOlderMessages,
                onOpenHistory = chatViewModel.history::open,
                onOpenRoomDetails = viewModel::openRoomDetails,
                onOpenRoomMembers = LocalOpenRoomMembers.current,
                roomMemberCount = roomDetailsUiState.characters.size,
                selfCharacterName = roomDetailsUiState.characters.firstOrNull { it.isSelf }?.displayName,
                onOpenCharacterProfile = viewModel::openCharacterBio,
                onOpenConversations = viewModel::openConversations,
                onOpenSelf = viewModel::openProfile,
                onOpenMyProfile = roomDetailsUiState.characters.firstOrNull { it.isSelf }?.let { self ->
                    { viewModel.openCharacterBio(self.memberNumber) }
                },
                onOpenContacts = viewModel::openContacts,
                onOpenSelfState = if (realtimeInputEnabled) {
                    {
                        viewModel.roomDetailsViewModel.openSelfState()
                    }
                } else null,
                onOpenWardrobe = if (realtimeInputEnabled && roomDetailsUiState.canOpenWardrobe) {
                    {
                        viewModel.roomDetailsViewModel.openWardrobe()
                    }
                } else null,
                onReturnToRoom = if (!uiState.isCurrentRoom && appUiState.currentRoomConversationId != null) {
                    viewModel::openHome
                } else null,
                onInteractWithCharacter = { memberNumber ->
                    viewModel.roomDetailsViewModel.openActionPicker(memberNumber)
                },
                onWhisperTargetChange = chatViewModel::onWhisperTargetChange,
                onReturnToPublic = chatViewModel::onReturnToPublic,
                onBrowseRooms = viewModel::openRooms,
                onLeaveRoom = viewModel.roomsViewModel::leaveRoom,
                leaveInProgress = matchesCurrentRoom &&
                    roomsUiState.leavingRoomName != null &&
                    roomsUiState.leavingRoomName == roomsUiState.currentRoomName,
                leaveErrorMessage = if (matchesCurrentRoom && roomsUiState.errorMessageRes == RoomsR.string.rooms_leave_failed) {
                    stringResource(RoomsR.string.rooms_leave_failed)
                } else null,
                onOpenActionPicker = {
                    if (interactionMode == RoomDetailsInteractionMode.ActionPicker) {
                        viewModel.roomDetailsViewModel.closeInteract()
                    } else {
                        viewModel.roomDetailsViewModel.openActionPicker()
                    }
                },
                onOpenItemPicker = {
                    if (interactionMode == RoomDetailsInteractionMode.ItemPicker) {
                        viewModel.roomDetailsViewModel.closeInteract()
                    } else {
                        viewModel.roomDetailsViewModel.openItemPicker()
                    }
                },
                onOpenClothingPicker = {
                    if (interactionMode == RoomDetailsInteractionMode.ClothingPicker) {
                        viewModel.roomDetailsViewModel.closeInteract()
                    } else {
                        viewModel.roomDetailsViewModel.openClothingPicker()
                    }
                },
                onCloseToolPanel = {
                    viewModel.roomDetailsViewModel.closeInteract()
                },
                onToolBack = { viewModel.roomDetailsViewModel.handlePickerBack() },
                activeTool = if (uiState.isCurrentRoom && realtimeInputEnabled) when (interactionMode) {
                    RoomDetailsInteractionMode.ActionPicker -> ChatComposerTool.Action
                    RoomDetailsInteractionMode.ItemPicker -> ChatComposerTool.Item
                    RoomDetailsInteractionMode.ClothingPicker -> ChatComposerTool.Clothing
                    else -> null
                } else null,
                roomPortraitControl = if (uiState.isCurrentRoom) { { PortraitVisibilityIcon() } } else null,
                actionPickerContent = if (uiState.isCurrentRoom && realtimeInputEnabled && interactionMode == RoomDetailsInteractionMode.ActionPicker) {
                    {
                        ChatRoomActionPickerPanel(
                            uiState = roomDetailsUiState,
                            portraitContent = { portrait, groups, select -> InteractivePortrait(portrait, groups, PortraitInteractionMode.Action, select) },
                            onSelectCharacter = viewModel.roomDetailsViewModel::selectActionPickerTarget,
                            onResetCharacter = viewModel.roomDetailsViewModel::resetActionPickerTarget,
                            onSelectGroup = viewModel.roomDetailsViewModel::selectInteractionGroup,
                            onToggleUnavailable = viewModel.roomDetailsViewModel::toggleActionPickerUnavailable,
                            onSendActivity = { group, activityName, activityAssetGroup, activityAssetName ->
                                viewModel.roomDetailsViewModel.sendActivity(
                                    group = group,
                                    activityName = activityName,
                                    activityAssetGroup = activityAssetGroup,
                                    activityAssetName = activityAssetName,
                                )
                            },
                            onClose = {
                                viewModel.roomDetailsViewModel.closeInteract()
                            },
                            onOpenItems = viewModel.roomDetailsViewModel::openItemPicker,
                            onOpenClothing = viewModel.roomDetailsViewModel::openClothingPicker,
                            onWhisper = { memberNumber ->
                                chatViewModel.onWhisperTargetChange(memberNumber)
                                viewModel.roomDetailsViewModel.closeInteract()
                            },
                            onOpenBio = viewModel::openCharacterBio,
                        )
                    }
                } else {
                    null
                },
                itemPickerContent = if (uiState.isCurrentRoom && realtimeInputEnabled && interactionMode == RoomDetailsInteractionMode.ItemPicker) {
                    {
                        ChatRoomItemPickerPanel(
                            uiState = roomDetailsUiState,
                            workflowKind = BcAppearanceGroupKind.Item,
                            portraitContent = { BcCharacterPortrait(it) },
                            interactionPortraitContent = { portrait, groups, select -> InteractivePortrait(portrait, groups, PortraitInteractionMode.Item, select) },
                            onEditText = viewModel.roomDetailsViewModel::editCurrentItemText,
                            onUnlockWithCode = viewModel.roomDetailsViewModel::unlockCurrentItemWithCode,
                            onConfigureLock = viewModel.roomDetailsViewModel::configureCurrentItemLock,
                            onChangeTimer = viewModel.roomDetailsViewModel::changeCurrentItemTimer,
                            onSelectCharacter = viewModel.roomDetailsViewModel::selectItemPickerTarget,
                            onResetCharacter = viewModel.roomDetailsViewModel::resetItemPickerTarget,
                            onSelectGroup = viewModel.roomDetailsViewModel::selectInteractionGroup,
                            onResetGroup = viewModel.roomDetailsViewModel::resetItemPickerGroup,
                            onSelectOperation = viewModel.roomDetailsViewModel::selectItemPickerOperation,
                            onSelectItem = viewModel.roomDetailsViewModel::selectItemPickerItem,
                            onBackToCatalog = viewModel.roomDetailsViewModel::returnToItemPickerCatalog,
                            onUpdateQuery = viewModel.roomDetailsViewModel::updateItemPickerQuery,
                            onToggleUnavailable = viewModel.roomDetailsViewModel::toggleItemPickerUnavailable,
                            onSendEntry = viewModel.roomDetailsViewModel::sendItemPickerEntry,
                            onLoadMore = viewModel.roomDetailsViewModel::loadNextItemPickerPage,
                            onClose = {
                                viewModel.roomDetailsViewModel.closeInteract()
                            },
                            onOpenActions = viewModel.roomDetailsViewModel::openActionPicker,
                            onOpenBio = viewModel::openCharacterBio,
                        )
                    }
                } else {
                    null
                },
                clothingPickerContent = if (uiState.isCurrentRoom && realtimeInputEnabled && interactionMode == RoomDetailsInteractionMode.ClothingPicker) {
                    {
                        ChatRoomItemPickerPanel(
                            uiState = roomDetailsUiState,
                            workflowKind = BcAppearanceGroupKind.Clothing,
                            portraitContent = { BcCharacterPortrait(it) },
                            onEditText = viewModel.roomDetailsViewModel::editCurrentItemText,
                            onUnlockWithCode = viewModel.roomDetailsViewModel::unlockCurrentItemWithCode,
                            onConfigureLock = viewModel.roomDetailsViewModel::configureCurrentItemLock,
                            onChangeTimer = viewModel.roomDetailsViewModel::changeCurrentItemTimer,
                            onSelectCharacter = viewModel.roomDetailsViewModel::selectClothingPickerTarget,
                            onResetCharacter = viewModel.roomDetailsViewModel::resetClothingPickerTarget,
                            onSelectGroup = viewModel.roomDetailsViewModel::selectInteractionGroup,
                            onResetGroup = viewModel.roomDetailsViewModel::resetItemPickerGroup,
                            onSelectOperation = viewModel.roomDetailsViewModel::selectItemPickerOperation,
                            onSelectItem = viewModel.roomDetailsViewModel::selectItemPickerItem,
                            onBackToCatalog = viewModel.roomDetailsViewModel::returnToItemPickerCatalog,
                            onUpdateQuery = viewModel.roomDetailsViewModel::updateItemPickerQuery,
                            onToggleUnavailable = viewModel.roomDetailsViewModel::toggleItemPickerUnavailable,
                            onSendEntry = viewModel.roomDetailsViewModel::sendItemPickerEntry,
                            onLoadMore = viewModel.roomDetailsViewModel::loadNextItemPickerPage,
                            onClose = {
                                viewModel.roomDetailsViewModel.closeInteract()
                            },
                            onOpenActions = viewModel.roomDetailsViewModel::openActionPicker,
                            onOpenBio = viewModel::openCharacterBio,
                        )
                    }
                } else {
                    null
                },
                sessionStatusText = sessionStatusBanner?.text,
                sessionStatusTone = sessionStatusBanner?.tone ?: SessionStatusTone.Neutral,
                sessionStatusInProgress = sessionStatusBanner?.showProgress ?: false,
                inputEnabled = realtimeInputEnabled,
                isVisible = isVisible,
                ownsPage = ownsPage,
            )
            if (isVisible && ownsPage && uiState.isCurrentRoom) {
                RoomPersonalSheets(
                    uiState = roomDetailsUiState,
                    onCloseSelfState = viewModel.roomDetailsViewModel::closeSelfState,
                    onChangeSelfState = viewModel.roomDetailsViewModel::changeSelfState,
                    onClearSelfStateResult = viewModel.roomDetailsViewModel::clearSelfStateResult,
                    onCloseWardrobe = viewModel.roomDetailsViewModel::closeWardrobe,
                    onSelectWardrobeSlot = viewModel.roomDetailsViewModel::selectWardrobeSlot,
                    onBeginWardrobeSave = viewModel.roomDetailsViewModel::beginWardrobeSave,
                    onBeginWardrobeRename = viewModel.roomDetailsViewModel::beginWardrobeRename,
                    onChangeWardrobeName = viewModel.roomDetailsViewModel::changeWardrobeName,
                    onCancelWardrobeEdit = viewModel.roomDetailsViewModel::cancelWardrobeEdit,
                    onConfirmWardrobeSave = viewModel.roomDetailsViewModel::confirmWardrobeSave,
                    onConfirmWardrobeRename = viewModel.roomDetailsViewModel::confirmWardrobeRename,
                    onPreviewWardrobeLoad = viewModel.roomDetailsViewModel::previewWardrobeLoad,
                    onCancelWardrobeLoad = viewModel.roomDetailsViewModel::cancelWardrobeLoad,
                    onConfirmWardrobeLoad = viewModel.roomDetailsViewModel::confirmWardrobeLoad,
                    onClearWardrobeResult = viewModel.roomDetailsViewModel::clearWardrobeResult,
                )
            }
        }

        AppScreen.Contacts -> {
            val uiState by viewModel.profileViewModel.uiState.collectAsStateWithLifecycle()
            ContactsScreen(
                relationships = uiState.relationships,
                onOpenCharacterProfile = viewModel::openCharacterBio,
                onBack = viewModel::onBack,
                selfMemberNumber = uiState.memberNumber,
            )
        }

        AppScreen.RoomDetails -> {
            val uiState by viewModel.roomDetailsViewModel.uiState.collectAsStateWithLifecycle()
            RoomDetailsScreen(
                uiState = uiState,
                onBack = viewModel::onBack,
                onOpenBio = viewModel::openCharacterBio,
                onOpenItemPicker = viewModel::openCharacterItemPicker,
                onOpenInteract = viewModel::interactWithCharacter,
                onCloseInteract = viewModel.roomDetailsViewModel::closeInteract,
                onSelectInteractionGroup = viewModel.roomDetailsViewModel::selectInteractionGroup,
                onSendActivity = { group, activityName, activityAssetGroup, activityAssetName ->
                    viewModel.roomDetailsViewModel.sendActivity(
                        group = group,
                        activityName = activityName,
                        activityAssetGroup = activityAssetGroup,
                        activityAssetName = activityAssetName,
                    )
                },
                onWearItem = { group, itemName, propertyJson, difficulty ->
                    viewModel.roomDetailsViewModel.wearItem(group, itemName, propertyJson, difficulty)
                },
                onRemoveItem = viewModel.roomDetailsViewModel::removeItem,
                onClearInteractionMessage = viewModel.roomDetailsViewModel::clearInteractionMessage,
                onOpenRoomSettings = viewModel.roomDetailsViewModel::openRoomSettings,
                onCloseRoomSettings = viewModel.roomDetailsViewModel::closeRoomSettings,
                onChangeRoomSettingsDraft = viewModel.roomDetailsViewModel::changeRoomSettingsDraft,
                onSubmitRoomSettings = viewModel.roomDetailsViewModel::submitRoomSettings,
                onReloadRoomSettings = viewModel.roomDetailsViewModel::reloadRoomSettings,
                onOpenMemberManagement = viewModel.roomDetailsViewModel::openMemberManagement,
                onCloseMemberManagement = viewModel.roomDetailsViewModel::closeMemberManagement,
                onSelectMemberAdminAction = viewModel.roomDetailsViewModel::selectMemberAdminAction,
                onCancelMemberAdminAction = viewModel.roomDetailsViewModel::cancelMemberAdminAction,
                onConfirmMemberAdminAction = viewModel.roomDetailsViewModel::confirmMemberAdminAction,
                onClearMemberAdminResult = viewModel.roomDetailsViewModel::clearMemberAdminResult,
            )
        }

        is AppScreen.CharacterBio -> {
            val profileViewModel = viewModel.characterProfileViewModel(current.memberNumber)
            DisposableEffect(profileViewModel) {
                val release = viewModel.retainCharacterProfileViewModel(current.memberNumber, profileViewModel)
                onDispose(release)
            }
            val uiState by profileViewModel.uiState.collectAsStateWithLifecycle()
            CharacterProfileScreen(
                uiState = uiState,
                onBack = viewModel::onBack,
                onRelationshipListChange = profileViewModel::updateRelationshipList,
                onRefreshRelationshipActions = profileViewModel::queryRelationshipCapabilities,
                onRelationshipAction = profileViewModel::submitRelationshipAction,
                onClearRelationshipMessage = profileViewModel::clearRelationshipMessage,
                portraitContent = uiState.portrait?.let { portrait ->
                    { BcCharacterPortrait(portrait) }
                },
            )
        }

        is AppScreen.Main -> {
            Scaffold(
                topBar = {
                    if (!inPager) MainNavigationBar(
                        current = current.tab,
                        canGoBack = viewModel.canNavigateBack,
                        hasRoom = appUiState.currentRoomConversationId != null,
                        onBack = viewModel::onBack,
                        onHome = viewModel::openHome,
                        onNavigate = viewModel::selectTab,
                    )
                },
            ) { padding ->
                when (current.tab) {
                    MainTab.Conversations -> {
                        val uiState by viewModel.conversationsViewModel.uiState.collectAsStateWithLifecycle()
                        ConversationsScreen(
                            uiState = uiState,
                            onOpenConversation = viewModel::openConversation,
                            onOpenContacts = viewModel::openContacts,
                            onSaveScrollPosition = viewModel.conversationsViewModel::saveScrollPosition,
                            onAcceptFriendRequest = viewModel.conversationsViewModel::acceptFriendRequest,
                            onDismissFriendRequest = viewModel.conversationsViewModel::dismissFriendRequest,
                            onClearFriendRequestMessage = viewModel.conversationsViewModel::clearFriendRequestMessage,
                            onOpenRooms = { viewModel.selectTab(MainTab.Rooms) },
                            modifier = Modifier.padding(padding),
                            sessionStatusText = sessionStatusBanner?.text,
                            sessionStatusTone = sessionStatusBanner?.tone ?: SessionStatusTone.Neutral,
                            sessionStatusInProgress = sessionStatusBanner?.showProgress ?: false,
                        )
                    }

                    MainTab.Rooms -> {
                        val uiState by viewModel.roomsViewModel.uiState.collectAsStateWithLifecycle()
                        RoomsScreen(
                            uiState = uiState,
                            onQueryChange = viewModel.roomsViewModel::onQueryChange,
                            onSpaceChange = viewModel.roomsViewModel::onSpaceChange,
                            onRoomTypeChange = viewModel.roomsViewModel::onRoomTypeChange,
                            onIncludeFullRoomsChange = viewModel.roomsViewModel::onIncludeFullRoomsChange,
                            onIncludeLockedRoomsChange = viewModel.roomsViewModel::onIncludeLockedRoomsChange,
                            onSearchDescriptionChange = viewModel.roomsViewModel::onSearchDescriptionChange,
                            onSearchRooms = viewModel.roomsViewModel::searchRooms,
                            onJoinRoom = viewModel.roomsViewModel::joinRoom,
                            onLeaveRoom = viewModel.roomsViewModel::leaveRoom,
                            onOpenCreateRoom = viewModel.roomsViewModel::openCreateRoom,
                            onCloseRoomForm = viewModel.roomsViewModel::closeRoomForm,
                            onRoomFormDraftChange = viewModel.roomsViewModel::changeRoomFormDraft,
                            onSubmitRoomForm = viewModel.roomsViewModel::submitRoomForm,
                            modifier = Modifier.padding(padding),
                        )
                    }

                    MainTab.Profile -> {
                        profileStateHolder.SaveableStateProvider("profile") {
                            val profileViewModel = viewModel.profileViewModel
                            val lifecycleOwner = LocalLifecycleOwner.current
                            DisposableEffect(profileViewModel, lifecycleOwner, isVisible) {
                                fun updateCollection() {
                                    profileViewModel.setPacketLogCollectionEnabled(
                                        isVisible && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                                    )
                                }
                                val observer = LifecycleEventObserver { _, _ -> updateCollection() }
                                lifecycleOwner.lifecycle.addObserver(observer)
                                updateCollection()
                                onDispose {
                                    lifecycleOwner.lifecycle.removeObserver(observer)
                                    profileViewModel.setPacketLogCollectionEnabled(false)
                                }
                            }
                            val uiState by profileViewModel.uiState.collectAsStateWithLifecycle()
                            ProfileScreen(
                                uiState = uiState,
                                isVisible = isVisible,
                                appearance = appearance,
                                onAppearanceChange = onAppearanceChange,
                                appLanguage = appLanguage,
                                onAppLanguageChange = onAppLanguageChange,
                                onAutoJoinRoomAfterReconnectChange = profileViewModel::onAutoJoinRoomAfterReconnectChange,
                                onExportPacketLogs = { context.sharePacketLogs(profileViewModel.exportPacketLogs()) },
                                onClearPacketLogs = profileViewModel::clearPacketLogs,
                                onSuppressRoomMessagesWhenForegroundChange = profileViewModel::onSuppressRoomMessagesWhenForegroundChange,
                                onSuppressDirectMessagesWhenForegroundChange = profileViewModel::onSuppressDirectMessagesWhenForegroundChange,
                                onSuppressSystemAlertsWhenForegroundChange = profileViewModel::onSuppressSystemAlertsWhenForegroundChange,
                                onNotificationRoutingChange = profileViewModel::updateNotificationSettings,
                                onDeobfuscationEnabledChange = profileViewModel::onDeobfuscationEnabledChange,
                                onRefreshNotificationDiagnostics = profileViewModel::refreshNotificationDiagnostics,
                                onOpenNotificationSettings = { context.openAppNotificationSettings() },
                                onOpenNotificationChannelSettings = context::openNotificationChannelSettings,
                                onSwitchAccount = profileViewModel::switchAccount,
                                onLogout = profileViewModel::logout,
                                onOpenLogin = viewModel::openLogin,
                                modifier = Modifier.padding(padding),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainNavigationBar(
    current: MainTab,
    canGoBack: Boolean,
    hasRoom: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNavigate: (MainTab) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) {
            IconButton(onClick = onBack) {
                Icon(MobileBcIcons.Back, stringResource(R.string.navigation_back))
            }
        }
        if (hasRoom) {
            TextButton(onClick = onHome) { Text(stringResource(R.string.navigation_current_room)) }
        }
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Text(stringResource(R.string.navigation_go_to))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                MainTab.entries.filter { it != current }.forEach { tab ->
                    DropdownMenuItem(
                        text = { Text(stringResource(tab.labelResource())) },
                        onClick = { menuExpanded = false; onNavigate(tab) },
                    )
                }
            }
        }
    }
}

private fun MainTab.labelResource(): Int = when (this) {
    MainTab.Conversations -> R.string.tab_chats
    MainTab.Rooms -> R.string.tab_rooms
    MainTab.Profile -> R.string.tab_settings
}

private data class MainSessionStatusBanner(
    val text: String,
    val tone: SessionStatusTone = SessionStatusTone.Neutral,
    val showProgress: Boolean = false,
)

@Composable
private fun MobileBcAppUiState.mainSessionStatusBanner(): MainSessionStatusBanner? {
    if (screen is AppScreen.Login || shell != AppShell.Main) return null
    val statusText = sessionStatusText.takeIf { it.isNotBlank() } ?: return null
    val account = activeAccountName?.takeIf { it.isNotBlank() }
    return when (sessionAvailability) {
        SessionAvailability.Online -> null
        SessionAvailability.SwitchingAccount -> MainSessionStatusBanner(
            text = switchingToAccountName?.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.service_switching_to, it) }
                ?: stringResource(R.string.service_switching_account),
            tone = SessionStatusTone.Warning,
            showProgress = true,
        )
        SessionAvailability.Reconnecting -> MainSessionStatusBanner(
            text = account?.let { stringResource(R.string.service_reconnecting_account, it) }
                ?: stringResource(R.string.service_reconnecting_bc),
            tone = SessionStatusTone.Warning,
            showProgress = true,
        )
        SessionAvailability.Starting -> MainSessionStatusBanner(
            text = stringResource(R.string.starting_bc_session_service),
            tone = SessionStatusTone.Neutral,
            showProgress = true,
        )
        SessionAvailability.Stopped -> MainSessionStatusBanner(
            text = stringResource(
                R.string.session_stopped_history_available,
                account?.let { stringResource(R.string.service_account_stopped, it) }
                    ?: stringResource(R.string.service_bc_stopped),
            ),
            tone = SessionStatusTone.Warning,
        )
        SessionAvailability.Failed -> MainSessionStatusBanner(
            text = switchErrorMessage?.takeIf { it.isNotBlank() } ?: statusText,
            tone = SessionStatusTone.Error,
        )
        SessionAvailability.NoAccount -> MainSessionStatusBanner(
            text = stringResource(R.string.session_no_saved_account),
            tone = SessionStatusTone.Error,
        )
    }
}

private fun MobileBcAppUiState.realtimeInputEnabled(): Boolean =
    sessionAvailability == SessionAvailability.Online
