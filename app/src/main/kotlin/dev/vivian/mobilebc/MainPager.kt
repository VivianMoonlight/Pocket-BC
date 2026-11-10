package dev.vivian.mobilebc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vivian.mobilebc.feature.roomdetails.RoomMembersList
import dev.vivian.mobilebc.core.ui.InteractionBoundary
import dev.vivian.mobilebc.core.ui.LocalBottomNavigationHeight
import dev.vivian.mobilebc.core.ui.MobileBcIcons
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal val LocalOpenRoomMembers = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun MainPager(
    viewModel: MobileBcAppViewModel,
    uiState: MobileBcAppUiState,
    selectedPage: Int,
    isCurrent: Boolean,
    reopenMembers: MutableState<Boolean>,
    membersListState: LazyListState,
    content: @Composable (AppScreen, Boolean, Boolean) -> Unit,
) {
    val pager = rememberPagerState(initialPage = selectedPage) { 3 }
    var navigationHeight by remember { mutableStateOf(0) }
    val navigationPadding = with(LocalDensity.current) { navigationHeight.toDp() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val latestUiState by rememberUpdatedState(uiState)
    val latestIsCurrent by rememberUpdatedState(isCurrent)
    val latestSelectedPage by rememberUpdatedState(selectedPage)
    val selection = remember { MainPageSelection(selectedPage) }
    // A new request disables gesture commits immediately, before its effect runs.
    val request = remember(selectedPage) { selection.request(selectedPage) }
    var drawerJob by remember { mutableStateOf<Job?>(null) }
    var drawerActionPending by remember { mutableStateOf(false) }
    val roomState by viewModel.roomDetailsViewModel.uiState.collectAsStateWithLifecycle()
    val latestRoomState by rememberUpdatedState(roomState)
    val hasRoom = uiState.currentRoomConversationId != null
    val drawerAvailable = isCurrent && hasRoom && selectedPage == 0 && selection.committedPage == 0
    val drawerBlocking = drawer.isOpen || drawer.targetValue == DrawerValue.Open ||
        drawer.isAnimationRunning || drawerJob?.isActive == true || drawerActionPending
    fun animateDrawer(action: suspend () -> Unit) {
        drawerJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) { action() }
        drawerJob = job
        job.invokeOnCompletion { if (drawerJob === job) drawerJob = null }
        job.start()
    }
    fun openDrawer() {
        if (!drawerAvailable || drawerBlocking || drawerJob?.isActive == true || drawerActionPending) return
        animateDrawer { drawer.open() }
    }
    fun closeDrawer() {
        animateDrawer { drawer.close() }
    }
    fun selectMember(member: Int, interact: Boolean) {
        if (drawerActionPending || !isCurrent || !drawer.isOpen || drawer.targetValue != DrawerValue.Open) return
        val owner = uiState.activeAccountName to uiState.currentRoomConversationId
        val generation = uiState.accountGeneration to uiState.roomPresenceEpoch
        drawerActionPending = true
        animateDrawer {
            try {
                drawer.close()
                if (latestIsCurrent && latestSelectedPage == 0 &&
                    owner == (latestUiState.activeAccountName to latestUiState.currentRoomConversationId) &&
                    generation == (latestUiState.accountGeneration to latestUiState.roomPresenceEpoch) &&
                    latestRoomState.characters.any { it.memberNumber == member }
                ) {
                    if (interact) viewModel.interactWithCharacter(member) else {
                        reopenMembers.value = true
                        viewModel.openCharacterBio(member)
                    }
                }
            } finally { drawerActionPending = false }
        }
    }
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        if (pager.settledPage != selectedPage || pager.isScrollInProgress) pager.animateScrollToPage(selectedPage)
        if (selection.complete(request, pager.settledPage) && latestIsCurrent) {
            viewModel.onMainPageSettled(selectedPage)
        }
    }
    LaunchedEffect(pager, selectedPage, isCurrent) {
        snapshotFlow { Triple(pager.settledPage, pager.isScrollInProgress, selection.pendingPage) }
            .distinctUntilChanged().collect { (settled, scrolling, programmatic) ->
                if (isCurrent && programmatic == null && selection.settleGesture(settled, scrolling)) {
                    viewModel.selectMainPage(settled)
                    viewModel.onMainPageSettled(settled)
                }
            }
    }
    LaunchedEffect(uiState.currentRoomConversationId, uiState.activeAccountName, selectedPage, isCurrent) {
        drawerJob?.cancel()
        // Navigation resets the drawer without a close animation that disables the tabs.
        drawer.snapTo(DrawerValue.Closed)
    }
    LaunchedEffect(isCurrent, selectedPage) {
        if (isCurrent && selectedPage == 0 && reopenMembers.value && hasRoom) {
            reopenMembers.value = false
            drawer.open()
        }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = isCurrent && drawer.isOpen && !drawerActionPending,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 360.dp).fillMaxWidth(0.85f)) {
                key(uiState.activeAccountName, uiState.currentRoomConversationId) {
                    RoomMembersList(
                        uiState = roomState,
                        listState = membersListState,
                        interactionEnabled = isCurrent && !drawerActionPending && uiState.sessionAvailability == SessionAvailability.Online,
                        onInteract = { member -> selectMember(member, interact = true) },
                        onOpenBio = { member -> selectMember(member, interact = false) },
                    )
                }
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            HorizontalPager(
                state = pager,
                beyondViewportPageCount = 1,
                userScrollEnabled = isCurrent && !drawerBlocking,
                modifier = Modifier.weight(1f)
                    .consumeWindowInsets(PaddingValues(bottom = navigationPadding)),
                key = { mainPageScreen(it, uiState.currentRoomConversationId).toRoute() },
            ) { page ->
                val interactive = isCurrent && selectedPage == page && pager.settledPage == page &&
                    !pager.isScrollInProgress && selection.pendingPage == null && !drawerBlocking
                InteractionBoundary(interactive || pager.isScrollInProgress) {
                    CompositionLocalProvider(
                        LocalBottomNavigationHeight provides navigationHeight,
                        LocalOpenRoomMembers provides if (drawerAvailable) ::openDrawer else null,
                    ) {
                    content(
                        mainPageScreen(page, uiState.currentRoomConversationId),
                        isCurrent && selection.committedPage == page,
                        interactive,
                    )
                    }
                }
            }
            NavigationBar(
                modifier = Modifier.onSizeChanged { navigationHeight = it.height },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                val icons = listOf(MobileBcIcons.Rooms, MobileBcIcons.Chats, MobileBcIcons.Menu)
                listOf(R.string.tab_rooms, R.string.tab_chats, R.string.tab_settings).forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = pager.currentPage == index,
                        enabled = isCurrent && !drawerBlocking,
                        onClick = { viewModel.selectMainPage(index) },
                        icon = { Icon(icons[index], null) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }
    }
    BackHandler(isCurrent && pager.isScrollInProgress && !drawerBlocking) {
        if (selection.pendingPage != null) viewModel.selectMainPage(selection.committedPage)
        else scope.launch { pager.animateScrollToPage(selection.committedPage) }
    }
    BackHandler(isCurrent && drawerBlocking) { closeDrawer() }
}
