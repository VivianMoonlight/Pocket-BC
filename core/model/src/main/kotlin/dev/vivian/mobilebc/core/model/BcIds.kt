package dev.vivian.mobilebc.core.model

import java.util.Base64

@JvmInline
value class BcConversationId(val value: String)

@JvmInline
value class BcMessageId(val value: String)

@JvmInline
value class BcRoomName(val value: String)

fun bcRoomConversationId(roomName: String): BcConversationId {
    val encodedRoomName = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(roomName.toByteArray(Charsets.UTF_8))
    return BcConversationId("room:$encodedRoomName")
}

