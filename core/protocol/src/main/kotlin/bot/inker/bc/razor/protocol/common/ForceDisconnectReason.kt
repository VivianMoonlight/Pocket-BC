package bot.inker.bc.razor.protocol.common

enum class ForceDisconnectReason(val wireValue: String) {
    RATE_LIMITED("ErrorRateLimited"),
    DUPLICATED_LOGIN("ErrorDuplicatedLogin"),
    UNKNOWN("");

    companion object {
        fun fromWireValue(value: String): ForceDisconnectReason {
            return entries.find { it.wireValue == value } ?: UNKNOWN
        }
    }
}
