package bot.inker.bc.razor.protocol.room

sealed class RoomJoinResult {
    data object Success : RoomJoinResult()
    data class Error(val message: String) : RoomJoinResult()
}
