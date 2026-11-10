package bot.inker.bc.razor.protocol.account

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class AccountQueryRequest(
    @SerializedName("Query") val query: String,
)

data class AccountQueryResult(
    @SerializedName("Query") val query: String,
    @SerializedName("Result") val result: JsonElement? = null,
)
