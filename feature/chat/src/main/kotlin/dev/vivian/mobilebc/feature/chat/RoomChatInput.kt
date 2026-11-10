package dev.vivian.mobilebc.feature.chat

internal sealed interface RoomChatInput {
    data class Chat(val text: String) : RoomChatInput
    data class Emote(val text: String) : RoomChatInput
    data class Whisper(val targetMemberNumber: Int, val text: String) : RoomChatInput
    data class Invalid(val error: ChatComposerValidationError) : RoomChatInput
}

internal fun parseRoomChatInput(draft: String, muStylePoses: Boolean = false): RoomChatInput {
    val text = draft.trim()
    if (text.startsWith("//")) return RoomChatInput.Chat(text.drop(1))
    // BC uses a zero-width prefix to keep escaped text out of command/emote parsing.
    if (text.startsWith("\\")) return RoomChatInput.Chat("\u200B" + text.drop(1))
    if (text.startsWith("*")) return parseEmote(text)
    if (muStylePoses && text.startsWith(":") && text.length > 3) {
        return RoomChatInput.Emote(text.drop(1).trim())
    }
    if (!text.startsWith("/")) return RoomChatInput.Chat(text)

    val command = text.takeWhile { !it.isWhitespace() }.lowercase()
    val body = text.drop(command.length).trim()
    return when (command) {
        "/me" -> parseEmote(body)
        // BC /action is an Emote with one remaining '*' prefix, not a Type=Action packet.
        "/action" -> parseEmote("**$body")
        "/w", "/whisper" -> {
            val parts = body.split(Regex("\\s+"), limit = 2)
            val target = parts.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }
            val message = parts.getOrNull(1)?.trim().orEmpty()
            if (target == null || message.isEmpty()) {
                RoomChatInput.Invalid(ChatComposerValidationError.InvalidWhisperCommand)
            } else {
                RoomChatInput.Whisper(target, message)
            }
        }
        else -> RoomChatInput.Invalid(ChatComposerValidationError.UnsupportedRoomCommand)
    }
}

private fun parseEmote(text: String): RoomChatInput {
    val body = text.removePrefix("*").removeSuffix("*").trim()
    return if (body.isEmpty() || body == "*") {
        RoomChatInput.Invalid(ChatComposerValidationError.EmptyEmote)
    } else {
        RoomChatInput.Emote(body)
    }
}
