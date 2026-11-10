package bot.inker.bc.razor.protocol.chat

import com.google.gson.JsonObject

object DictionaryEntry {

    fun sourceCharacter(memberNumber: Int): JsonObject =
        JsonObject().apply { addProperty("SourceCharacter", memberNumber) }

    fun targetCharacter(memberNumber: Int, index: Int? = null): JsonObject =
        JsonObject().apply {
            addProperty("TargetCharacter", memberNumber)
            if (index != null) addProperty("Index", index)
        }

    fun focusGroup(groupName: String): JsonObject =
        JsonObject().apply { addProperty("FocusGroupName", groupName) }

    fun assetReference(
        groupName: String,
        assetName: String,
        tag: String? = null,
        craftName: String? = null
    ): JsonObject =
        JsonObject().apply {
            addProperty("GroupName", groupName)
            addProperty("AssetName", assetName)
            if (tag != null) addProperty("Tag", tag)
            if (craftName != null) addProperty("CraftName", craftName)
        }

    fun text(tag: String, text: String): JsonObject =
        JsonObject().apply {
            addProperty("Tag", tag)
            addProperty("Text", text)
        }

    fun textLookup(tag: String, textToLookUp: String): JsonObject =
        JsonObject().apply {
            addProperty("Tag", tag)
            addProperty("TextToLookUp", textToLookUp)
        }

    fun msgId(msgId: String): JsonObject =
        JsonObject().apply {
            addProperty("Tag", "MsgId")
            addProperty("MsgId", msgId)
        }

    fun replyId(replyId: String): JsonObject =
        JsonObject().apply {
            addProperty("Tag", "ReplyId")
            addProperty("ReplyId", replyId)
        }

    fun shockEvent(intensity: Int): JsonObject =
        JsonObject().apply { addProperty("ShockIntensity", intensity) }

    fun automaticEvent(): JsonObject =
        JsonObject().apply { addProperty("Automatic", true) }
}
