package bot.inker.bc.razor.protocol.sync

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class MemberJoinMessage(
    @SerializedName("SourceMemberNumber") val sourceMemberNumber: Int,
    @SerializedName("Character") val character: JsonElement,
    @SerializedName("WhiteListedBy") val whiteListedBy: List<Int>? = null,
    @SerializedName("BlackListedBy") val blackListedBy: List<Int>? = null,
)
