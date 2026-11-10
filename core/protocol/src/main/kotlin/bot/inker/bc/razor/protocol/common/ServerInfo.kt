package bot.inker.bc.razor.protocol.common

import com.google.gson.annotations.SerializedName

data class ServerInfo(
    @SerializedName("Time") val time: Long,
    @SerializedName("OnlinePlayers") val onlinePlayers: Int,
)
