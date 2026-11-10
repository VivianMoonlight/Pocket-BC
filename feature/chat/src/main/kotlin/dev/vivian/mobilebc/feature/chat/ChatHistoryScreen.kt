package dev.vivian.mobilebc.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.ui.MobileBcIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    state: ChatHistoryUiState,
    title: String,
    onClose: () -> Unit,
    onSearch: (String) -> Unit,
    onDate: (LocalDate?) -> Unit,
    onContext: (BcMessage) -> Unit,
    onBackToResults: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val inContext = state.selectedKey != null
    val back = if (inContext) onBackToResults else onClose
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    val searchList = rememberLazyListState()
    val contextList = rememberLazyListState()
    var positionedKey by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    LaunchedEffect(state.text, state.date) { searchList.scrollToItem(0) }
    LaunchedEffect(state.selectedKey, state.messages, state.loading) {
        if (state.selectedKey == null) positionedKey = null
        else if (!state.loading && positionedKey != state.selectedKey) {
            val index = state.messages.indexOfFirst { it.chatUiKey == state.selectedKey }
            if (index >= 0) {
                contextList.scrollToItem(index)
                positionedKey = state.selectedKey
            }
        }
    }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(if (inContext) R.string.history_context else R.string.history_title))
                            Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = back) { Icon(MobileBcIcons.Back, stringResource(R.string.chat_back)) }
                    },
                    actions = {
                        if (inContext) TextButton(onClick = onClose) { Text(stringResource(R.string.history_close)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().widthIn(max = 840.dp)) {
                if (!inContext) {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = onSearch,
                        label = { Text(stringResource(R.string.history_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        trailingIcon = {
                            if (state.text.isNotEmpty()) TextButton(onClick = { onSearch("") }) { Text(stringResource(R.string.chat_clear)) }
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { focus.clearFocus(); showCalendar = true }) {
                            Text(state.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: stringResource(R.string.history_date))
                        }
                        if (state.date != null) TextButton(onClick = { onDate(null) }) { Text(stringResource(R.string.history_all_dates)) }
                    }
                    Text(stringResource(R.string.history_local), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.storageUnavailable) {
                    Text(stringResource(R.string.timeline_storage_unavailable), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                if (state.loading) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (state.messages.isEmpty() && !state.storageUnavailable) {
                    Text(stringResource(R.string.history_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!state.loading || state.messages.isNotEmpty()) LazyColumn(
                    state = if (inContext) contextList else searchList,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(state.messages, key = { it.chatUiKey }) { message ->
                        HistoryMessage(
                            message, if (inContext) "" else state.text.trim(),
                            selected = message.chatUiKey == state.selectedKey,
                            onClick = if (inContext) null else ({ focus.clearFocus(); onContext(message) }),
                        )
                    }
                    if (state.hasMore && !state.loading) item("more") {
                        TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (inContext) R.string.history_more_context else R.string.history_more))
                        }
                    }
                }
            }
        }
    }
    if (showCalendar) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    picker.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showCalendar = false
                }) { Text(stringResource(R.string.history_show_date)) }
            },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text(stringResource(R.string.history_cancel)) } },
        ) { DatePicker(state = picker) }
    }
}

@Composable
private fun HistoryMessage(message: BcMessage, query: String, selected: Boolean, onClick: (() -> Unit)?) {
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val text = remember(message.text, query, highlight) {
        buildAnnotatedString {
            append(message.text)
            if (query.isNotEmpty()) {
                var index = message.text.indexOf(query, ignoreCase = true)
                while (index >= 0) {
                    addStyle(SpanStyle(background = highlight), index, index + query.length)
                    index = message.text.indexOf(query, index + query.length, ignoreCase = true)
                }
            }
        }
    }
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClickLabel = stringResource(R.string.history_context), onClick = onClick) else Modifier).padding(16.dp)) {
            Text(message.sender.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (message.transport == dev.vivian.mobilebc.core.model.BcMessageTransport.RoomWhisper) {
                Text(stringResource(R.string.chat_whisper_mode), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            Text(message.timestamp.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (onClick == null) SelectionContainer { Text(text) }
            else Text(text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider()
}
