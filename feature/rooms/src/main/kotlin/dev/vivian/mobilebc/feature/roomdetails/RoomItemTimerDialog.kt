package dev.vivian.mobilebc.feature.roomdetails

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.domain.bcitemproperty.*
import dev.vivian.mobilebc.feature.rooms.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import kotlin.random.Random

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RoomItemTimerDialog(timer: InteractionItemTimerUiState, busy: Boolean, onDismiss: () -> Unit, onChange: (BcLockTimerChange) -> Unit) {
    var removeItem by remember { mutableStateOf(timer.removeItem) }
    var showTimer by remember { mutableStateOf(timer.showTimer) }
    var randomInput by remember { mutableStateOf(timer.randomInput) }
    var selected by remember { mutableStateOf(bcTimerDurations.first()) }
    var selecting by remember { mutableStateOf(false) }
    var now by remember(timer.clock) { mutableStateOf(timer.clock?.epochMillis) }
    LaunchedEffect(timer.clock) {
        val clock = timer.clock ?: return@LaunchedEffect
        while (isActive) {
            val elapsed = System.nanoTime() - clock.receivedAtMonotonicNanos
            now = if (elapsed < 0) null else clock.epochMillis + elapsed / 1_000_000
            delay(1000)
        }
    }
    val remaining = timer.deadlineMillis?.let { deadline -> now?.let { ceil(deadline - it).toLong().coerceAtLeast(0) } }
    val expired = timer.deadlineMillis?.let { deadline -> now?.let { deadline < it } } == true
    fun send(change: BcLockTimerChange) { onChange(change); onDismiss() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.room_details_timer_controls)) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                timer.clock == null -> Text(stringResource(R.string.room_permission_lock_clock_unavailable))
                expired -> Text(stringResource(R.string.room_permission_timer_expired))
                remaining != null -> Text(stringResource(R.string.room_details_timer_remaining, timerCountdown(remaining)))
                else -> Text(stringResource(R.string.room_details_timer_hidden))
            }
            timer.maximumSeconds?.let { Text(stringResource(R.string.room_details_timer_maximum, timerCountdown(it * 1000)), style = MaterialTheme.typography.bodySmall) }
            if (timer.settings != null) {
                val enabled = !busy && !expired && timer.settings.enabled
                TimerSettingRow(stringResource(R.string.room_details_timer_remove_item), removeItem, enabled) { removeItem = it }
                if (!timer.basic) {
                    TimerSettingRow(stringResource(R.string.room_details_timer_show), showTimer, enabled) { showTimer = it }
                    TimerSettingRow(stringResource(R.string.room_details_timer_allow_random), randomInput, enabled) { randomInput = it }
                }
                TextButton(onClick = { send(BcLockTimerChange.Settings(removeItem, showTimer.takeUnless { timer.basic }, randomInput.takeUnless { timer.basic })) }, enabled = enabled) {
                    Text(stringResource(R.string.room_details_timer_save_settings))
                }
                timer.settings.permissionMessage?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
            } else Text(stringResource(if (timer.removeItem) R.string.room_details_timer_will_remove else R.string.room_details_timer_will_keep))
            timer.restart?.let { entry ->
                TextButton(onClick = { send(BcLockTimerChange.Restart) }, enabled = !busy && !expired && entry.enabled) { Text(stringResource(R.string.room_details_timer_restart)) }
                entry.permissionMessage?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
            }
            timer.adjust?.let { entry ->
                Box {
                    OutlinedButton(onClick = { selecting = true }, enabled = !busy && !expired && entry.enabled) { Text(timerDurationLabel(selected)) }
                    DropdownMenu(expanded = selecting, onDismissRequest = { selecting = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        bcTimerDurations.forEach { duration -> DropdownMenuItem(text = { Text(timerDurationLabel(duration)) }, onClick = { selected = duration; selecting = false }) }
                    }
                }
                TextButton(onClick = { send(BcLockTimerChange.Adjust(selected.seconds)) }, enabled = !busy && !expired && entry.enabled) { Text(stringResource(R.string.room_details_timer_adjust)) }
                entry.permissionMessage?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
            }
            timer.limited?.let { entry ->
                val step = timer.limitedStepSeconds
                if (step != null) {
                    Text(stringResource(R.string.room_details_timer_limited_help, step * 2 / 60), style = MaterialTheme.typography.bodySmall)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { send(BcLockTimerChange.LimitedInput(BcTimerInputChoice.Decrease)) }, enabled = !busy && !expired && entry.enabled) { Text(stringResource(R.string.room_details_timer_minus_minutes, step / 60)) }
                        TextButton(onClick = { send(BcLockTimerChange.LimitedInput(BcTimerInputChoice.Random, Random.nextBoolean())) }, enabled = !busy && !expired && entry.enabled) { Text(stringResource(R.string.room_details_timer_random)) }
                        TextButton(onClick = { send(BcLockTimerChange.LimitedInput(BcTimerInputChoice.Increase)) }, enabled = !busy && !expired && entry.enabled) { Text(stringResource(R.string.room_details_timer_plus_minutes, step / 60)) }
                    }
                }
                entry.permissionMessage?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.room_details_item_preview_close)) } })
}

@Composable private fun TimerSettingRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun timerDurationLabel(duration: BcTimerDuration): String = stringResource(when (duration.unit) {
    BcTimerUnit.Minutes -> R.string.room_details_timer_minutes
    BcTimerUnit.Hours -> R.string.room_details_timer_hours
    BcTimerUnit.Days -> R.string.room_details_timer_days
}, duration.amount)

@Composable private fun timerCountdown(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    val time = listOf(totalSeconds / 3600 % 24, totalSeconds / 60 % 60, totalSeconds % 60).joinToString(":") { it.toString().padStart(2, '0') }
    val days = totalSeconds / 86400
    return if (days > 0) stringResource(R.string.room_details_timer_days_countdown, days, time) else time
}
