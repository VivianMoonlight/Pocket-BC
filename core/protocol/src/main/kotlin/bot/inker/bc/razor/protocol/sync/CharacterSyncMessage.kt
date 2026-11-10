package bot.inker.bc.razor.protocol.sync

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class CharacterSyncMessage(
    @SerializedName("SourceMemberNumber") val sourceMemberNumber: Int,
    @SerializedName("Character") val character: JsonElement,
)
