package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcAfcHeartLockChange
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.domain.bcitemproperty.BcAfcHeartLock
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import java.time.Instant
import java.util.UUID

internal data class RazorItemUpdateWithCompanion(val item: JsonObject, val companion: JsonObject, val companionFirst: Boolean)

/** Liko-AFC net/heartlock.js and snapshot.js, pinned at 7377a48. */
internal fun buildAfcHeartLockUpdate(
    command: BcCharacterItemUpdateCommand,
    sourceMemberNumber: Int,
    nowMillis: Long,
    lockId: String = UUID.randomUUID().toString(),
): RazorItemUpdateWithCompanion {
    require(command.targetMemberNumber != sourceMemberNumber) { "AFC companion updates require another wearer." }
    require(command.group.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) && command.name != null)
    val change = requireNotNull(command.afcHeartLockChange)
    val applying = change is BcAfcHeartLockChange.Apply
    val outgoing = if (applying) command.copy(propertyJson = BcItemPropertyJson.overlayRawProperties(
        command.propertyJson, JsonObject().apply { addProperty("HeartLockId", lockId) }.toString(),
    )) else command
    val item = buildCharacterItemUpdatePayload(outgoing)
    val content = if (applying) "HeartLockApply" else "HeartLock::Remove"
    val entry = JsonObject().apply {
        addProperty("Tag", content)
        addProperty("Target", command.targetMemberNumber)
        addProperty("Group", command.group)
        when (change) {
            is BcAfcHeartLockChange.Apply -> {
                val property = JsonParser.parseString(requireNotNull(outgoing.propertyJson)).asJsonObject
                check(property.get("LockedBy")?.asString == BcAfcHeartLock.NativeLock && property.get("LockMemberNumber")?.asInt == sourceMemberNumber)
                addProperty("Owner", sourceMemberNumber)
                addProperty("OwnerName", change.ownerName)
                addProperty("LockedAt", Instant.ofEpochMilli(nowMillis).toString())
                addProperty("AssetName", command.name)
                addProperty("LockId", lockId)
                add("Settings", JsonObject())
                add("Snapshot", JsonObject().apply {
                    addProperty("version", 2)
                    addProperty("format", "runtime")
                    addProperty("assetFamily", change.assetFamily)
                    addProperty("assetName", command.name)
                    addProperty("groupName", command.group)
                    command.colorJson?.let { add("color", JsonParser.parseString(it)) }
                    command.craftJson?.let { add("craft", JsonParser.parseString(it)) }
                    command.difficulty?.let { addProperty("difficulty", it) }
                    add("property", property.deepCopy())
                })
            }
            is BcAfcHeartLockChange.Remove -> change.lockId?.let { addProperty("LockId", it) }
        }
    }
    val companion = JsonObject().apply {
        addProperty("Type", "Hidden")
        addProperty("Content", content)
        addProperty("Target", command.targetMemberNumber)
        add("Dictionary", JsonArray().apply { add(entry) })
    }
    // Legacy Remove without identity is accepted only after the actual unlock.
    return RazorItemUpdateWithCompanion(item, companion, applying || (change as? BcAfcHeartLockChange.Remove)?.lockId != null)
}
