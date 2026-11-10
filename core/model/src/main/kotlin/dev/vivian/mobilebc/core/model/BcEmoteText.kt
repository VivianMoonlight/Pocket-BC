package dev.vivian.mobilebc.core.model

/** BC's leading '*' marks an emote that omits the sender's name. */
fun bcEmoteDisplayText(text: String, senderName: String): String =
    if (text.startsWith("*")) {
        text.drop(1)
    } else {
        val separator = if (text.startsWith("'") || text.startsWith(",") || senderName.isBlank()) "" else " "
        senderName + separator + text
    }
