package dev.vivian.mobilebc.feature.roomdetails

import androidx.compose.runtime.Composable
import dev.vivian.mobilebc.core.model.BcSelfStateChange
import dev.vivian.mobilebc.core.ui.AnimatedModalSheet

@Composable
fun RoomPersonalSheets(
    uiState: RoomDetailsUiState,
    onCloseSelfState: () -> Unit = {},
    onChangeSelfState: (BcSelfStateChange) -> Unit = {},
    onClearSelfStateResult: () -> Unit = {},
    onCloseWardrobe: () -> Unit = {},
    onSelectWardrobeSlot: (Int) -> Unit = {},
    onBeginWardrobeSave: () -> Unit = {},
    onBeginWardrobeRename: () -> Unit = {},
    onChangeWardrobeName: (String) -> Unit = {},
    onCancelWardrobeEdit: () -> Unit = {},
    onConfirmWardrobeSave: () -> Unit = {},
    onConfirmWardrobeRename: () -> Unit = {},
    onPreviewWardrobeLoad: () -> Unit = {},
    onCancelWardrobeLoad: () -> Unit = {},
    onConfirmWardrobeLoad: () -> Unit = {},
    onClearWardrobeResult: () -> Unit = {},
) {
    uiState.selfStateSheet?.let { sheet ->
        AnimatedModalSheet(onDismissRequest = onCloseSelfState) { requestClose ->
            RoomSelfStateSheet(
                uiState = sheet,
                onClose = requestClose,
                onChange = onChangeSelfState,
                onClearResult = onClearSelfStateResult,
            )
        }
    }

    uiState.wardrobe?.takeIf { it.visible }?.let { state ->
        RoomWardrobeSheet(
            state = state,
            onClose = onCloseWardrobe,
            onSelectSlot = onSelectWardrobeSlot,
            onBeginSave = onBeginWardrobeSave,
            onBeginRename = onBeginWardrobeRename,
            onChangeName = onChangeWardrobeName,
            onCancelEdit = onCancelWardrobeEdit,
            onConfirmSave = onConfirmWardrobeSave,
            onConfirmRename = onConfirmWardrobeRename,
            onPreviewLoad = onPreviewWardrobeLoad,
            onCancelLoad = onCancelWardrobeLoad,
            onConfirmLoad = onConfirmWardrobeLoad,
            onClearResult = onClearWardrobeResult,
        )
    }
}
