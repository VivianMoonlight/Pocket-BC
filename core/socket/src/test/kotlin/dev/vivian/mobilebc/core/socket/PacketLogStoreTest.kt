package dev.vivian.mobilebc.core.socket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketLogStoreTest {
    @Test
    fun recordKeepsLatestEntries() {
        val store = PacketLogStore(maxEntries = 2)

        store.record(PacketLogDirection.Outbound, "first", """{"a":1}""")
        store.record(PacketLogDirection.Inbound, "second", """{"b":2}""")
        store.record(PacketLogDirection.Outbound, "third", """{"c":3}""")

        assertEquals(listOf("second", "third"), store.entries.value.map { it.eventName })
        assertEquals(listOf(PacketLogDirection.Inbound, PacketLogDirection.Outbound), store.entries.value.map { it.direction })
    }

    @Test
    fun recordRedactsSensitiveJsonFields() {
        val store = PacketLogStore()

        store.record(
            PacketLogDirection.Outbound,
            "AccountLogin",
            """{"AccountName":"alice","Password":"secret","Nested":{"NewPassword":"next"}}""",
        )

        val payload = store.entries.value.single().payloadJson
        assertTrue(payload.contains("alice"))
        assertTrue(payload.contains("<redacted>"))
        assertTrue(!payload.contains("secret"))
        assertTrue(!payload.contains("next"))
    }
}
