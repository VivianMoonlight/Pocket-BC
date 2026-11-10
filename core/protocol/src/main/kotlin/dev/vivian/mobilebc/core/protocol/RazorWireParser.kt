package dev.vivian.mobilebc.core.protocol

import bot.inker.bc.razor.protocol.account.AccountQueryResult
import bot.inker.bc.razor.protocol.account.AccountLovershipResponse
import bot.inker.bc.razor.protocol.account.AccountOwnershipResponse
import bot.inker.bc.razor.protocol.account.BeepMessage
import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.auth.LoginResult
import bot.inker.bc.razor.protocol.chat.ChatMessageType
import bot.inker.bc.razor.protocol.chat.ReceivedChatMessage
import bot.inker.bc.razor.protocol.common.ForceDisconnectReason
import bot.inker.bc.razor.protocol.common.ServerInfo
import bot.inker.bc.razor.protocol.room.ChatRoomSearchResult
import bot.inker.bc.razor.protocol.room.RoomJoinResult
import bot.inker.bc.razor.protocol.room.RoomManagementResponse
import bot.inker.bc.razor.protocol.sync.ArousalSyncMessage
import bot.inker.bc.razor.protocol.sync.CharacterSyncMessage
import bot.inker.bc.razor.protocol.sync.ExpressionSyncMessage
import bot.inker.bc.razor.protocol.sync.ItemSyncMessage
import bot.inker.bc.razor.protocol.sync.MapDataSyncMessage
import bot.inker.bc.razor.protocol.sync.MemberJoinMessage
import bot.inker.bc.razor.protocol.sync.MemberLeaveMessage
import bot.inker.bc.razor.protocol.sync.PoseSyncMessage
import bot.inker.bc.razor.protocol.sync.ReorderPlayersSyncMessage
import bot.inker.bc.razor.protocol.sync.RoomPropertiesSyncMessage
import bot.inker.bc.razor.protocol.sync.RoomSyncMessage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.lang.reflect.Type

fun createRazorWireGson(): Gson =
    GsonBuilder()
        .registerTypeAdapter(ChatMessageType::class.java, WireValueAdapter(ChatMessageType::fromWireValue) { wireValue })
        .registerTypeAdapter(ForceDisconnectReason::class.java, WireValueAdapter(ForceDisconnectReason::fromWireValue) { wireValue })
        .create()

object RazorWireParser {
    private val gson: Gson = createRazorWireGson()

    fun parse(eventName: String, payloadJson: String): RazorWireEvent =
        when (eventName) {
            "ServerInfo" -> RazorWireEvent.ServerInfo(gson.fromJson(payloadJson, ServerInfo::class.java))
            "LoginResponse" -> parseLoginResponse(payloadJson)
            "LoginQueue" -> RazorWireEvent.LoginQueue(payloadJson.trim().toIntOrNull())
            "ForceDisconnect" -> RazorWireEvent.ForceDisconnect(gson.fromJson(payloadJson, ForceDisconnectReason::class.java))
            "AccountQueryResult" -> RazorWireEvent.AccountQueryResult(gson.fromJson(payloadJson, AccountQueryResult::class.java))
            "AccountBeep" -> RazorWireEvent.AccountBeep(gson.fromJson(payloadJson, BeepMessage::class.java))
            "AccountOwnership" -> RazorWireEvent.AccountOwnership(
                gson.fromJson(payloadJson, AccountOwnershipResponse::class.java)
            )
            "AccountLovership" -> RazorWireEvent.AccountLovership(
                gson.fromJson(payloadJson, AccountLovershipResponse::class.java)
            )
            "ChatRoomSearchResult" -> RazorWireEvent.ChatRoomSearchResult(
                gson.fromJson(payloadJson, Array<ChatRoomSearchResult>::class.java).orEmpty()
                    .filterNotNull().filter { !it.name.isNullOrBlank() }
            )
            "ChatRoomSearchResponse" -> RazorWireEvent.ChatRoomSearchResponse(parseRoomJoinResult(payloadJson))
            "ChatRoomCreateResponse" -> RazorWireEvent.ChatRoomCreateResponse(parseRoomManagementResponse(payloadJson))
            "ChatRoomUpdateResponse" -> RazorWireEvent.ChatRoomUpdateResponse(parseRoomManagementResponse(payloadJson))
            "ChatRoomSync" -> parseRoomSettings(eventName, payloadJson, RoomSyncMessage::class.java) { value, raw ->
                RazorWireEvent.ChatRoomSync(value, rawPayloadJson = raw)
            }
            "ChatRoomSyncMemberJoin" -> RazorWireEvent.MemberJoin(gson.fromJson(payloadJson, MemberJoinMessage::class.java))
            "ChatRoomSyncMemberLeave" -> RazorWireEvent.MemberLeave(gson.fromJson(payloadJson, MemberLeaveMessage::class.java))
            "ChatRoomSyncRoomProperties" -> parseRoomSettings(eventName, payloadJson, RoomPropertiesSyncMessage::class.java) { value, raw ->
                RazorWireEvent.RoomProperties(value, rawPayloadJson = raw)
            }
            "ChatRoomSyncCharacter",
            "ChatRoomSyncSingle",
            -> RazorWireEvent.CharacterSync(gson.fromJson(payloadJson, CharacterSyncMessage::class.java))
            "ChatRoomSyncExpression" -> RazorWireEvent.ExpressionSync(gson.fromJson(payloadJson, ExpressionSyncMessage::class.java))
            "ChatRoomSyncPose" -> RazorWireEvent.PoseSync(gson.fromJson(payloadJson, PoseSyncMessage::class.java))
            "ChatRoomSyncArousal" -> RazorWireEvent.ArousalSync(gson.fromJson(payloadJson, ArousalSyncMessage::class.java))
            "ChatRoomSyncItem" -> RazorWireEvent.ItemSync(gson.fromJson(payloadJson, ItemSyncMessage::class.java))
            "ChatRoomSyncMapData" -> RazorWireEvent.MapDataSync(
                gson.fromJson(payloadJson, MapDataSyncMessage::class.java),
                rawPayloadJson = payloadJson,
            )
            "ChatRoomSyncReorderPlayers" -> RazorWireEvent.ReorderPlayers(gson.fromJson(payloadJson, ReorderPlayersSyncMessage::class.java))
            "ChatRoomMessage",
            "ChatRoomChat",
            -> RazorWireEvent.ChatRoomMessage(gson.fromJson(payloadJson, ReceivedChatMessage::class.java))
            "ChatRoomAllowItem" -> parseAllowItemResult(payloadJson)
            else -> RazorWireEvent.Unknown(eventName = eventName, payloadJson = payloadJson)
        }

    private fun parseLoginResponse(payloadJson: String): RazorWireEvent.LoginResponse {
        val element = gson.fromJson(payloadJson, JsonElement::class.java)
        return if (element.isJsonPrimitive) {
            RazorWireEvent.LoginResponse(LoginResult.Error(element.asString))
        } else {
            val account = gson.fromJson(element, ServerAccountData::class.java)
            if (account == null || account.name.isNullOrBlank() || account.memberNumber <= 0) {
                RazorWireEvent.LoginResponse(LoginResult.Error("Invalid account data"))
            } else {
                RazorWireEvent.LoginResponse(LoginResult.Success(account))
            }
        }
    }

    private fun parseRoomJoinResult(payloadJson: String): RoomJoinResult {
        val value = gson.fromJson(payloadJson, JsonElement::class.java).asString
        return if (value == "JoinedRoom") RoomJoinResult.Success else RoomJoinResult.Error(value)
    }

    private fun parseRoomManagementResponse(payloadJson: String): RoomManagementResponse {
        val token = runCatching {
            JsonReader(StringReader(payloadJson)).use { reader ->
                reader.strictness = Strictness.STRICT
                check(reader.peek() == JsonToken.STRING)
                reader.nextString().also { check(reader.peek() == JsonToken.END_DOCUMENT) }
            }
        }.getOrNull()
        return if (token != null) {
            RoomManagementResponse.Valid(token)
        } else {
            RoomManagementResponse.Invalid(payloadJson)
        }
    }

    private fun <T> parseRoomSettings(
        eventName: String,
        payloadJson: String,
        type: Class<T>,
        toEvent: (T, String) -> RazorWireEvent,
    ): RazorWireEvent =
        runCatching { toEvent(requireNotNull(gson.fromJson(payloadJson, type)), payloadJson) }
            .getOrElse { RazorWireEvent.InvalidRoomSettings(eventName, payloadJson) }

    private fun parseAllowItemResult(payloadJson: String): RazorWireEvent.AllowItem {
        val value = gson.fromJson(payloadJson, JsonElement::class.java).asJsonObject
        return RazorWireEvent.AllowItem(
            memberNumber = value.get("MemberNumber").asInt,
            allowItem = value.get("AllowItem").asBoolean,
        )
    }
}

sealed interface RazorWireEvent {
    data class ServerInfo(val value: bot.inker.bc.razor.protocol.common.ServerInfo) : RazorWireEvent
    data class LoginResponse(val value: LoginResult) : RazorWireEvent
    data class LoginQueue(val position: Int?) : RazorWireEvent
    data class ForceDisconnect(val value: ForceDisconnectReason) : RazorWireEvent
    data class AccountQueryResult(val value: bot.inker.bc.razor.protocol.account.AccountQueryResult) : RazorWireEvent
    data class AccountBeep(val value: BeepMessage) : RazorWireEvent
    data class AccountOwnership(val value: AccountOwnershipResponse) : RazorWireEvent
    data class AccountLovership(val value: AccountLovershipResponse) : RazorWireEvent
    data class ChatRoomSearchResult(val value: List<bot.inker.bc.razor.protocol.room.ChatRoomSearchResult>) : RazorWireEvent
    data class ChatRoomSearchResponse(val value: RoomJoinResult) : RazorWireEvent
    data class ChatRoomCreateResponse(val value: RoomManagementResponse) : RazorWireEvent
    data class ChatRoomUpdateResponse(val value: RoomManagementResponse) : RazorWireEvent
    data class ChatRoomSync(val value: RoomSyncMessage, val rawPayloadJson: String? = null) : RazorWireEvent
    data class MemberJoin(val value: MemberJoinMessage) : RazorWireEvent
    data class MemberLeave(val value: MemberLeaveMessage) : RazorWireEvent
    data class RoomProperties(val value: RoomPropertiesSyncMessage, val rawPayloadJson: String? = null) : RazorWireEvent
    data class InvalidRoomSettings(val eventName: String, val rawPayloadJson: String) : RazorWireEvent
    data class CharacterSync(val value: CharacterSyncMessage) : RazorWireEvent
    data class ExpressionSync(val value: ExpressionSyncMessage) : RazorWireEvent
    data class PoseSync(val value: PoseSyncMessage) : RazorWireEvent
    data class ArousalSync(val value: ArousalSyncMessage) : RazorWireEvent
    data class ItemSync(val value: ItemSyncMessage) : RazorWireEvent
    data class MapDataSync(val value: MapDataSyncMessage, val rawPayloadJson: String? = null) : RazorWireEvent
    data class ReorderPlayers(val value: ReorderPlayersSyncMessage) : RazorWireEvent
    data class ChatRoomMessage(val value: ReceivedChatMessage) : RazorWireEvent
    data class AllowItem(val memberNumber: Int, val allowItem: Boolean) : RazorWireEvent
    data class Unknown(val eventName: String, val payloadJson: String) : RazorWireEvent
}

private class WireValueAdapter<T>(
    private val fromWireValue: (String) -> T,
    private val toWireValue: T.() -> String,
) : JsonSerializer<T>, JsonDeserializer<T> {
    override fun serialize(src: T, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
        JsonPrimitive(src.toWireValue())

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): T =
        fromWireValue(json.asString)
}
