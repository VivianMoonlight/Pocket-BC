package bot.inker.bc.razor.protocol.room

sealed interface RoomManagementResponse {
    data class Valid(val token: String) : RoomManagementResponse
    data class Invalid(val payloadJson: String) : RoomManagementResponse
}
