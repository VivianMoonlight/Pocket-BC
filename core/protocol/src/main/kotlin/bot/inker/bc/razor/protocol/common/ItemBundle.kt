package bot.inker.bc.razor.protocol.common

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class ItemBundle(
    @SerializedName("Group") val group: String,
    @SerializedName("Name") val name: String,
    @SerializedName("Color") val color: JsonElement? = null,
    @SerializedName("Difficulty") val difficulty: Int? = null,
    @SerializedName("Property") val property: JsonElement? = null,
    @SerializedName("Craft") val craft: JsonElement? = null,
)
