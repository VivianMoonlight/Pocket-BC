package dev.vivian.mobilebc.feature.roomdetails

import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import dev.vivian.mobilebc.feature.characterprofile.CharacterPortraitState
import dev.vivian.mobilebc.domain.bcitemproperty.validBcItemText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.ui.LocalInteractionPanelControls
import dev.vivian.mobilebc.core.ui.MobileBcIcons
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.feature.rooms.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private enum class ActionDockView { Body, List, Favorites }

/** An in-room control, with progressive expansion instead of a navigation stack. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FlatRoomActionDock(
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
    portraitContent: (@Composable (CharacterPortraitState?, List<InteractionGroupUiState>, (String) -> Unit) -> Unit)? = null,
) {
    val sheet = uiState.interactionSheet
    val target = sheet?.target
    val context = LocalContext.current.applicationContext
    val activityGroups = remember(sheet?.groups) {
        sheet?.groups.orEmpty().filter { it.hasActivities }
    }
    val actionLayoutGroups = remember(sheet?.groups) {
        sheet?.groups.orEmpty().filter { it.kind == BcAppearanceGroupKind.Item && (it.hasItems || it.hasCurrentItem) }
            .ifEmpty { activityGroups }
    }
    val favorites = remember(context) { ActivityFavorites(context) }
    LaunchedEffect(favorites) { favorites.load() }
    // The dock moves between two composition trees when expanded, so use stable save keys.
    var actionView by rememberSaveable(key = "roomActionDockView") { mutableStateOf(ActionDockView.Body) }
    var choosingBodyPart by rememberSaveable(target?.memberNumber, key = "roomActionDockBody") { mutableStateOf(false) }
    var query by rememberSaveable(target?.memberNumber, key = "roomActionDockQuery") { mutableStateOf("") }
    var pendingActivity by remember(target?.memberNumber) { mutableStateOf<InteractionActivityUiState?>(null) }
    val activities = remember(sheet?.activities, sheet?.groups, query, favorites.keys, actionView) {
        visibleDockActivities(
            sheet?.activities.orEmpty(), sheet?.groups.orEmpty(), query, favorites.keys,
            actionView == ActionDockView.Favorites,
        )
    }
    val listState = rememberLazyListState()
    LaunchedEffect(target?.memberNumber, sheet?.selectedGroup?.name, actionView, choosingBodyPart) {
        listState.scrollToItem(0)
    }
    // Permission updates may remove a row while its confirmation is visible.
    LaunchedEffect(sheet?.activities) {
        pendingActivity = pendingActivity?.let { pending ->
            sheet?.activities?.firstOrNull { it.group == pending.group && it.name == pending.name && it.enabled }
        }
    }
    fun send(activity: InteractionActivityUiState) {
        onSendActivity(activity.group, activity.name, activity.activityAssetGroup, activity.activityAssetName)
    }
    fun choose(activity: InteractionActivityUiState) {
        if (activity.effects.isEmpty()) send(activity) else pendingActivity = activity
    }
    val selectedBody = sheet?.selectedGroup?.takeIf { actionView == ActionDockView.Body && !choosingBodyPart }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            DockCharacterStrip(uiState.characters, target?.memberNumber, sheet?.busy == true, onSelectCharacter)
            DockHeader(target, onClose, onOpenBio, busy = sheet?.busy == true,
                onSwitchMode = onOpenItems?.let { open -> { target?.let { open(it.memberNumber) } } },
                itemMode = false, bodyPart = selectedBody?.displayName,
                onChangeBodyPart = selectedBody?.let { { actionView = ActionDockView.Body; choosingBodyPart = true } })
            if (target != null && sheet != null && selectedBody == null) {
                val searchInteraction = remember { MutableInteractionSource() }
                val searchFocused by searchInteraction.collectIsFocusedAsState()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        ActionDockView.Body to R.string.dock_layout,
                        ActionDockView.List to R.string.dock_list,
                    ).forEach { (view, label) ->
                        FilterChip(
                            selected = actionView == view,
                            onClick = {
                                actionView = view
                                query = ""
                                choosingBodyPart = view == ActionDockView.Body
                                if (view == ActionDockView.List) onSelectGroup("")
                            },
                            enabled = !sheet.busy,
                            label = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1) },
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.height(36.dp),
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            if (it.isNotBlank()) {
                                actionView = ActionDockView.List
                                choosingBodyPart = false
                                if (sheet.selectedGroup != null) onSelectGroup("")
                            }
                        },
                        singleLine = true,
                        enabled = !sheet.busy,
                        textStyle = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        interactionSource = searchInteraction,
                        modifier = Modifier.weight(1f).height(36.dp)
                            .border(
                                width = if (searchFocused) 2.dp else 1.dp,
                                color = if (!sheet.busy) {
                                    if (searchFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                } else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 10.dp),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(stringResource(R.string.dock_search_global),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1)
                                }
                                innerTextField()
                            }
                        },
                    )
                    val favoriteLabel = stringResource(R.string.dock_favorites)
                    FilterChip(
                        selected = actionView == ActionDockView.Favorites,
                        onClick = {
                            actionView = ActionDockView.Favorites
                            query = ""
                            onSelectGroup("")
                        },
                        enabled = !sheet.busy,
                        label = { Text("⭐") },
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(36.dp).semantics { contentDescription = favoriteLabel },
                    )
                }
            }
            SwipeCharacterPanel(uiState.characters, target?.memberNumber, sheet?.busy == false, onSelectCharacter) { motion ->
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().then(motion),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    uiState.interactionNotice?.let { item(key = "notice") { DockNotice(it) } }
                    if (target == null || sheet == null) {
                        item { Text(stringResource(R.string.room_details_choose_role), style = MaterialTheme.typography.bodySmall) }
                    } else {
                        item(key = "feedback") { InteractionFeedback(sheet) }
                        if (actionView == ActionDockView.Body && (sheet.selectedGroup == null || choosingBodyPart)) {
                            item(key = "body-layout") {
                                val select: (String) -> Unit = { name ->
                                    if (!sheet.busy) {
                                        choosingBodyPart = false
                                        onSelectGroup(name)
                                    }
                                }
                                if (portraitContent != null) {
                                    portraitContent(sheet.portrait, actionLayoutGroups, select)
                                } else {
                                    BodyPartArrangementGrid(groups = actionLayoutGroups,
                                        mode = if (actionLayoutGroups.any { it.hasItems || it.hasCurrentItem }) BodyPartArrangementMode.Item else BodyPartArrangementMode.Action,
                                        enabled = !sheet.busy, selectedGroup = sheet.selectedGroup?.name, onSelectGroup = select)
                                }
                            }
                        } else {
                            if (actionView == ActionDockView.List && query.isBlank() && selectedBody == null) item(key = "groups") {
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    FilterChip(
                                        selected = sheet.selectedGroup == null,
                                        onClick = { onSelectCharacter(target.memberNumber) },
                                        label = { Text(stringResource(R.string.dock_all_body_parts)) },
                                        enabled = !sheet.busy,
                                    )
                                    activityGroups.forEach { group ->
                                        FilterChip(
                                            selected = sheet.selectedGroup?.name == group.name,
                                            onClick = { onSelectGroup(group.name) },
                                            label = { Text(group.displayName) },
                                            enabled = !sheet.busy,
                                        )
                                    }
                                }
                            }
                            if (activities.isEmpty()) {
                                item { Text(stringResource(if (actionView == ActionDockView.Favorites) R.string.dock_no_favorites else R.string.dock_no_matching_actions), style = MaterialTheme.typography.bodySmall) }
                            }
                            items(activities.chunked(2), key = { row -> "activity:${row.first().group}:${row.first().name}" }) { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { activity ->
                                        val groupName = sheet.groups.firstOrNull { it.name == activity.group }?.displayName ?: activity.group
                                        ActivityActionRow(
                                            activity = activity.copy(displayName = "${activity.displayName} · $groupName"),
                                            busy = sheet.busy,
                                            compact = true,
                                            modifier = Modifier.weight(1f),
                                            favorite = activity.favoriteKey() in favorites.keys,
                                            onToggleFavorite = if (favorites.loaded) ({ favorites.toggle(activity) }) else null,
                                            onClick = { choose(activity) },
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            item(key = "unavailable") {
                                FilterChip(
                                    selected = sheet.showUnavailableActivities,
                                    onClick = onToggleUnavailable,
                                    label = { Text(stringResource(R.string.room_details_show_unavailable_actions)) },
                                    enabled = !sheet.busy,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingActivity?.let { activity ->
        if (target != null && sheet != null) {
            ActivityConfirmationDialog(
                activity = activity,
                target = target,
                enabled = !sheet.busy && activity.enabled,
                onDismiss = { pendingActivity = null },
                onConfirm = { pendingActivity = null; send(activity) },
            )
        }
    }
}

internal fun adjacentDockCharacter(characters: List<BcCharacterSnapshot>, current: Int?, direction: Int): Int? {
    if (direction != -1 && direction != 1) return null
    val index = characters.indexOfFirst { it.memberNumber == current }
    return if (index < 0) null else characters.getOrNull(index + direction)?.memberNumber
}

@Composable
private fun SwipeCharacterPanel(
    characters: List<BcCharacterSnapshot>,
    current: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val latestCharacters = rememberUpdatedState(characters)
    val latestCurrent = rememberUpdatedState(current)
    val latestOnSelect = rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var preview by remember { mutableStateOf<BcCharacterSnapshot?>(null) }
    var settling by remember { mutableStateOf(false) }
    var panelWidth by remember { mutableIntStateOf(0) }
    var arrived by remember { mutableStateOf(false) }
    var contentAlpha by remember { mutableFloatStateOf(1f) }

    fun settle(destination: BcCharacterSnapshot?, direction: Int) {
        scope.launch {
            settling = true
            val animation = Animatable(offset)
            val targetOffset = if (destination == null) 0f else -direction * panelWidth.toFloat()
            animation.animateTo(targetOffset, tween(200)) { offset = value }
            if (destination != null) {
                latestOnSelect.value(destination.memberNumber)
                val changed = withTimeoutOrNull(3000) {
                    snapshotFlow { latestCurrent.value }.first { it == destination.memberNumber }
                } != null
                if (!changed) {
                    animation.animateTo(0f, tween(200)) { offset = value }
                } else {
                    arrived = true
                    contentAlpha = 0f
                    offset = 0f
                    Animatable(0f).animateTo(1f, tween(150)) { contentAlpha = value }
                }
            }
            offset = 0f
            preview = null
            arrived = false
            contentAlpha = 1f
            settling = false
        }
    }

    val gesture = Modifier.pointerInput(enabled, panelWidth, settling) {
        if (!enabled || settling || panelWidth == 0 || latestCurrent.value == null) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var distance = 0f
            fun updateDistance(delta: Float) {
                distance = (distance + delta).coerceIn(-panelWidth.toFloat(), panelWidth.toFloat())
                val direction = if (distance < 0) 1 else -1
                val next = adjacentDockCharacter(latestCharacters.value, latestCurrent.value, direction)
                preview = latestCharacters.value.firstOrNull { it.memberNumber == next }
                offset = if (preview == null) distance * 0.2f else distance
            }
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                updateDistance(overSlop)
                change.consume()
            } ?: return@awaitEachGesture
            val completed = horizontalDrag(drag.id) { change ->
                updateDistance(change.position.x - change.previousPosition.x)
                change.consume()
            }
            val direction = if (distance < 0) 1 else -1
            settle(preview.takeIf { completed && abs(distance) >= threshold }, direction)
        }
    }
    Box(Modifier.fillMaxWidth().onSizeChanged { panelWidth = it.width }.clipToBounds().then(gesture)) {
        preview?.let { character ->
            Box(Modifier.matchParentSize().graphicsLayer {
                translationX = if (arrived) 0f else offset + if (offset < 0) panelWidth else -panelWidth
            }.background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(character.displayName, style = MaterialTheme.typography.titleMedium)
                    CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                }
            }
        }
        content(Modifier.graphicsLayer { translationX = offset; alpha = contentAlpha })
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FlatRoomItemDock(
    uiState: RoomDetailsUiState,
    workflowKind: BcAppearanceGroupKind,
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
    portraitContent: (@Composable (CharacterPortraitState) -> Unit)? = null,
    interactionPortraitContent: (@Composable (CharacterPortraitState?, List<InteractionGroupUiState>, (String) -> Unit) -> Unit)? = null,
    onEditText: ((BcCharacterSnapshot, String, Map<String, String>) -> Unit)? = null,
    onUnlockWithCode: ((BcCharacterSnapshot, String, String) -> Unit)? = null,
    onConfigureLock: ((BcCharacterSnapshot, String, BcLockConfiguration) -> Unit)? = null,
    onChangeTimer: ((BcCharacterSnapshot, String, dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange) -> Unit)? = null,
) {
    val sheet = uiState.interactionSheet
    val target = sheet?.target
    val group = sheet?.selectedGroup
    val picker = sheet?.itemPicker
    var preview by remember(sheet?.portrait) { mutableStateOf<CharacterPortraitState?>(null) }
    var editingText by remember(target, group?.name) { mutableStateOf(false) }
    var textDraft by remember(target, group?.name) { mutableStateOf(picker?.currentItemTextValues.orEmpty()) }
    var unlockingWithCode by remember(target, group?.name) { mutableStateOf(false) }
    var configuringLock by remember(target, group?.name) { mutableStateOf(false) }
    var editingTimer by remember(target, group?.name) { mutableStateOf(false) }
    val workflowGroups = remember(sheet?.groups, workflowKind) {
        sheet?.groups.orEmpty().filter { it.kind == workflowKind }
    }
    val workflowRows = remember(workflowGroups) { workflowGroups.chunked(3) }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(target?.memberNumber, group?.name, picker?.selectedItemName, picker?.configuringCurrentItem) {
        listState.scrollToItem(0)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            DockCharacterStrip(uiState.characters, target?.memberNumber, sheet?.busy == true, onSelectCharacter)
            DockHeader(target, onClose, onOpenBio, busy = sheet?.busy == true,
                onSwitchMode = onOpenActions?.let { open -> { target?.let { open(it.memberNumber) } } },
                itemMode = true, bodyPart = group?.displayName,
                onChangeBodyPart = group?.let { onResetGroup })
            if (group != null && (picker?.selectedItemName != null || picker?.configuringCurrentItem == true)) {
                TextButton(onClick = onBackToCatalog, enabled = sheet?.busy == false) {
                    Text(stringResource(R.string.dock_item_catalog))
                }
            }
            SwipeCharacterPanel(uiState.characters, target?.memberNumber, sheet?.busy == false, onSelectCharacter) { motion ->
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().then(motion),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    uiState.interactionNotice?.let { item(key = "notice") { DockNotice(it) } }
                    if (target == null || sheet == null) {
                        item { Text(stringResource(R.string.room_details_choose_role), style = MaterialTheme.typography.bodySmall) }
                    } else {
                        item(key = "feedback") { InteractionFeedback(sheet) }
                        if (group == null) {
                            item {
                                Text(stringResource(R.string.room_details_choose_body_part_for, target.displayName), style = MaterialTheme.typography.bodySmall)
                            }
                            if (workflowKind == BcAppearanceGroupKind.Item) {
                                item(key = "item-body-layout") {
                                    val select: (String) -> Unit = { if (!sheet.busy) onSelectGroup(it) }
                                    if (interactionPortraitContent != null) {
                                        interactionPortraitContent(sheet.portrait, workflowGroups, select)
                                    } else {
                                        BodyPartArrangementGrid(groups = workflowGroups, mode = BodyPartArrangementMode.Item,
                                            enabled = !sheet.busy, onSelectGroup = select)
                                    }
                                }
                            } else items(workflowRows) { groups ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    groups.forEach { available ->
                                        OutlinedButton(
                                            onClick = { onSelectGroup(available.name) },
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                            enabled = !sheet.busy,
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                        ) { Text(available.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                            }
                        } else if (picker != null) {
                            itemPickerEntries(
                                target = target,
                                group = group,
                                picker = picker,
                                busy = sheet.busy,
                                onSelectOperation = onSelectOperation,
                                onSelectItem = onSelectItem,
                                onUpdateQuery = onUpdateQuery,
                                onToggleUnavailable = onToggleUnavailable,
                                onSendEntry = onSendEntry,
                                onLoadMore = onLoadMore,
                                onClearFocus = { focusManager.clearFocus() },
                                onPreview = if (portraitContent != null && sheet.portrait != null) ({ entry ->
                                    itemPreviewCharacter(target, entry)?.let { character -> preview = sheet.portrait.copy(character = character) }
                                }) else null,
                                onEditText = if (onEditText != null) ({ textDraft = picker.currentItemTextValues; editingText = true }) else null,
                                onUnlockInput = if (onUnlockWithCode != null) ({ unlockingWithCode = true }) else null,
                                onConfigureLock = if (onConfigureLock != null) ({ configuringLock = true }) else null,
                                onChangeTimer = if (onChangeTimer != null) ({ editingTimer = true }) else null,
                            )
                        } else {
                            item { Text(stringResource(R.string.room_details_item_picker_not_ready), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
    if ((unlockingWithCode || configuringLock) && picker != null && target != null && group != null) {
        RoomItemLockDialog(picker, busy = sheet?.busy != false, configure = configuringLock,
            onDismiss = { unlockingWithCode = false; configuringLock = false },
            onUnlock = { onUnlockWithCode?.invoke(target, group.name, it) },
            onConfigure = { onConfigureLock?.invoke(target, group.name, it) })
    }
    if (editingTimer && picker?.currentItemTimer != null && target != null && group != null && onChangeTimer != null) {
        RoomItemTimerDialog(picker.currentItemTimer, sheet?.busy != false, onDismiss = { editingTimer = false },
            onChange = { onChangeTimer(target, group.name, it) })
    }
    if (editingText && picker != null && onEditText != null && target != null && group != null) {
        val valid = picker.currentItemTextFields.isNotEmpty() && picker.currentItemTextFields.all { (key, limit) -> validBcItemText(textDraft[key].orEmpty(), limit) }
        AlertDialog(onDismissRequest = { editingText = false },
            title = { Text(stringResource(R.string.room_details_edit_item_text)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    picker.currentItemTextFields.entries.forEachIndexed { index, (key, limit) ->
                        val value = textDraft[key].orEmpty()
                        OutlinedTextField(value = value, onValueChange = { textDraft = textDraft + (key to it) }, singleLine = true,
                            label = { Text(stringResource(R.string.room_details_item_text_field, index + 1)) },
                            supportingText = { Text("${value.length}/$limit") }, isError = !validBcItemText(value, limit))
                    }
                    if (!valid) Text(stringResource(R.string.room_permission_item_text_invalid))
                }
            },
            confirmButton = { TextButton(onClick = { onEditText(target, group.name, textDraft); editingText = false },
                enabled = valid && sheet?.busy == false && picker.currentItemTextEditEntry?.enabled == true) { Text(stringResource(R.string.room_details_apply)) } },
            dismissButton = { TextButton(onClick = { editingText = false }) { Text(stringResource(R.string.room_details_item_preview_close)) } })
    }
    preview?.let { state ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.room_details_item_preview_title)) },
            text = {
                Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.room_details_item_preview_draft), style = MaterialTheme.typography.bodySmall)
                    portraitContent?.invoke(state)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.room_details_item_preview_close)) } },
        )
    }
}

@Composable
private fun DockCharacterStrip(
    characters: List<BcCharacterSnapshot>,
    current: Int?,
    busy: Boolean,
    onSelect: (Int) -> Unit,
) {
    if (characters.isEmpty()) {
        Text(stringResource(R.string.room_details_no_online_characters), modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall)
        return
    }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val chipConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val widths = subcompose("measure") {
            characters.forEach { character ->
                DockCharacterChip(character, current, busy, onSelect)
            }
        }.map { it.measure(chipConstraints).width }
        val requiredWidth = widths.sumOf { it.toLong() } +
            16.dp.roundToPx() + 6.dp.roundToPx().toLong() * (characters.size - 1)
        val fits = requiredWidth <= constraints.maxWidth
        val strip = subcompose("strip") {
            if (fits) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    characters.forEach { character -> DockCharacterChip(character, current, busy, onSelect) }
                }
            } else {
                ScrollingDockCharacterStrip(characters, current, busy, onSelect)
            }
        }.single().measure(constraints)
        layout(strip.width, strip.height) { strip.placeRelative(0, 0) }
    }
}

@Composable
private fun ScrollingDockCharacterStrip(
    characters: List<BcCharacterSnapshot>,
    current: Int?,
    busy: Boolean,
    onSelect: (Int) -> Unit,
) {
    val count = characters.size
    val initial = if (count == 1) 0 else (Int.MAX_VALUE / 2).let { it - it % count }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    LaunchedEffect(current) {
        val selected = characters.indexOfFirst { it.memberNumber == current }
        if (selected >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index % count == selected }) {
            val base = list.firstVisibleItemIndex.toLong()
            val nearest = listOf(base - count, base, base + count)
                .map { it - Math.floorMod(it, count.toLong()) + selected }
                .filter { it >= 0 && it < if (count == 1) 1 else Int.MAX_VALUE.toLong() }
                .minByOrNull { kotlin.math.abs(it - base) }
            nearest?.let { list.animateScrollToItem(it.toInt()) }
        }
    }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth(),
        userScrollEnabled = !busy && count > 1,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(if (count == 1) 1 else Int.MAX_VALUE, key = { it }) { index ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index % count == 0) {
                    VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.width(6.dp))
                }
                DockCharacterChip(characters[index % count], current, busy, onSelect)
            }
        }
    }
}

@Composable
private fun DockCharacterChip(character: BcCharacterSnapshot, current: Int?, busy: Boolean, onSelect: (Int) -> Unit) {
    FilterChip(
        selected = character.memberNumber == current,
        enabled = !busy,
        onClick = { if (character.memberNumber != current) onSelect(character.memberNumber) },
        label = { Text(character.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.widthIn(max = 180.dp).height(36.dp),
    )
}

@Composable
private fun DockHeader(
    target: BcCharacterSnapshot?,
    onClose: () -> Unit,
    onOpenBio: ((Int) -> Unit)? = null,
    busy: Boolean = false,
    onSwitchMode: (() -> Unit)? = null,
    itemMode: Boolean = false,
    bodyPart: String? = null,
    onChangeBodyPart: (() -> Unit)? = null,
) {
    val controls = LocalInteractionPanelControls.current
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            val backLabel = stringResource(R.string.dock_change_body_part)
            TextButton(onClick = { if (bodyPart != null) onChangeBodyPart?.invoke() else target?.let { onOpenBio?.invoke(it.memberNumber) } },
                enabled = !busy && target != null && if (bodyPart != null) onChangeBodyPart != null else onOpenBio != null,
                modifier = Modifier.weight(1f).height(40.dp).then(
                    if (bodyPart != null) Modifier.semantics { contentDescription = "$bodyPart, $backLabel" } else Modifier
                ), contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (bodyPart != null) "‹$bodyPart" else stringResource(R.string.dock_profile),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { onSwitchMode?.invoke() }, enabled = !busy && target != null && onSwitchMode != null,
                modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(if (itemMode) R.string.room_details_actions else R.string.room_details_items), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            controls?.let {
                IconButton(onClick = it.toggle, enabled = !busy, modifier = Modifier.size(40.dp)) {
                    Icon(if (it.expanded) MobileBcIcons.ExpandDown else MobileBcIcons.ExpandUp,
                        contentDescription = stringResource(if (it.expanded) R.string.dock_panel_collapse else R.string.dock_expand))
                }
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.height(40.dp)) {
                Text(stringResource(R.string.dock_speak), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DockNotice(message: InteractionUserMessage) {
    Text(
        message.text(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Recent order comes from the ViewModel; never show denied or server-unchecked actions as shortcuts. */
internal fun quickDockActivities(activities: List<InteractionActivityUiState>): List<InteractionActivityUiState> =
    activities.filter { it.enabled && it.permissionMessage == null && it.warningMessage == null }
        .distinctBy { it.name }
        .take(3)

internal fun filterDockActivities(
    activities: List<InteractionActivityUiState>,
    groups: List<InteractionGroupUiState>,
    query: String,
): List<InteractionActivityUiState> {
    val normalized = query.trim()
    if (normalized.isBlank()) return activities
    val groupNames = groups.associate { it.name to it.displayName }
    return activities.filter { activity ->
        activity.name.contains(normalized, ignoreCase = true) ||
            activity.displayName.contains(normalized, ignoreCase = true) ||
            activity.group.contains(normalized, ignoreCase = true) ||
            groupNames[activity.group].orEmpty().contains(normalized, ignoreCase = true)
    }
}
