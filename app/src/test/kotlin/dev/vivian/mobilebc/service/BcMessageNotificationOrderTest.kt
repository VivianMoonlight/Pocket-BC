package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.service.BcSessionNotificationCoordinator.MessageNotificationCursor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcMessageNotificationOrderTest {
    @Test
    fun distinctMessagesInSameMillisecondAreNotOrderedByTheirIds() {
        assertTrue(MessageNotificationCursor(123, "a-id").canFollow(MessageNotificationCursor(123, "z-id")))
        assertTrue(MessageNotificationCursor(123, "z-id").canFollow(MessageNotificationCursor(123, "a-id")))
    }

    @Test
    fun repeatedIdAndOlderMessageAreSuppressed() {
        val latest = MessageNotificationCursor(123, "message-id")
        assertFalse(MessageNotificationCursor(123, "message-id").canFollow(latest))
        assertFalse(MessageNotificationCursor(122, "another-id").canFollow(latest))
        assertTrue(MessageNotificationCursor(124, "another-id").canFollow(latest))
    }
}
