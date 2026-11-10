package bot.inker.bc.razor.protocol.chat

enum class ChatMessageType(val wireValue: String) {
    CHAT("Chat"),
    WHISPER("Whisper"),
    EMOTE("Emote"),
    ACTION("Action"),
    ACTIVITY("Activity"),
    HIDDEN("Hidden"),
    SERVER_MESSAGE("ServerMessage"),
    STATUS("Status"),
    LOCAL_MESSAGE("LocalMessage");

    companion object {
        fun fromWireValue(value: String): ChatMessageType {
            return entries.find { it.wireValue == value } ?: CHAT
        }
    }
}
