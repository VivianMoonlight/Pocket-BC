package bot.inker.bc.razor.protocol.account

import com.google.gson.annotations.SerializedName

data class AccountLovershipResponse(
    @SerializedName("MemberNumber") val memberNumber: Int? = null,
    @SerializedName("Result") val result: String? = null,
    @SerializedName("Lovership") val lovership: List<Lovership>? = null,
)
