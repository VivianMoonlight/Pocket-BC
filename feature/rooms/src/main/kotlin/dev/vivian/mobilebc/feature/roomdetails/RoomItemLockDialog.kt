package dev.vivian.mobilebc.feature.roomdetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.domain.bcitemproperty.BcCombinationInput
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfigurationMode
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockCredential
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockInputKind
import dev.vivian.mobilebc.domain.bcitemproperty.validBcNewLockPassword
import dev.vivian.mobilebc.feature.rooms.R

@Composable
internal fun RoomItemLockDialog(
    picker: InteractionItemPickerUiState,
    busy: Boolean,
    configure: Boolean,
    onDismiss: () -> Unit,
    onUnlock: (String) -> Unit,
    onConfigure: (BcLockConfiguration) -> Unit,
) {
    val mode = picker.currentItemLockConfigurationMode.takeIf { configure }
    val kind = picker.currentItemLockInputKind
    val combination = kind == BcLockInputKind.Combination
    val input = remember(picker.lockInputConditions) { BcCombinationInput(picker.lockInputConditions) }
    var current by remember(picker.lockInputConditions) { mutableStateOf("") }
    var next by remember(picker.lockInputConditions) { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var removeOnUnlock by remember { mutableStateOf(picker.currentItemRemoveOnUnlock) }
    var keyholders by remember { mutableStateOf(picker.currentItemKeyholders) }
    var appendOwner by remember { mutableStateOf(true) }
    var appendLovers by remember { mutableStateOf(true) }
    var appendWhitelist by remember { mutableStateOf(false) }
    val title = when (mode) {
        BcLockConfigurationMode.UpdateKeyholders -> R.string.room_details_lock_keyholders
        BcLockConfigurationMode.InitializePassword -> R.string.room_details_set_lock_password
        BcLockConfigurationMode.ChangeCombination -> R.string.room_details_change_lock_combination
        null -> R.string.room_details_unlock_with_code
    }
    val valid = when (mode) {
        BcLockConfigurationMode.UpdateKeyholders -> keyholders.length <= 250
        BcLockConfigurationMode.InitializePassword -> validBcNewLockPassword(next) && hint.length <= 140
        BcLockConfigurationMode.ChangeCombination -> kind?.acceptsInput(current) == true && kind.acceptsInput(next)
        null -> !configure && kind?.acceptsInput(current) == true
    }
    val entry = if (configure) picker.currentItemLockConfigurationEntry else picker.currentItemCredentialUnlockEntry
    val keyboard = KeyboardOptions(keyboardType = if (combination) KeyboardType.NumberPassword else KeyboardType.Password)
    val visual = if (combination && !picker.lockInputConditions.blind) VisualTransformation.None else PasswordVisualTransformation()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == BcLockConfigurationMode.UpdateKeyholders) {
                    Text(stringResource(R.string.room_details_keyholders_help), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = keyholders, onValueChange = { if (it.length <= 250) keyholders = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        label = { Text(stringResource(R.string.room_details_keyholder_numbers)) }, supportingText = { Text("${keyholders.length}/250") })
                    LockKeyholderCheckbox(appendOwner, R.string.room_details_keyholders_append_owner) { appendOwner = it }
                    LockKeyholderCheckbox(appendLovers, R.string.room_details_keyholders_append_lovers) { appendLovers = it }
                    LockKeyholderCheckbox(appendWhitelist, R.string.room_details_keyholders_append_whitelist) { appendWhitelist = it }
                    if (picker.currentItemKeysDeposited) Text(stringResource(R.string.room_details_keyholders_deposited), style = MaterialTheme.typography.bodySmall)
                } else {
                    if (combination && picker.lockInputConditions.blind) Text(stringResource(R.string.room_details_combination_blind))
                    if (combination && picker.lockInputConditions.clumsiness > 0) Text(stringResource(R.string.room_details_combination_clumsy))
                    if (mode != BcLockConfigurationMode.InitializePassword) {
                        picker.currentItemLockHint?.let { Text(stringResource(R.string.room_details_lock_hint, it)) }
                        OutlinedTextField(value = current, onValueChange = { if (it.length <= (kind?.maxLength ?: 8)) current = if (combination) input.edit(it) else it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = visual, keyboardOptions = keyboard,
                            label = { Text(stringResource(if (combination) R.string.room_details_lock_combination else R.string.room_details_lock_password)) })
                    }
                    if (configure) {
                        OutlinedTextField(value = next, onValueChange = { if (it.length <= (kind?.maxLength ?: 8)) next = if (combination) input.edit(it) else it },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = visual, keyboardOptions = keyboard,
                            label = { Text(stringResource(if (combination) R.string.room_details_new_lock_combination else R.string.room_details_new_lock_password)) })
                        if (mode == BcLockConfigurationMode.InitializePassword) {
                            Text(stringResource(R.string.room_details_password_initialization_help), style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = hint, onValueChange = { if (it.length <= 140) hint = it }, modifier = Modifier.fillMaxWidth(),
                                singleLine = true, label = { Text(stringResource(R.string.room_details_lock_hint_label)) },
                                placeholder = { Text(picker.currentItemLockHint.orEmpty()) }, supportingText = { Text("${hint.length}/140") })
                            if (!picker.currentItemLockIsTimerPassword) Row(Modifier.fillMaxWidth().toggleable(
                                value = removeOnUnlock, role = Role.Checkbox, onValueChange = { removeOnUnlock = it }), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = removeOnUnlock, onCheckedChange = null)
                                Text(stringResource(R.string.room_details_remove_item_on_unlock))
                            }
                            if (!validBcNewLockPassword(next)) Text(stringResource(R.string.room_permission_lock_configuration_invalid))
                        }
                    } else {
                        Text(stringResource(if (picker.currentItemRemoveOnUnlock && kind == BcLockInputKind.Password)
                            R.string.room_details_unlock_removes_item else R.string.room_details_unlock_keeps_item), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            when (mode) {
                BcLockConfigurationMode.UpdateKeyholders -> onConfigure(BcLockConfiguration.UpdateKeyholders(keyholders, appendOwner, appendLovers, appendWhitelist))
                BcLockConfigurationMode.InitializePassword -> onConfigure(BcLockConfiguration.InitializePassword(BcLockCredential(next), hint,
                    removeOnUnlock.takeUnless { picker.currentItemLockIsTimerPassword }))
                BcLockConfigurationMode.ChangeCombination -> onConfigure(BcLockConfiguration.ChangeCombination(BcLockCredential(current), BcLockCredential(next)))
                null -> onUnlock(current)
            }
            onDismiss()
        }, enabled = valid && !busy && entry?.enabled == true) { Text(stringResource(if (configure) R.string.room_details_apply else R.string.room_details_unlock)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.room_details_item_preview_close)) } })
}

@Composable
private fun LockKeyholderCheckbox(checked: Boolean, label: Int, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(label))
    }
}
