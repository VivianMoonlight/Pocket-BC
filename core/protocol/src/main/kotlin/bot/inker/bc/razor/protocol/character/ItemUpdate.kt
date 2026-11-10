package bot.inker.bc.razor.protocol.character

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class ItemUpdate(
    @SerializedName("Target") val target: Int,
    @SerializedName("Group") val group: String,
    @SerializedName("Name") val name: String? = null,
    @SerializedName("Color") val color: JsonElement? = null,
    @SerializedName("Difficulty") val difficulty: Int? = null,
    @SerializedName("Property") val property: JsonElement? = null,
    @SerializedName("Craft") val craft: JsonElement? = null,
)
