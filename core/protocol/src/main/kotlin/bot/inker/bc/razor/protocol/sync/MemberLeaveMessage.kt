package bot.inker.bc.razor.protocol.sync

import com.google.gson.annotations.SerializedName

data class MemberLeaveMessage(
    @SerializedName("SourceMemberNumber") val sourceMemberNumber: Int,
)
