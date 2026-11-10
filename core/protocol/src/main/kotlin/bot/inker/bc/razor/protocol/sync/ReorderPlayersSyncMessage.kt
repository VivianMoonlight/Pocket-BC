package bot.inker.bc.razor.protocol.sync

import com.google.gson.annotations.SerializedName

data class ReorderPlayersSyncMessage(
    @SerializedName("PlayerOrder") val playerOrder: List<Int>,
)
