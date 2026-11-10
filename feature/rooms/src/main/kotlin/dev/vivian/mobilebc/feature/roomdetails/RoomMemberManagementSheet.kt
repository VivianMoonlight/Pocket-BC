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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import dev.vivian.mobilebc.core.ui.AnimatedModalSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminObservation
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectionReason
import dev.vivian.mobilebc.feature.rooms.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomMemberManagementSheet(
    state: RoomMemberManagementUiState,
    onClose: () -> Unit,
    onSelectAction: (BcRoomMemberAdminAction) -> Unit,
    onCancelAction: () -> Unit,
    onConfirm: () -> Unit,
    onClearResult: () -> Unit,
) {
    androidx.compose.runtime.key(state.target.memberNumber) {
        AnimatedModalSheet(onDismissRequest = onClose) { requestClose ->
            Column(Modifier.fillMaxWidth().imePadding().heightIn(max = 720.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.room_member_manage),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = requestClose, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.room_details_close))
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("target") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(state.target.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.room_member_number, state.target.memberNumber))
                        }
                    }
                    if (state.busy) {
                        item("pending") {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.room_member_waiting))
                                }
                                Text(stringResource(R.string.room_member_waiting_help), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    state.result?.let { result ->
                        item("result") {
                            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                Text(result.text(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = onClearResult, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.room_member_dismiss_result))
                                }
                            }
                        }
                    }
                    if (!state.busy) {
                        state.unavailableReason?.let { reason ->
                            item("unavailable") {
                                Text(reason.text(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val action = state.selectedAction
                        if (action != null) {
                            item("confirmation") {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        action.confirmationText(state.target.displayName, state.target.memberNumber),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(action.descriptionText(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(
                                        onClick = onConfirm,
                                        enabled = state.unavailableReason == null && action in state.availableActions,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    ) {
                                        Text(action.text())
                                    }
                                    OutlinedButton(onClick = onCancelAction, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text(stringResource(R.string.room_member_cancel_action))
                                    }
                                }
                            }
                        } else {
                            items(state.availableActions, key = { it.name }) { available ->
                                OutlinedButton(
                                    onClick = { onSelectAction(available) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Text(available.text())
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun BcRoomMemberAdminAction.text(): String = stringResource(when (this) {
    BcRoomMemberAdminAction.Kick -> R.string.room_member_kick
    BcRoomMemberAdminAction.Ban -> R.string.room_member_ban
    BcRoomMemberAdminAction.Promote -> R.string.room_member_promote
    BcRoomMemberAdminAction.Demote -> R.string.room_member_demote
})

@Composable
private fun BcRoomMemberAdminAction.confirmationText(name: String, number: Int): String = stringResource(when (this) {
    BcRoomMemberAdminAction.Kick -> R.string.room_member_confirm_kick
    BcRoomMemberAdminAction.Ban -> R.string.room_member_confirm_ban
    BcRoomMemberAdminAction.Promote -> R.string.room_member_confirm_promote
    BcRoomMemberAdminAction.Demote -> R.string.room_member_confirm_demote
}, name, number)

@Composable
private fun BcRoomMemberAdminAction.descriptionText(): String = stringResource(when (this) {
    BcRoomMemberAdminAction.Kick -> R.string.room_member_kick_help
    BcRoomMemberAdminAction.Ban -> R.string.room_member_ban_help
    BcRoomMemberAdminAction.Promote -> R.string.room_member_promote_help
    BcRoomMemberAdminAction.Demote -> R.string.room_member_demote_help
})

@Composable
private fun RoomMemberManagementResult.text(): String = when (this) {
    is RoomMemberManagementResult.Observed -> stringResource(when (observation) {
        BcRoomMemberAdminObservation.TargetLeft -> R.string.room_member_observed_left
        BcRoomMemberAdminObservation.TargetBanned -> R.string.room_member_observed_banned
        BcRoomMemberAdminObservation.TargetPromoted -> R.string.room_member_observed_promoted
        BcRoomMemberAdminObservation.TargetDemoted -> R.string.room_member_observed_demoted
        BcRoomMemberAdminObservation.Unconfirmed -> R.string.room_member_unconfirmed
    })
    is RoomMemberManagementResult.Rejected -> reason.text()
    RoomMemberManagementResult.Failed -> stringResource(R.string.room_member_failed)
}

@Composable
private fun BcRoomMemberAdminRejectionReason.text(): String = stringResource(when (this) {
    BcRoomMemberAdminRejectionReason.ConnectionUnavailable -> R.string.room_member_offline
    BcRoomMemberAdminRejectionReason.AccountUnavailable -> R.string.room_member_account_changed
    BcRoomMemberAdminRejectionReason.RoomUnavailable -> R.string.room_member_room_unavailable
    BcRoomMemberAdminRejectionReason.RoomChanged -> R.string.room_member_room_changed
    BcRoomMemberAdminRejectionReason.SettingsUnavailable -> R.string.room_member_settings_unavailable
    BcRoomMemberAdminRejectionReason.NotAdministrator -> R.string.room_member_not_administrator
    BcRoomMemberAdminRejectionReason.TargetUnavailable -> R.string.room_member_target_unavailable
    BcRoomMemberAdminRejectionReason.TargetChanged -> R.string.room_member_target_changed
    BcRoomMemberAdminRejectionReason.SelfTarget -> R.string.room_member_self_target
    BcRoomMemberAdminRejectionReason.TargetRoleChanged -> R.string.room_member_target_role_changed
    BcRoomMemberAdminRejectionReason.Busy -> R.string.room_member_busy
    BcRoomMemberAdminRejectionReason.PendingResponse -> R.string.room_member_pending_response
})
