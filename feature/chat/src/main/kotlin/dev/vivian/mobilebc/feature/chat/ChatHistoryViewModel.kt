package dev.vivian.mobilebc.feature.chat

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageHistoryQuery
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

data class ChatHistoryUiState(
    val visible: Boolean = false,
    val text: String = "",
    val date: LocalDate? = null,
    val messages: List<BcMessage> = emptyList(),
    val selectedKey: String? = null,
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val storageUnavailable: Boolean = false,
)

/** Owned by the chat model; history never advances the conversation's read cursor. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryViewModel(
    private val conversationId: BcConversationId,
    private val timeline: BcSessionTimeline,
    private val connection: ConnectionStatusReader,
    scope: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private data class Request(
        val owner: Pair<Int?, Long>,
        val text: String = "",
        val date: LocalDate? = null,
        val limit: Int = 100,
        val selected: BcMessage? = null,
        val contextLimit: Int = 50,
    )
    private data class Result(val request: Request, val rows: List<BcMessage>, val more: Boolean, val loading: Boolean = false)

    private fun owner() = connection.snapshot.value.let { it.account?.memberNumber to it.relationships.accountGeneration }
    private val request = MutableStateFlow<Request?>(null)
    private val activeRequest = combine(
        request,
        connection.snapshot.map { it.account?.memberNumber to it.relationships.accountGeneration }.distinctUntilChanged(),
    ) { value, owner -> value?.takeIf { it.owner == owner && owner.first != null } }

    private val results = activeRequest.flatMapLatest { value ->
        if (value == null) flowOf(null)
        else {
            val selected = value.selected
            val query = BcMessageHistoryQuery(
                text = value.text.trim(),
                fromMillis = value.date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
                untilMillis = value.date?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
                limit = value.limit + 1,
            )
            val rows = if (selected == null) {
                timeline.observeHistory(conversationId, query).map { snapshot ->
                    val messages = snapshot.rowsFor(value.owner.first, value.owner.second)
                    Result(value, messages.take(value.limit), messages.size > value.limit)
                }
            } else {
                val timestamp = selected.timestamp.toEpochMilli()
                combine(
                    timeline.observeHistory(conversationId, BcMessageHistoryQuery(untilMillis = timestamp + 1, limit = value.contextLimit + 1)),
                    timeline.observeHistory(conversationId, BcMessageHistoryQuery(fromMillis = timestamp, oldestFirst = true, limit = value.contextLimit + 1)),
                ) { before, after ->
                    val older = before.rowsFor(value.owner.first, value.owner.second)
                    val newer = after.rowsFor(value.owner.first, value.owner.second)
                    Result(
                        value,
                        (older.take(value.contextLimit) + selected + newer.take(value.contextLimit))
                            .distinctBy { it.chatUiKey }.sortedWith(compareBy({ it.timestamp }, { it.id.value }, { it.conversationId.value })),
                        older.size > value.contextLimit || newer.size > value.contextLimit,
                    )
                }
            }
            rows.onStart {
                emit(Result(value, emptyList(), false, loading = true))
                if (selected == null && value.text.isNotEmpty()) delay(250)
            }
        }
    }.runningFold(null as Result?) { previous, next ->
        if (next?.loading == true && previous?.request?.copy(
                limit = next.request.limit, contextLimit = next.request.contextLimit,
            ) == next.request
        ) next.copy(rows = previous.rows)
        else next
    }

    val uiState = combine(activeRequest, results, timeline.persistenceFailure) { value, result, failure ->
        if (value == null) ChatHistoryUiState()
        else {
            val current = result?.takeIf { it.request == value }
            ChatHistoryUiState(
                visible = true,
                text = value.text,
                date = value.date,
                selectedKey = value.selected?.chatUiKey,
                messages = current?.rows.orEmpty(),
                loading = current?.loading ?: true,
                hasMore = current?.more ?: false,
                storageUnavailable = failure?.let {
                    it.ownerMemberNumber == value.owner.first && it.accountGeneration == value.owner.second &&
                        (it.conversationId == null || it.conversationId == conversationId)
                } == true,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, ChatHistoryUiState())

    fun open() { request.value = Request(owner()) }
    fun close() { request.value = null }
    fun search(text: String) { request.value = request.value?.copy(text = text, limit = 100, selected = null) }
    fun selectDate(date: LocalDate?) { request.value = request.value?.copy(date = date, limit = 100, selected = null) }
    fun showContext(message: BcMessage) {
        if (uiState.value.messages.any { it.chatUiKey == message.chatUiKey }) {
            request.value = request.value?.copy(selected = message, contextLimit = 50)
        }
    }
    fun backToResults() { request.value = request.value?.copy(selected = null) }
    fun loadMore() {
        val value = request.value ?: return
        if (!uiState.value.hasMore || uiState.value.loading) return
        request.value = if (value.selected == null) value.copy(limit = value.limit + 100)
        else value.copy(contextLimit = value.contextLimit + 50)
    }
}
