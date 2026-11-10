package bot.inker.bc.razor.protocol.account

import com.google.gson.annotations.SerializedName

data class AccountOwnershipResponse(
    @SerializedName("MemberNumber") val memberNumber: Int? = null,
    @SerializedName("Result") val result: String? = null,
    @SerializedName("Owner") val owner: String? = null,
    @SerializedName("Ownership") val ownership: Ownership? = null,
    @SerializedName("ClearOwnership") val clearOwnership: Boolean? = null,
)
