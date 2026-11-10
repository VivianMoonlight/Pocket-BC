package bot.inker.bc.razor.protocol.room

import com.google.gson.annotations.SerializedName

data class ChatRoomCustomData(
    @SerializedName("ImageURL") val imageUrl: String? = null,
    @SerializedName("MusicURL") val musicUrl: String? = null,
)
