package bot.inker.bc.razor.protocol.room

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

data class ChatRoomSearchResult(
    @SerializedName("Name") val name: String,
    @SerializedName("Description") val description: String? = null,
    @SerializedName("MemberCount") val memberCount: Int? = null,
    @SerializedName("MemberLimit") val memberLimit: Int? = null,
    @SerializedName("Creator") val creator: String? = null,
    @SerializedName("CreatorMemberNumber") val creatorMemberNumber: Int? = null,
    @SerializedName("Game") val game: String? = null,
    @SerializedName("Friends") val friends: List<FriendEntry>? = null,
    @SerializedName("BlockCategory") val blockCategory: List<String>? = null,
    @SerializedName("Language") val language: String? = null,
    @SerializedName("Space") val space: String? = null,
    @SerializedName("Locked") val locked: Boolean? = null,
    @SerializedName("MapType") val mapType: String? = null,
    @SerializedName("Order") val order: Double? = null,
    @SerializedName("CanJoin")
    @JsonAdapter(RoomCanJoinAdapter::class)
    val canJoin: Boolean? = null,
)

internal class RoomCanJoinAdapter : TypeAdapter<Boolean?>() {
    override fun read(reader: JsonReader): Boolean? =
        if (reader.peek() == JsonToken.BOOLEAN) {
            reader.nextBoolean()
        } else {
            reader.skipValue()
            null
        }

    override fun write(writer: JsonWriter, value: Boolean?) {
        if (value == null) writer.nullValue() else writer.value(value)
    }
}

data class FriendEntry(
    @SerializedName("MemberName") val memberName: String,
    @SerializedName("MemberNumber") val memberNumber: Int,
    @SerializedName("Type") val type: String? = null,
)
