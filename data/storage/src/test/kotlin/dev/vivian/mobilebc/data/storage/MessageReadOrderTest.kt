package dev.vivian.mobilebc.data.storage

import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReadOrderTest {
    @Test
    fun cursorOrderUsesTimestampThenMessageId() {
        assertTrue(MessageReadOrder(101L, "a") > MessageReadOrder(100L, "z"))
        assertTrue(MessageReadOrder(100L, "b") > MessageReadOrder(100L, "a"))
        assertTrue(MessageReadOrder(100L, "a") > MessageReadOrder.BeforeAll)
    }
}
