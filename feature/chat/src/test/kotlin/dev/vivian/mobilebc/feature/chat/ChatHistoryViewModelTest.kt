package dev.vivian.mobilebc.feature.chat

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.timeline.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryViewModelTest {
    private val id = BcConversationId("message:1001")
    private val zone = ZoneId.of("America/New_York")
    private fun message(index: Int, text: String = "row $index", timestamp: Instant = Instant.ofEpochSecond(index.toLong())) = BcMessage(
        id = BcMessageId("$index"), conversationId = id, address = BcMessageAddress.Direct(1001),
        sender = BcSender(1001, "Alice"), kind = BcMessageKind.Chat, text = text, timestamp = timestamp,
    )
    private class Connection : ConnectionStatusReader {
        override val snapshot = MutableStateFlow(BcSessionSnapshot(account = BcAccount(9999, "Me", "me")))
    }
    private class Timeline : BcSessionTimeline {
        val rows = MutableStateFlow(emptyList<BcMessage>())
        val queries = mutableListOf<BcMessageHistoryQuery>()
        var owner = 9999
        var readCalls = 0
        override val conversations = MutableStateFlow(BcConversationSnapshot())
        override val persistenceFailure = MutableStateFlow<BcTimelinePersistenceFailure?>(null)
        override fun observeHistory(conversationId: BcConversationId, query: BcMessageHistoryQuery) = rows.map { all ->
            queries += query
            val matching = all.filter {
                it.conversationId == conversationId && it.text.contains(query.text, ignoreCase = true) &&
                    (query.fromMillis == null || it.timestamp.toEpochMilli() >= query.fromMillis!!) &&
                    (query.untilMillis == null || it.timestamp.toEpochMilli() < query.untilMillis!!)
            }.sortedWith(compareBy({ it.timestamp }, { it.id.value }))
            BcMessageSnapshot(owner, 0, (if (query.oldestFirst) matching else matching.asReversed()).take(query.limit))
        }
        override fun observeMessages(conversationId: BcConversationId, limit: Int) = flowOf(BcMessageSnapshot())
        override fun selectConversation(conversationId: BcConversationId, owner: Any) = Unit
        override fun clearSelectedConversation(owner: Any) = Unit
        override fun isConversationVisible(conversationId: BcConversationId) = false
        override fun advanceReadCursor(ownerMemberNumber: Int, accountGeneration: Long, conversationId: BcConversationId, throughMessage: BcMessage) { readCalls++ }
    }

    @Test fun searchesBeyondLatest500AndPagesWithoutMarkingRead() = runTest {
        val timeline = Timeline().apply { rows.value = (0..1200).map { message(it) } }
        val model = ChatHistoryViewModel(id, timeline, Connection(), backgroundScope, zone)
        runCurrent()
        assertTrue(timeline.queries.isEmpty())
        model.open()
        runCurrent()
        assertEquals(100, model.uiState.value.messages.size)
        assertTrue(model.uiState.value.hasMore)
        model.loadMore()
        runCurrent()
        assertEquals(200, model.uiState.value.messages.size)
        model.search("row 0")
        advanceTimeBy(251)
        runCurrent()
        assertEquals(listOf("0"), model.uiState.value.messages.map { it.id.value })
        assertFalse(model.uiState.value.hasMore)
        assertEquals(0, timeline.readCalls)
        model.close()
        runCurrent()
        assertFalse(model.uiState.value.visible)
        assertTrue(model.uiState.value.messages.isEmpty())
    }

    @Test fun dateUsesLocalDayIncludingDaylightSavingBoundary() = runTest {
        val day = LocalDate.of(2026, 3, 8)
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val timeline = Timeline().apply {
            rows.value = listOf(message(0, timestamp = start.minusMillis(1)), message(1, timestamp = start),
                message(2, timestamp = end.minusMillis(1)), message(3, timestamp = end))
        }
        val model = ChatHistoryViewModel(id, timeline, Connection(), backgroundScope, zone)
        model.open()
        model.selectDate(day)
        runCurrent()
        assertEquals(listOf("2", "1"), model.uiState.value.messages.map { it.id.value })
        val query = timeline.queries.last()
        assertEquals(23 * 3600_000L, query.untilMillis!! - query.fromMillis!!)
    }

    @Test fun contextRemovesSearchFiltersAndBackRestoresThem() = runTest {
        val timeline = Timeline().apply { rows.value = (0..200).map { message(it, if (it == 100) "needle" else "context") } }
        val model = ChatHistoryViewModel(id, timeline, Connection(), backgroundScope, zone)
        model.open()
        model.search("needle")
        advanceTimeBy(251)
        runCurrent()
        val selected = model.uiState.value.messages.single()
        model.showContext(selected)
        runCurrent()
        assertEquals(selected.chatUiKey, model.uiState.value.selectedKey)
        assertTrue(model.uiState.value.messages.any { it.id.value == "99" })
        assertTrue(model.uiState.value.messages.any { it.id.value == "101" })
        model.backToResults()
        advanceTimeBy(251)
        runCurrent()
        assertEquals(listOf(selected), model.uiState.value.messages)
        assertEquals("needle", model.uiState.value.text)
    }

    @Test fun accountSwitchHidesOldResultsEvenIfStorageEmitsOldOwner() = runTest {
        val timeline = Timeline().apply { rows.value = listOf(message(1)) }
        val connection = Connection()
        val model = ChatHistoryViewModel(id, timeline, connection, backgroundScope, zone)
        model.open()
        runCurrent()
        assertEquals(1, model.uiState.value.messages.size)
        connection.snapshot.value = connection.snapshot.value.copy(account = BcAccount(2000, "Other", "other"))
        runCurrent()
        assertFalse(model.uiState.value.visible)
        assertTrue(model.uiState.value.messages.isEmpty())
        model.open()
        runCurrent()
        assertTrue(model.uiState.value.visible)
        assertTrue(model.uiState.value.messages.isEmpty())
    }

    @Test fun selectedMessageRemainsVisibleWhenManyMessagesShareATimestamp() = runTest {
        val timeline = Timeline().apply {
            rows.value = (0..300).map { message(it, if (it == 150) "needle" else "context", Instant.ofEpochSecond(1)) }
        }
        val model = ChatHistoryViewModel(id, timeline, Connection(), backgroundScope, zone)
        model.open()
        model.search("needle")
        advanceTimeBy(251)
        runCurrent()
        val selected = model.uiState.value.messages.single()
        model.showContext(selected)
        runCurrent()
        assertTrue(model.uiState.value.messages.contains(selected))
        assertEquals(model.uiState.value.messages.size, model.uiState.value.messages.distinctBy { it.chatUiKey }.size)
    }
}
