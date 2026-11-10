package bot.inker.bc.razor.protocol.account

import com.google.gson.annotations.SerializedName

data class Ownership(
    @SerializedName("MemberNumber") val memberNumber: Int,
    @SerializedName("Name") val name: String? = null,
    @SerializedName("Stage") val stage: Int? = null,
    @SerializedName("Start") val start: Long? = null,
    @SerializedName("Notes") val notes: String? = null,
)
