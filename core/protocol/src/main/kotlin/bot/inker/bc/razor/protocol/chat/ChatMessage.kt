package bot.inker.bc.razor.protocol.chat

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class OutgoingChatMessage(
    @SerializedName("Content") val content: String,
    @SerializedName("Type") val type: ChatMessageType,
    @SerializedName("Target") val target: Int? = null,
    @SerializedName("Dictionary") val dictionary: List<JsonElement>? = null,
)

data class ReceivedChatMessage(
    @SerializedName("Sender") val sender: Int,
    @SerializedName("Content") val content: String,
    @SerializedName("Type") val type: ChatMessageType,
    @SerializedName("Target") val target: Int? = null,
    @SerializedName("Dictionary") val dictionary: JsonElement? = null,
)
