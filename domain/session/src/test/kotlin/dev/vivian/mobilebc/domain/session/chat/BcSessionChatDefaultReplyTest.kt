package dev.vivian.mobilebc.domain.session.chat

import dev.vivian.mobilebc.core.model.BcMessageReplyReference
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class BcSessionChatDefaultReplyTest {
    @Test
    fun anOlderSessionImplementationCannotSilentlySendAReplyAsAnOrdinaryMessage() = runTest {
        val ordinaryCalls = mutableListOf<String>()
        val session = object : BcSessionChat {
            override suspend fun sendChat(text: String) { ordinaryCalls += text }
            override suspend fun sendEmote(text: String) { ordinaryCalls += text }
            override suspend fun sendAction(text: String) { ordinaryCalls += text }
            override suspend fun sendWhisper(targetMemberNumber: Int, text: String) { ordinaryCalls += text }
            override suspend fun sendBeep(targetMemberNumber: Int, text: String) { ordinaryCalls += text }
        }
        val reference = BcMessageReplyReference("original", bcRoomConversationId("Room A"), BcMessageTransport.RoomChat, "Room A")
        val sends: List<suspend () -> Unit> = listOf(
            { session.sendChat("Reply", reference) },
            { session.sendEmote("Reply", reference) },
            { session.sendWhisper(20, "Reply", reference) },
        )

        sends.forEach { send ->
            assertTrue(runCatching { send() }.exceptionOrNull() is UnsupportedOperationException)
        }
        assertTrue(ordinaryCalls.isEmpty())
    }
}
