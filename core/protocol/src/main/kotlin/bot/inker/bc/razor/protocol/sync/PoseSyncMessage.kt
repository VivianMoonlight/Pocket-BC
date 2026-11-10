package bot.inker.bc.razor.protocol.sync

import com.google.gson.annotations.SerializedName

data class PoseSyncMessage(
    @SerializedName("MemberNumber") val memberNumber: Int,
    @SerializedName("Pose") val pose: List<String>? = null,
)
