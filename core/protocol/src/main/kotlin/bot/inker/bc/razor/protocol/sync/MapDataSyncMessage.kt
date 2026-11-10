package bot.inker.bc.razor.protocol.sync

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class MapDataSyncMessage(
    @SerializedName("MemberNumber") val memberNumber: Int,
    @SerializedName("MapData") val mapData: JsonElement? = null,
)
