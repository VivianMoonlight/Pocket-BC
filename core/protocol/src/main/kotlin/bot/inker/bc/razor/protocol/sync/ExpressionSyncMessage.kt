package bot.inker.bc.razor.protocol.sync

import com.google.gson.annotations.SerializedName

data class ExpressionSyncMessage(
    @SerializedName("MemberNumber") val memberNumber: Int,
    @SerializedName("Name") val name: String?,
    @SerializedName("Group") val group: String,
)
