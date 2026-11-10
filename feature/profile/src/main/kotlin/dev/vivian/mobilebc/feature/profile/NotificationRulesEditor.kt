package dev.vivian.mobilebc.feature.profile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.vivian.mobilebc.core.model.*
import java.util.UUID

private val messageTypes = BcMessageKind.entries.filter { it != BcMessageKind.System && it != BcMessageKind.Error }

@Composable
internal fun NotificationRulesEditor(settings: BcNotificationSettings, onChange: (BcNotificationSettings) -> Unit) {
    val levels = stringArrayResource(R.array.notification_levels).toList()
    val identities = stringArrayResource(R.array.notification_identities).toList()
    val types = stringArrayResource(R.array.notification_types).toList()
    var editing by remember { mutableStateOf<BcNotificationRule?>(null) }
    var testing by remember { mutableStateOf(false) }
    val dragStep = with(LocalDensity.current) { 80.dp.toPx() }
    Text(stringResource(R.string.notification_matrix), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.notification_matrix_help), style = MaterialTheme.typography.bodySmall)
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row {
            Text(stringResource(R.string.notification_identity_type), Modifier.width(110.dp))
            messageTypes.forEach { type ->
                Choice(types[type.ordinal], levels, Modifier.width(110.dp)) { index ->
                    onChange(settings.copy(matrix = settings.matrix + BcNotificationIdentity.entries.associate {
                        "${it.name}:${type.name}" to BcNotificationLevel.entries[index]
                    }))
                }
            }
        }
        BcNotificationIdentity.entries.forEach { identity ->
            Row {
                Choice(identities[identity.ordinal], levels, Modifier.width(110.dp)) { index ->
                    onChange(settings.copy(matrix = settings.matrix + messageTypes.associate {
                        "${identity.name}:${it.name}" to BcNotificationLevel.entries[index]
                    }))
                }
                messageTypes.forEach { type ->
                    Choice(
                        levels[settings.level(identity, type).ordinal], levels,
                        Modifier.width(110.dp),
                    ) { index ->
                        onChange(settings.copy(matrix = settings.matrix + ("${identity.name}:${type.name}" to BcNotificationLevel.entries[index])))
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.notification_system_level))
    Choice(levels[settings.systemLevel.ordinal], levels) { onChange(settings.copy(systemLevel = BcNotificationLevel.entries[it])) }
    TextButton(onClick = { onChange(settings.copy(matrix = emptyMap())) }) {
        Text(stringResource(R.string.notification_reset_matrix))
    }
    Text(stringResource(R.string.notification_custom_rules), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.notification_custom_help), style = MaterialTheme.typography.bodySmall)
    settings.rules.forEachIndexed { index, rule ->
        Card(Modifier.fillMaxWidth().pointerInput(rule.id, settings.rules) {
            var distance = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { distance = 0f },
                onDragCancel = { distance = 0f },
                onDragEnd = {
                    val steps = (distance / dragStep).toInt()
                    val destination = (index + steps).coerceIn(0, settings.rules.lastIndex)
                    if (destination != index) {
                        onChange(settings.copy(rules = settings.rules.toMutableList().apply {
                            add(destination, removeAt(index))
                        }))
                    }
                },
            ) { change, amount ->
                change.consume()
                distance += amount.y
            }
        }) {
            Column(Modifier.padding(8.dp)) {
                Row {
                    Text(rule.name, Modifier.weight(1f))
                    Switch(rule.enabled, { enabled ->
                        onChange(settings.copy(rules = settings.rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }))
                    })
                }
                Text(levels[rule.level.ordinal])
                Row {
                    TextButton(onClick = { editing = rule }) { Text(stringResource(R.string.notification_edit)) }
                    TextButton(onClick = { onChange(settings.copy(rules = settings.rules.filterNot { it.id == rule.id })) }) {
                        Text(stringResource(R.string.notification_delete))
                    }
                }
                Row {
                    TextButton(enabled = index > 0, onClick = {
                        onChange(settings.copy(rules = settings.rules.toMutableList().apply { add(index - 1, removeAt(index)) }))
                    }) { Text(stringResource(R.string.notification_move_up)) }
                    TextButton(enabled = index < settings.rules.lastIndex, onClick = {
                        onChange(settings.copy(rules = settings.rules.toMutableList().apply { add(index + 1, removeAt(index)) }))
                    }) { Text(stringResource(R.string.notification_move_down)) }
                }
            }
        }
    }
    TextButton(onClick = { editing = BcNotificationRule(UUID.randomUUID().toString(), "") }) {
        Text(stringResource(R.string.notification_add_rule))
    }
    TextButton(onClick = { testing = true }) { Text(stringResource(R.string.notification_test)) }
    editing?.let { rule ->
        RuleDialog(rule, onDismiss = { editing = null }, onSave = { updated ->
            val rules = if (settings.rules.any { it.id == updated.id }) {
                settings.rules.map { if (it.id == updated.id) updated else it }
            } else settings.rules + updated
            onChange(settings.copy(rules = rules))
            editing = null
        })
    }
    if (testing) NotificationRuleTestDialog(settings) { testing = false }
}

@Composable
private fun Choice(value: String, choices: List<String>, modifier: Modifier = Modifier, onChoice: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }) { Text(value) }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onChoice(index) })
            }
        }
    }
}

@Composable
private fun RuleDialog(rule: BcNotificationRule, onDismiss: () -> Unit, onSave: (BcNotificationRule) -> Unit) {
    var draft by remember(rule.id) { mutableStateOf(rule) }
    var member by remember(rule.id) { mutableStateOf(rule.memberNumber?.toString().orEmpty()) }
    val levels = stringArrayResource(R.array.notification_levels).toList()
    val identities = stringArrayResource(R.array.notification_identities).toList()
    val types = stringArrayResource(R.array.notification_types).toList()
    val any = stringResource(R.string.notification_any)
    val selfChoices = listOf(any, stringResource(R.string.notification_self_yes), stringResource(R.string.notification_self_no))
    val validMember = member.isBlank() || (member.toIntOrNull()?.let { it > 0 } == true)
    val hasCondition = member.isNotBlank() || draft.identity != null || draft.type != null ||
        draft.keyword.isNotBlank() || draft.involvesSelf != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notification_custom_rules)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text(stringResource(R.string.notification_rule_name)) })
                Choice(
                    stringResource(if (draft.matchAll) R.string.notification_match_all else R.string.notification_match_any),
                    listOf(stringResource(R.string.notification_match_all), stringResource(R.string.notification_match_any)),
                ) { draft = draft.copy(matchAll = it == 0) }
                OutlinedTextField(
                    member, { member = it }, singleLine = true, isError = !validMember,
                    label = { Text(stringResource(R.string.notification_member)) },
                )
                Text(stringResource(R.string.notification_identity))
                Choice(draft.identity?.let { identities[it.ordinal] } ?: any, listOf(any) + identities) {
                    draft = draft.copy(identity = BcNotificationIdentity.entries.getOrNull(it - 1))
                }
                Text(stringResource(R.string.notification_type))
                Choice(draft.type?.let { types[it.ordinal] } ?: any, listOf(any) + types) {
                    draft = draft.copy(type = BcMessageKind.entries.getOrNull(it - 1))
                }
                OutlinedTextField(draft.keyword, { draft = draft.copy(keyword = it) }, label = { Text(stringResource(R.string.notification_keyword)) })
                Choice(selfChoices[when (draft.involvesSelf) { null -> 0; true -> 1; false -> 2 }], selfChoices) {
                    draft = draft.copy(involvesSelf = when (it) { 1 -> true; 2 -> false; else -> null })
                }
                Text(stringResource(R.string.notification_result))
                Choice(levels[draft.level.ordinal], levels) { draft = draft.copy(level = BcNotificationLevel.entries[it]) }
                if (!hasCondition) Text(stringResource(R.string.notification_condition_required))
            }
        },
        confirmButton = {
            TextButton(enabled = draft.name.isNotBlank() && validMember && hasCondition, onClick = {
                onSave(draft.copy(name = draft.name.trim(), memberNumber = member.toIntOrNull(), keyword = draft.keyword.trim()))
            }) { Text(stringResource(R.string.notification_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notification_cancel)) } },
    )
}

@Composable
private fun NotificationRuleTestDialog(settings: BcNotificationSettings, onDismiss: () -> Unit) {
    var member by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf(BcNotificationIdentity.Stranger)) }
    var type by remember { mutableStateOf(BcMessageKind.Chat) }
    var text by remember { mutableStateOf("") }
    var involvesSelf by remember { mutableStateOf(false) }
    val identities = stringArrayResource(R.array.notification_identities)
    val types = stringArrayResource(R.array.notification_types).toList()
    val levels = stringArrayResource(R.array.notification_levels)
    val result = settings.decide(BcNotificationInput(
        member.toIntOrNull(), selected, type, text, involvesSelf,
        type == BcMessageKind.System || type == BcMessageKind.Error,
    ))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notification_test)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.notification_test_help))
                OutlinedTextField(member, { member = it }, label = { Text(stringResource(R.string.notification_member)) })
                BcNotificationIdentity.entries.forEach { identity ->
                    Row {
                        Checkbox(identity in selected, { checked ->
                            selected = if (checked) selected + identity else selected - identity
                        })
                        Text(identities[identity.ordinal], Modifier.padding(top = 12.dp))
                    }
                }
                Choice(types[type.ordinal], types) { type = BcMessageKind.entries[it] }
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.notification_sample)) })
                Row {
                    Checkbox(involvesSelf, { involvesSelf = it })
                    Text(stringResource(R.string.notification_self_yes), Modifier.padding(top = 12.dp))
                }
                Text(levels[result.level.ordinal], style = MaterialTheme.typography.titleMedium)
                Text(result.ruleId?.let { id -> settings.rules.first { it.id == id }.name }
                    ?: stringResource(R.string.notification_matrix))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notification_close)) } },
    )
}
