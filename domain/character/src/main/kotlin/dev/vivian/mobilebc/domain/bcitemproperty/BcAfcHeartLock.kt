package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bccharacterstate.isLoverOf
import dev.vivian.mobilebc.domain.bccharacterstate.isOwnerOf
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule

/** Liko-AFC 7377a48: features/heartlock/permissions.js and compat/r132-heartlock.js. */
object BcAfcHeartLock {
    const val Name = "Heart Padlock"
    const val NativeLock = "HighSecurityPadlock"
    const val PickSeed = "8,3,5,10,4,2,6,7,1,9,0,11"
    val asset = BcAssetRule("ItemMisc", Name, isLock = true, value = 0.0)

    fun canApply(actor: BcCharacterSnapshot, wearer: BcCharacterSnapshot): Boolean =
        actor.memberNumber != wearer.memberNumber && relationAllowed(actor, wearer)

    fun canUnlock(actor: BcCharacterSnapshot, wearer: BcCharacterSnapshot, item: BcCharacterItem): Boolean =
        identity(wearer, item)?.let { it.owner == actor.memberNumber || relationAllowed(actor, wearer) } == true

    private fun relationAllowed(actor: BcCharacterSnapshot, wearer: BcCharacterSnapshot): Boolean {
        val afc = BcItemPropertyJson.readPropertyValue(wearer.onlineSharedSettingsJson, "AFC") ?: return false
        val permissions = BcItemPropertyJson.readPropertyValue(afc, "lockPerms")
        if (permissions.readBcBooleanProperty("enableOwnerLock") == true && actor.isOwnerOf(wearer)) return true
        if (permissions.readBcBooleanProperty("enableAFCLock") != true) return false
        if (actor.isLoverOf(wearer)) return true
        return BcItemPropertyJson.readObjectArray(BcItemPropertyJson.readPropertyValue(afc, "lovers"))
            .any { it.memberNumber("memberNumber") == actor.memberNumber }
    }

    data class Identity(val owner: Int?, val lockId: String?)

    fun identity(wearer: BcCharacterSnapshot, item: BcCharacterItem): Identity? {
        val property = item.propertyJson
        if (property.readBcStringProperty("LockedBy") != NativeLock) return null
        val owner = property.memberNumber("LockMemberNumber")
        val id = property.readBcStringProperty("HeartLockId")?.takeIf { it.isNotBlank() }
        if (property.readBcStringProperty("Name") == Name || id != null) return Identity(owner, id)
        // R132 may strip custom markers. Only a matching server-shared summary can restore identity.
        val store = BcItemPropertyJson.readPropertyValue(wearer.onlineSharedSettingsJson, "AFC_HeartLock") ?: return null
        val member = store.memberNumber("memberNumber")
        if (member != null && member != wearer.memberNumber) return null
        val locks = BcItemPropertyJson.readPropertyValue(store, "padlocks")
        val summary = BcItemPropertyJson.readPropertyValue(locks, item.group)
        val summaryId = summary.readBcStringProperty("lockId")?.takeIf { it.isNotBlank() } ?: return null
        return Identity(owner, summaryId).takeIf {
            owner != null && owner > 0 && summary.memberNumber("owner") == owner &&
                summary.readBcStringProperty("assetName") == item.name
        }
    }

    fun unlockedProperty(property: String?): String? = BcItemPropertyJson.withoutProperty(
        BcItemPropertyJson.withoutProperty(BcItemPropertyJson.withoutLock(property), "Name"), "HeartLockId",
    )

    private fun String?.memberNumber(key: String): Int? =
        (readBcIntProperty(key) ?: readBcStringProperty(key)?.toIntOrNull())?.takeIf { it > 0 }
}
