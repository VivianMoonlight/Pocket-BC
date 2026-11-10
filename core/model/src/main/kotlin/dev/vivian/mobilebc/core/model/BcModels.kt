package dev.vivian.mobilebc.core.model

import java.time.Instant

const val DefaultBcServerUrl = "https://bondage-club-server.herokuapp.com/"

data class BcServerConfig(
    val serverUrl: String = DefaultBcServerUrl,
    val version: String,
) {
    val referer: String =
        "https://www.bondageprojects.elementfx.com/${version.normalizedBcVersion()}/BondageClub/"
}

data class BcAccount(
    val memberNumber: Int,
    val displayName: String,
    val accountName: String,
)

data class BcConversation(
    val id: BcConversationId,
    val type: ConversationType,
    val title: String,
    val subtitle: String,
    val unreadCount: Int = 0,
    val muted: Boolean = false,
    val pinned: Boolean = false,
    val updatedAt: Instant? = null,
    val availability: Set<ConversationAvailability> = emptySet(),
    val onlineRoomName: String? = null,
    val hasMessages: Boolean = false,
    val avatarUrl: String? = null,
)

enum class ConversationAvailability {
    SameRoom,
    OnlineFriend,
}

enum class ConversationType {
    CurrentRoom,
    Direct,
    System,
}

data class BcSender(
    val memberNumber: Int?,
    val displayName: String,
    val isSelf: Boolean = false,
    val labelColor: String? = null,
    val avatarUrl: String? = null,
)

data class BcMessage(
    val id: BcMessageId,
    val conversationId: BcConversationId,
    val address: BcMessageAddress = BcMessageAddress.CurrentRoom,
    val sender: BcSender,
    val kind: BcMessageKind,
    val transport: BcMessageTransport = BcMessageTransport.RoomChat,
    val text: String,
    val timestamp: Instant,
    val delivery: DeliveryState = DeliveryState.Sent,
    val visibility: BcMessageVisibility = BcMessageVisibility.Visible,
    val notificationPolicy: BcNotificationPolicy = BcNotificationPolicy.None,
    val targetMemberNumber: Int? = null,
    val resolution: BcMessageResolution = BcMessageResolution.Plain,
    val raw: BcRawMessage? = null,
    val replyToId: String? = null,
)

enum class BcMessageKind {
    Chat,
    Emote,
    Action,
    Activity,
    Whisper,
    Beep,
    System,
    Error,
}

sealed interface BcMessageAddress {
    data object CurrentRoom : BcMessageAddress
    data class Direct(val memberNumber: Int) : BcMessageAddress
    data object System : BcMessageAddress
    data class Protocol(val sourceEvent: String) : BcMessageAddress
}

enum class BcMessageTransport {
    RoomChat,
    RoomWhisper,
    AccountBeep,
    LocalSystem,
    ProtocolEvent,
}

enum class BcMessageVisibility {
    Visible,
    HiddenProtocol,
    DebugOnly,
    LocalOnly,
}

fun BcMessage.isTimelineVisible(): Boolean =
    visibility == BcMessageVisibility.Visible || visibility == BcMessageVisibility.LocalOnly

enum class BcNotificationPolicy {
    None,
    RoomMessage,
    DirectMessage,
    SystemAlert,
}

data class BcRawMessage(
    val sourceEvent: String,
    val wireType: String? = null,
    val beepType: String? = null,
    val payloadJson: String? = null,
    val dictionaryJson: String? = null,
    val roomName: String? = null,
)

sealed interface BcMessageResolution {
    data object Plain : BcMessageResolution
    data class NeedsTranslation(val key: String) : BcMessageResolution
    data class Resolved(val key: String, val resolvedText: String) : BcMessageResolution
    data class Failed(val key: String, val fallback: String) : BcMessageResolution
}

data class BcRawChatMessage(
    val senderMemberNumber: Int,
    val senderName: String?,
    val content: String,
    val wireType: String,
    val targetMemberNumber: Int? = null,
    val targetName: String? = null,
    val clientMsgId: String? = null,
    val dictionaryJson: String? = null,
    val payloadJson: String? = null,
    val activityName: String? = null,
    val activityTargetGroup: String? = null,
)

data class BcRawAccountBeep(
    val memberNumber: Int,
    val memberName: String?,
    val chatRoomName: String?,
    val chatRoomSpace: String?,
    val private: Boolean?,
    val beepType: String?,
    val message: String?,
    val clientMsgId: String? = null,
    val payloadJson: String? = null,
)

enum class DeliveryState {
    Sending,
    Sent,
    Failed,
}

data class BcRoom(
    val name: BcRoomName,
    val description: String,
    val memberCount: Int,
    val memberLimit: Int,
    val isLocked: Boolean,
    val language: String?,
    val mapType: BcRoomMapType = BcRoomMapType.ChatRoom,
    val blockCategories: Set<String> = emptySet(),
    val adminSettings: BcRoomAdministration? = null,
    val space: String? = null,
    val banMemberNumbers: List<Int>? = null,
    val game: String? = null,
)

data class BcRoomSummary(
    val name: BcRoomName,
    val description: String,
    val memberCount: Int,
    val memberLimit: Int,
    val isLocked: Boolean,
    val language: String?,
    val creatorName: String?,
    val creatorMemberNumber: Int? = null,
    val game: String? = null,
    val mapType: BcRoomMapType = BcRoomMapType.ChatRoom,
    val friends: List<BcRoomFriend> = emptyList(),
    val canJoin: Boolean? = null,
)

data class BcRoomSearchFilter(
    val query: String = "",
    val space: String = "X",
    val language: String? = null,
    val includeFullRooms: Boolean = false,
    val includeLockedRooms: Boolean = false,
    val searchDescription: Boolean = true,
    val roomType: BcRoomTypeFilter = BcRoomTypeFilter.All,
)

data class BcRoomFriend(
    val memberNumber: Int,
    val name: String,
    val relationshipType: String? = null,
)

enum class BcRoomMapType(val wireValue: String, val displayName: String) {
    ChatRoom("Never", "Chat room"),
    Hybrid("Hybrid", "Map + chat"),
    Map("Always", "Map"),
    Unknown("", "Unknown"),
}

enum class BcRoomTypeFilter(val mapType: BcRoomMapType?) {
    All(null),
    ChatRoom(BcRoomMapType.ChatRoom),
    Hybrid(BcRoomMapType.Hybrid),
    Map(BcRoomMapType.Map),
}

data class BcMember(
    val memberNumber: Int,
    val name: String,
    val isSelf: Boolean,
    val statusText: String? = null,
    val role: RoomRole? = null,
    val roomMemberPresenceEpoch: Long? = null,
)

data class BcCharacterSnapshot(
    val id: String,
    val memberNumber: Int,
    val name: String,
    val displayName: String,
    val isSelf: Boolean,
    val type: String = "online",
    val assetFamily: String = "Female3DCG",
    val accountName: String? = null,
    val nickname: String? = null,
    val title: String? = null,
    val labelColor: String? = null,
    val avatarUrl: String? = null,
    val signature: String? = null,
    val description: String? = null,
    val creation: Long = 0L,
    val activePose: List<String> = emptyList(),
    val poseState: BcCharacterPoseState = BcCharacterPoseState(),
    val pronouns: String = "SheHer",
    val appearance: List<BcCharacterItem> = emptyList(),
    val body: BcCharacterBodyState = BcCharacterBodyState(),
    val owner: String? = null,
    val ownerName: String? = null,
    val ownerMemberNumber: Int? = null,
    val lover: String? = null,
    val loverNames: List<String> = emptyList(),
    val ownership: BcCharacterOwnership? = null,
    val lovership: List<BcCharacterLovership> = emptyList(),
    val reputation: List<BcCharacterReputation> = emptyList(),
    val difficulty: BcCharacterDifficulty? = null,
    val allowedInteractions: Int? = null,
    val whiteList: List<Int> = emptyList(),
    val blackList: List<Int> = emptyList(),
    val arousal: BcCharacterArousal? = null,
    val arousalSettingsJson: String? = null,
    val onlineSharedSettingsJson: String? = null,
    val gameJson: String? = null,
    val craftingJson: String? = null,
    val inventoryData: String? = null,
    val blockItemsJson: String? = null,
    val limitedItemsJson: String? = null,
    val favoriteItemsJson: String? = null,
    val hiddenItemsJson: String? = null,
    val characterMapDataJson: String? = null,
    val rawDataJson: String? = null,
)

data class BcCharacterItem(
    val group: String,
    val name: String,
    val colorJson: String? = null,
    val difficulty: Int? = null,
    val propertyJson: String? = null,
    val craftJson: String? = null,
)

/**
 * Structural body state derived from the character appearance.
 *
 * BC checks these through InventoryIsItemInList on concrete body groups:
 * - HasVagina: Pussy in Pussy1/Pussy2/Pussy3
 * - HasPenis: Pussy == Penis
 * - HasBreasts: BodyUpper in XLarge/Large/Normal/Small
 * - HasFlatChest: BodyUpper in FlatSmall/FlatMedium
 *
 * The default matches the standard Female3DCG base body used by existing tests; real room
 * snapshots should set this from appearance via fromAppearance(...).
 */
data class BcCharacterBodyState(
    val hasVagina: Boolean = true,
    val hasPenis: Boolean = false,
    val hasBreasts: Boolean = true,
    val hasFlatChest: Boolean = false,
) {
    fun has(part: String): Boolean = when (part) {
        "vulva", "vagina" -> hasVagina
        "penis" -> hasPenis
        "breasts" -> hasBreasts
        "flatChest" -> hasFlatChest
        else -> false
    }

    companion object {
        fun fromAppearance(
            appearance: List<BcCharacterItem>,
            fallback: BcCharacterBodyState = BcCharacterBodyState(),
        ): BcCharacterBodyState {
            val pussy = appearance.firstOrNull { it.group.equals("Pussy", ignoreCase = true) }?.name
            val bodyUpper = appearance.firstOrNull { it.group.equals("BodyUpper", ignoreCase = true) }?.name

            return BcCharacterBodyState(
                hasVagina = if (pussy != null) pussy in setOf("Pussy1", "Pussy2", "Pussy3") else fallback.hasVagina,
                hasPenis = if (pussy != null) pussy == "Penis" else fallback.hasPenis,
                hasBreasts = if (bodyUpper != null) bodyUpper in setOf("XLarge", "Large", "Normal", "Small") else fallback.hasBreasts,
                hasFlatChest = if (bodyUpper != null) bodyUpper in setOf("FlatSmall", "FlatMedium") else fallback.hasFlatChest,
            )
        }
    }
}


/**
 * Structural BC pose mapping derived from ActivePose and, in domain/bc-character-state, worn item
 * SetPose / AllowActivePose restrictions.
 *
 * BC keeps ActivePose as a flat string list for the server payload, then converts it to category
 * mappings through PoseToMapping.Scalar/Array. Character.IsSuspended does not test whether the
 * flat list merely contains a substring; it checks PoseMapping.BodyAddon == "Suspension".
 */
data class BcCharacterPoseState(
    val bodyLower: String? = "BaseLower",
    val bodyUpper: String? = "BaseUpper",
    val bodyFull: String? = null,
    val bodyAddon: String? = null,
    val bodyHands: String? = null,
    val rawMapping: Map<String, String> = emptyMap(),
) {
    fun poseInCategory(category: String): String? = when (category) {
        "BodyLower" -> bodyLower
        "BodyUpper" -> bodyUpper
        "BodyFull" -> bodyFull
        "BodyAddon" -> bodyAddon
        "BodyHands" -> bodyHands
        else -> rawMapping[category]
    }

    fun containsPose(pose: String): Boolean = listOfNotNull(
        bodyLower,
        bodyUpper,
        bodyFull,
        bodyAddon,
        bodyHands,
    ).any { it.equals(pose, ignoreCase = true) } || rawMapping.values.any { it.equals(pose, ignoreCase = true) }

    fun toPoseList(): List<String> = buildList {
        bodyUpper?.let(::add)
        bodyLower?.let(::add)
        bodyFull?.let(::add)
        bodyAddon?.let(::add)
        bodyHands?.let(::add)
        rawMapping.values.forEach { pose ->
            if (none { it.equals(pose, ignoreCase = true) }) add(pose)
        }
    }

    companion object {
        fun fromActivePose(activePose: List<String>): BcCharacterPoseState = fromMapping(
            mapping = activePose.toScalarPoseMapping(),
            fallback = BcCharacterPoseState(),
        )

        fun fromMapping(
            mapping: Map<String, String>,
            fallback: BcCharacterPoseState = BcCharacterPoseState(),
        ): BcCharacterPoseState {
            val bodyFull = mapping["BodyFull"]
            val bodyUpper = if (bodyFull != null) null else mapping["BodyUpper"] ?: fallback.bodyUpper ?: "BaseUpper"
            val bodyLower = if (bodyFull != null) null else mapping["BodyLower"] ?: fallback.bodyLower ?: "BaseLower"
            val raw = mapping.filterKeys { it !in KNOWN_CATEGORIES }
            return BcCharacterPoseState(
                bodyLower = bodyLower,
                bodyUpper = bodyUpper,
                bodyFull = bodyFull,
                bodyAddon = mapping["BodyAddon"],
                bodyHands = mapping["BodyHands"],
                rawMapping = raw,
            )
        }

        fun categoryOf(pose: String): String? = when (pose) {
            "BaseLower", "Kneel", "KneelingSpread", "LegsClosed", "LegsOpen", "Spread" -> "BodyLower"
            "BaseUpper", "Yoked", "OverTheHead", "BackBoxTie", "BackElbowTouch", "BackCuffs" -> "BodyUpper"
            "Hogtied", "AllFours" -> "BodyFull"
            "Suspension" -> "BodyAddon"
            "TapedHands" -> "BodyHands"
            else -> null
        }

        private val KNOWN_CATEGORIES = setOf("BodyLower", "BodyUpper", "BodyFull", "BodyAddon", "BodyHands")
    }
}

fun List<String>.toScalarPoseMapping(): Map<String, String> = buildMap {
    this@toScalarPoseMapping.forEach { pose ->
        BcCharacterPoseState.categoryOf(pose)?.let { category -> put(category, pose) }
    }
}

fun List<String>.toArrayPoseMapping(): Map<String, List<String>> = buildMap {
    this@toArrayPoseMapping.forEach { pose ->
        BcCharacterPoseState.categoryOf(pose)?.let { category ->
            put(category, get(category).orEmpty() + pose)
        }
    }
}

data class BcCharacterOwnership(
    val name: String,
    val memberNumber: Int,
    val notes: String? = null,
    val stage: Int? = null,
    val start: Long? = null,
)

data class BcCharacterLovership(
    val name: String,
    val memberNumber: Int? = null,
    val stage: Int? = null,
    val start: Long? = null,
)

data class BcCharacterReputation(
    val type: String,
    val value: Int,
)

data class BcCharacterDifficulty(
    val level: Int,
    val lastChange: Long? = null,
)

data class BcCharacterArousal(
    val orgasmTimer: Double? = null,
    val orgasmCount: Int? = null,
    val progress: Double? = null,
    val progressTimer: Double? = null,
)

data class BcProfileRelation(
    val name: String,
    val memberNumber: Int? = null,
    val stage: Int? = null,
    val startEpochMillis: Long? = null,
)
data class BcProfilePortrait(
    val version: String,
    val assetFamily: String,
    val activePose: List<String>,
    val appearance: List<BcCharacterItem>,
    val updatedAtMillis: Long,
)
fun BcCharacterSnapshot.profileOwner(): BcProfileRelation? =
    (ownership?.name?.takeIf(String::isNotBlank) ?: ownerName)?.let {
        BcProfileRelation(
            name = it.removePrefix("NPC-"),
            memberNumber = (ownership?.memberNumber ?: ownerMemberNumber)?.takeIf { number -> number > 0 },
            stage = ownership?.stage,
            startEpochMillis = ownership?.start,
        )
    }
fun BcCharacterSnapshot.profileLovers(): List<BcProfileRelation> =
    if (lovership.isNotEmpty()) lovership.map {
        BcProfileRelation(
            name = it.name.removePrefix("NPC-"),
            memberNumber = it.memberNumber?.takeIf { number -> number > 0 },
            stage = it.stage,
            startEpochMillis = it.start,
        )
    } else loverNames.map { BcProfileRelation(it) }

data class BcContactProfile(
    val memberNumber: Int,
    val name: String,
    val labelColor: String? = null,
    val avatarUrl: String? = null,
    val signature: String? = null,
    val accountName: String? = null,
    val characterName: String? = null,
    val nickname: String? = null,
    val title: String? = null,
    val description: String? = null,
    val pronouns: String? = null,
    val statusText: String? = null,
    val role: RoomRole? = null,
    val ownerRelation: BcProfileRelation? = null,
    val loverRelations: List<BcProfileRelation>? = null,
    val portrait: BcProfilePortrait? = null,
    val ownerName: String? = null,
    val loverNames: List<String> = emptyList(),
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val snapshotUpdatedAtMillis: Long? = null,
    val lianUpdatedAtMillis: Long? = null,
)

enum class RoomRole {
    Owner,
    Admin,
    Member,
}
