package bot.inker.bc.razor.protocol.room

import com.google.gson.annotations.SerializedName

data class ChatRoomSearchRequest(
    @SerializedName("Query") val query: String = "",
    @SerializedName("Space") val space: String = "",
    @SerializedName("Game") val game: String = "",
    @SerializedName("FullRooms") val fullRooms: Boolean = false,
    @SerializedName("Language") val language: String = "",
    @SerializedName("ShowLocked") val showLocked: Boolean = true,
    @SerializedName("SearchDescs") val searchDescs: Boolean = false,
    @SerializedName("MapTypes") val mapTypes: List<String>? = null,
    @SerializedName("Ignore") val ignore: List<String>? = null,
)
