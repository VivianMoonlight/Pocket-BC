package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.domain.bccharacterstate.isLoverOf
import dev.vivian.mobilebc.domain.bccharacterstate.isOwnerOf
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerState
import dev.vivian.mobilebc.domain.bcitemproperty.readBcIntProperty
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty

data class BcLockTimerAccess(val editSettings: Boolean, val adjust: Boolean, val restart: Boolean, val limitedInput: Boolean, val showTime: Boolean)

fun BcPermissionContext.lockTimerState(item: BcCharacterItem): BcLockTimerState? = BcLockTimerState.from(item.propertyJson,
    item.propertyJson.readBcStringProperty("LockedBy")?.let { rules.extendedItem("ItemMisc", it)?.baselinePropertyJson })

fun BcPermissionContext.lockTimerAccess(item: BcCharacterItem, state: BcLockTimerState): BcLockTimerAccess {
    val creator = item.propertyJson.readBcIntProperty("LockMemberNumber") == actor.memberNumber
    val owner = actor.isOwnerOf(target)
    val ownerOrLover = owner || actor.isLoverOf(target)
    val mistress = itemPrivateState?.ruleLog?.any {
        val expiry = it.expiresAtMillis
        it.name == "ClubMistress" && it.group == "Management" && (expiry == null || serverTimeMillis?.let { now -> expiry >= now } == true)
    } == true
    val settings = when (state.lockName) {
        "OwnerTimerPadlock" -> owner
        "LoversTimerPadlock" -> ownerOrLover
        else -> creator
    }
    val adjust = when (state.lockName) {
        "TimerPadlock" -> false
        "MistressTimerPadlock" -> creator || mistress
        else -> settings
    }
    return BcLockTimerAccess(settings, adjust, state.basic, !state.basic && !adjust && state.enableRandomInput && actor.memberNumber !in state.usedMemberNumbers,
        state.basic || state.showTimer || state.lockName == "OwnerTimerPadlock" && owner || state.lockName == "LoversTimerPadlock" && ownerOrLover)
}

fun BcPermissionContext.lockTimerMaximumSeconds(defaultSeconds: Int): Long = when (target.onlineSharedSettingsJson.readBcStringProperty("LockTimerLimit").takeUnless { target.isSelf || target.type != "online" }) {
    "LockTimerLimitDay" -> 86400L
    "LockTimerLimitWeek" -> 7 * 86400L
    "LockTimerLimitMonth" -> 30 * 86400L
    "LockTimerLimitYear" -> 365 * 86400L
    "LockTimerLimitDecade" -> 10 * 365 * 86400L
    else -> defaultSeconds.toLong()
}
