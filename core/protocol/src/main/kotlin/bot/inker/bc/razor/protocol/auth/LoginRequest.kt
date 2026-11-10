package bot.inker.bc.razor.protocol.auth

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    @SerializedName("AccountName") val accountName: String,
    @SerializedName("Password") val password: String,
)
