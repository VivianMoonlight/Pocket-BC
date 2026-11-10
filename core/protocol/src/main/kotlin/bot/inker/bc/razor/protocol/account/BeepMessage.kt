package bot.inker.bc.razor.protocol.account

import com.google.gson.annotations.SerializedName

data class BeepMessage(
    @SerializedName("MemberNumber") val memberNumber: Int,
    @SerializedName("MemberName") val memberName: String? = null,
    @SerializedName("ChatRoomName") val chatRoomName: String? = null,
    @SerializedName("ChatRoomSpace") val chatRoomSpace: String? = null,
    @SerializedName("Private") val private: Boolean? = null,
    @SerializedName("BeepType") val beepType: String? = null,
    @SerializedName("Message") val message: String? = null,
)

data class BeepRequest(
    @SerializedName("MemberNumber") val memberNumber: Int,
    @SerializedName("BeepType") val beepType: String = "",
    @SerializedName("IsSecret") val isSecret: Boolean? = null,
    @SerializedName("Message") val message: String? = null,
    @SerializedName("MsgId") val msgId: String? = null,
)
