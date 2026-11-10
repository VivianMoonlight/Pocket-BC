package bot.inker.bc.razor.protocol.account

import com.google.gson.annotations.SerializedName

data class Lovership(
    @SerializedName("MemberNumber") val memberNumber: Int? = null,
    @SerializedName("Name") val name: String? = null,
    @SerializedName("Stage") val stage: Int? = null,
    @SerializedName("Start") val start: Long? = null,
)
