package dev.vivian.mobilebc.feature.roomdetails

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.model.BcSelfStateChange
import dev.vivian.mobilebc.core.model.BcSelfStateResult
import dev.vivian.mobilebc.core.model.BcSelfStateUnavailableReason
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfExpressionGroup
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfStateOption
import dev.vivian.mobilebc.feature.rooms.R

/** Sheet content shared by room details and the room chat. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoomSelfStateSheet(
    uiState: SelfStateSheetUiState,
    onClose: () -> Unit,
    onChange: (BcSelfStateChange) -> Unit,
    onClearResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val projection = uiState.projection
    val character = projection.character
    var expressionsSelected by remember(character?.memberNumber) { mutableStateOf(false) }
    var expressionGroupName by remember(character?.memberNumber) { mutableStateOf<String?>(null) }
    val expressionGroup = projection.expressionGroups.firstOrNull { it.group == expressionGroupName }
        ?: projection.expressionGroups.firstOrNull()

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .then(if (expressionsSelected) Modifier.selectableGroup() else Modifier),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.room_self_state_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onClose) { Text(stringResource(R.string.room_details_close)) }
            }
        }
        character?.let { self ->
            item(key = "identity") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.room_self_state_character, self.displayName, self.memberNumber),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.room_self_state_own_character),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    self.ownership?.takeIf { (it.stage ?: 0) >= 1 }?.let { owner ->
                        Text(
                            stringResource(R.string.room_self_state_owner, owner.name, owner.memberNumber),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (uiState.busy || uiState.result != null || projection.unavailableReason != null) {
            if (projection.unavailableReason == null) {
                stickyHeader(key = "feedback") {
                    SelfStateFeedback(uiState, onClearResult)
                }
            } else {
                item(key = "feedback") {
                    SelfStateFeedback(uiState, onClearResult)
                }
            }
        }
        if (projection.unavailableReason == null) {
            stickyHeader(key = "tabs") {
                TabRow(selectedTabIndex = if (expressionsSelected) 1 else 0) {
                    Tab(
                        selected = !expressionsSelected,
                        onClick = { expressionsSelected = false },
                        text = { Text(stringResource(R.string.room_self_state_poses)) },
                    )
                    Tab(
                        selected = expressionsSelected,
                        onClick = { expressionsSelected = true },
                        text = { Text(stringResource(R.string.room_self_state_expressions)) },
                    )
                }
            }
            if (!expressionsSelected) {
                if (projection.poseGroups.isEmpty()) {
                    item(key = "no_poses") {
                        Text(stringResource(R.string.room_self_state_no_poses))
                    }
                }
                items(projection.poseGroups, key = { "pose_${it.category}" }) { group ->
                    Column(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            selfStateName(group.category),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                R.string.room_details_current_value,
                                group.currentName?.let { selfStateName(it) }
                                    ?: stringResource(R.string.room_self_state_pose_not_active),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        group.options.forEach { option ->
                            SelfStateOptionRow(
                                option = option,
                                busy = uiState.busy,
                                onSelect = { option.name?.let { onChange(BcSelfStateChange.Pose(it)) } },
                            )
                        }
                    }
                }
                item(key = "reset_pose") {
                    SelfStateResetButton(
                        label = stringResource(R.string.room_self_state_reset_pose),
                        reason = projection.resetPoseReason,
                        enabled = !uiState.busy && projection.poseGroups.isNotEmpty(),
                        onClick = { onChange(BcSelfStateChange.ResetPose) },
                    )
                }
            } else {
                if (projection.expressionGroups.isEmpty()) {
                    item(key = "no_expressions") {
                        Text(stringResource(R.string.room_self_state_no_expressions))
                    }
                } else {
                    item(key = "expression_groups") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            projection.expressionGroups.forEach { group ->
                                FilterChip(
                                    selected = expressionGroup?.group == group.group,
                                    onClick = { expressionGroupName = group.group },
                                    label = { Text(selfStateName(group.group)) },
                                )
                            }
                        }
                    }
                }
                expressionGroup?.let { group ->
                    item(key = "expression_heading_${group.group}") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                selfStateName(group.group),
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.room_details_current_value, expressionValue(group)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (group.group == "Eyes") {
                                Text(
                                    stringResource(R.string.room_self_state_eyes_together),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(group.options, key = { "expression_${group.group}_${it.name ?: "default"}" }) { option ->
                        SelfStateOptionRow(
                            option = option,
                            busy = uiState.busy,
                            onSelect = { onChange(BcSelfStateChange.Expression(group.group, option.name)) },
                        )
                    }
                }
                item(key = "reset_expressions") {
                    SelfStateResetButton(
                        label = stringResource(R.string.room_self_state_reset_expressions),
                        reason = projection.resetExpressionsReason,
                        enabled = !uiState.busy && projection.expressionGroups.isNotEmpty(),
                        onClick = { onChange(BcSelfStateChange.ResetExpressions) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelfStateOptionRow(
    option: BcSelfStateOption,
    busy: Boolean,
    onSelect: () -> Unit,
) {
    val enabled = !busy && option.unavailableReason == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = option.selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = option.selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(selfStateName(option.name), style = MaterialTheme.typography.bodyLarge)
            option.unavailableReason?.let { reason ->
                Text(
                    selfStateReason(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SelfStateResetButton(
    label: String,
    reason: BcSelfStateUnavailableReason?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        OutlinedButton(onClick = onClick, enabled = enabled && reason == null) { Text(label) }
        reason?.let {
            Text(selfStateReason(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SelfStateFeedback(uiState: SelfStateSheetUiState, onClearResult: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (uiState.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.room_details_sending), style = MaterialTheme.typography.bodySmall)
            }
        }
        uiState.projection.unavailableReason?.let {
            Text(selfStateReason(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        uiState.result?.let { result ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (result) {
                        BcSelfStateResult.Sent -> stringResource(R.string.room_self_state_sent)
                        is BcSelfStateResult.Rejected -> selfStateReason(result.reason)
                        is BcSelfStateResult.Failed -> stringResource(R.string.room_self_state_failed)
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result == BcSelfStateResult.Sent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onClearResult) { Text(stringResource(R.string.character_dismiss)) }
            }
        }
    }
}

@Composable
private fun expressionValue(group: BcSelfExpressionGroup): String =
    if (group.hasDifferentEyes) {
        stringResource(R.string.room_self_state_mixed_eyes, selfStateName(group.currentName), selfStateName(group.secondaryCurrentName))
    } else selfStateName(group.currentName)

@Composable
private fun selfStateReason(reason: BcSelfStateUnavailableReason): String = stringResource(
    when (reason) {
        BcSelfStateUnavailableReason.Offline -> R.string.room_self_state_offline
        BcSelfStateUnavailableReason.NotInRoom -> R.string.room_self_state_not_in_room
        BcSelfStateUnavailableReason.SelfUnavailable -> R.string.room_feedback_self_unavailable
        BcSelfStateUnavailableReason.AccountChanged -> R.string.room_self_state_account_changed
        BcSelfStateUnavailableReason.RoomChanged -> R.string.room_self_state_room_changed
        BcSelfStateUnavailableReason.CharacterStateChanged -> R.string.room_self_state_character_changed
        BcSelfStateUnavailableReason.UnsupportedAssetFamily -> R.string.room_self_state_unsupported_family
        BcSelfStateUnavailableReason.CatalogPreparing -> R.string.room_feedback_data_preparing
        BcSelfStateUnavailableReason.InvalidPose -> R.string.room_self_state_invalid_pose
        BcSelfStateUnavailableReason.PoseRestrictedByItem -> R.string.room_self_state_pose_item_restricted
        BcSelfStateUnavailableReason.PoseBlockedByOwner -> R.string.room_self_state_pose_owner_restricted
        BcSelfStateUnavailableReason.OwnerRulesUnavailable -> R.string.room_self_state_owner_rules_unavailable
        BcSelfStateUnavailableReason.InvalidExpression -> R.string.room_self_state_invalid_expression
        BcSelfStateUnavailableReason.ExpressionItemMissing -> R.string.room_self_state_expression_missing
        BcSelfStateUnavailableReason.ExpressionRestrictedByItem -> R.string.room_self_state_expression_item_restricted
        BcSelfStateUnavailableReason.ExpressionPrerequisiteNotMet -> R.string.room_self_state_expression_prerequisite
        BcSelfStateUnavailableReason.UnsupportedExpressionPrerequisite -> R.string.room_self_state_expression_unsupported
    },
)

@Composable
private fun selfStateName(name: String?): String = when {
    name == null -> stringResource(R.string.room_self_state_default)
    selfStateLabels[name] != null -> stringResource(selfStateLabels.getValue(name))
    else -> name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ')
}

private val selfStateLabels = mapOf(
    "BodyUpper" to R.string.room_self_state_arms,
    "BodyLower" to R.string.room_self_state_legs,
    "BodyFull" to R.string.room_self_state_full_body,
    "BaseUpper" to R.string.room_self_state_base_upper,
    "Yoked" to R.string.room_self_state_yoked,
    "OverTheHead" to R.string.room_self_state_over_head,
    "BackBoxTie" to R.string.room_self_state_back_box,
    "BackElbowTouch" to R.string.room_self_state_back_elbows,
    "BackCuffs" to R.string.room_self_state_back_cuffs,
    "BaseLower" to R.string.room_self_state_base_lower,
    "Kneel" to R.string.room_self_state_kneel,
    "KneelingSpread" to R.string.room_self_state_kneeling_spread,
    "LegsClosed" to R.string.room_self_state_legs_closed,
    "AllFours" to R.string.room_self_state_all_fours,
    "Hogtied" to R.string.room_self_state_hogtied,
    "Suspension" to R.string.room_self_state_suspension,
    "Spread" to R.string.room_self_state_spread,
    "TapedHands" to R.string.room_self_state_taped_hands,
    "LegsOpen" to R.string.room_self_state_legs_open,
    "Eyebrows" to R.string.room_self_state_eyebrows,
    "Eyes" to R.string.room_self_state_eyes,
    "Mouth" to R.string.room_self_state_mouth,
    "Pussy" to R.string.room_self_state_genitalia,
    "Blush" to R.string.room_self_state_blush,
    "Fluids" to R.string.room_self_state_fluids,
    "Emoticon" to R.string.room_self_state_emoticon,
    "Raised" to R.string.room_self_state_raised,
    "Lowered" to R.string.room_self_state_lowered,
    "OneRaised" to R.string.room_self_state_one_raised,
    "Harsh" to R.string.room_self_state_harsh,
    "Angry" to R.string.room_self_state_angry,
    "Soft" to R.string.room_self_state_soft,
    "Closed" to R.string.room_self_state_closed,
    "Dazed" to R.string.room_self_state_dazed,
    "Shy" to R.string.room_self_state_shy,
    "Sad" to R.string.room_self_state_sad,
    "Horny" to R.string.room_self_state_horny,
    "Lewd" to R.string.room_self_state_lewd,
    "VeryLewd" to R.string.room_self_state_very_lewd,
    "Heart" to R.string.room_self_state_heart,
    "HeartPink" to R.string.room_self_state_heart_pink,
    "LewdHeart" to R.string.room_self_state_lewd_heart,
    "LewdHeartPink" to R.string.room_self_state_lewd_heart_pink,
    "Dizzy" to R.string.room_self_state_dizzy,
    "Daydream" to R.string.room_self_state_daydream,
    "ShylyHappy" to R.string.room_self_state_shyly_happy,
    "Surprised" to R.string.room_self_state_surprised,
    "Scared" to R.string.room_self_state_scared,
    "Frown" to R.string.room_self_state_frown,
    "Pained" to R.string.room_self_state_pained,
    "HalfOpen" to R.string.room_self_state_half_open,
    "Open" to R.string.room_self_state_open,
    "Ahegao" to R.string.room_self_state_ahegao,
    "Moan" to R.string.room_self_state_moan,
    "TonguePinch" to R.string.room_self_state_tongue_pinch,
    "LipBite" to R.string.room_self_state_lip_bite,
    "Happy" to R.string.room_self_state_happy,
    "Devious" to R.string.room_self_state_devious,
    "Laughing" to R.string.room_self_state_laughing,
    "Grin" to R.string.room_self_state_grin,
    "Smirk" to R.string.room_self_state_smirk,
    "Pout" to R.string.room_self_state_pout,
    "Hard" to R.string.room_self_state_hard,
    "Low" to R.string.room_self_state_low,
    "Medium" to R.string.room_self_state_medium,
    "High" to R.string.room_self_state_high,
    "VeryHigh" to R.string.room_self_state_very_high,
    "Extreme" to R.string.room_self_state_extreme,
    "ShortBreath" to R.string.room_self_state_short_breath,
    "DroolLow" to R.string.room_self_state_drool_low,
    "DroolMedium" to R.string.room_self_state_drool_medium,
    "DroolHigh" to R.string.room_self_state_drool_high,
    "DroolSides" to R.string.room_self_state_drool_sides,
    "DroolMessy" to R.string.room_self_state_drool_messy,
    "DroolTearsLow" to R.string.room_self_state_drool_tears_low,
    "DroolTearsMedium" to R.string.room_self_state_drool_tears_medium,
    "DroolTearsHigh" to R.string.room_self_state_drool_tears_high,
    "DroolTearsMessy" to R.string.room_self_state_drool_tears_messy,
    "DroolTearsSides" to R.string.room_self_state_drool_tears_sides,
    "TearsHigh" to R.string.room_self_state_tears_high,
    "TearsMedium" to R.string.room_self_state_tears_medium,
    "TearsLow" to R.string.room_self_state_tears_low,
    "Afk" to R.string.room_self_state_afk,
    "Brb" to R.string.room_self_state_brb,
    "SOS" to R.string.room_self_state_sos,
    "Whisper" to R.string.room_self_state_whisper,
    "Sleep" to R.string.room_self_state_sleep,
    "Hearts" to R.string.room_self_state_hearts,
    "Tear" to R.string.room_self_state_tear,
    "Hearing" to R.string.room_self_state_hearing,
    "Confusion" to R.string.room_self_state_confusion,
    "Exclamation" to R.string.room_self_state_exclamation,
    "Annoyed" to R.string.room_self_state_annoyed,
    "Read" to R.string.room_self_state_read,
    "RaisedHand" to R.string.room_self_state_raised_hand,
    "Spectator" to R.string.room_self_state_spectator,
    "ThumbsDown" to R.string.room_self_state_thumbs_down,
    "ThumbsUp" to R.string.room_self_state_thumbs_up,
    "LoveRope" to R.string.room_self_state_love_rope,
    "LoveGag" to R.string.room_self_state_love_gag,
    "LoveLock" to R.string.room_self_state_love_lock,
    "Wardrobe" to R.string.room_self_state_wardrobe,
    "Gaming" to R.string.room_self_state_gaming,
    "Work" to R.string.room_self_state_work,
    "Shopping" to R.string.room_self_state_shopping,
    "Coffee" to R.string.room_self_state_coffee,
    "Fork" to R.string.room_self_state_fork,
    "Music" to R.string.room_self_state_music,
    "Car" to R.string.room_self_state_car,
    "Hanger" to R.string.room_self_state_hanger,
    "Call" to R.string.room_self_state_call,
    "Lightbulb" to R.string.room_self_state_lightbulb,
    "Warning" to R.string.room_self_state_warning,
    "BrokenHeart" to R.string.room_self_state_broken_heart,
    "Drawing" to R.string.room_self_state_drawing,
    "Coding" to R.string.room_self_state_coding,
    "TV" to R.string.room_self_state_tv,
    "Bathing" to R.string.room_self_state_bathing,
)
