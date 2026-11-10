package dev.vivian.mobilebc.domain.bccharacterstate

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcitemproperty.containsBcTokenOrFalse

fun BcCharacterSnapshot.ownerNumber(): Int? = ownerMemberNumber ?: ownership?.memberNumber

fun BcCharacterSnapshot.isOwnedByMemberNumber(ownerMemberNumber: Int?): Boolean =
    ownerMemberNumber != null && ownerNumber() == ownerMemberNumber

fun BcCharacterSnapshot.isOwnedByCharacter(owner: BcCharacterSnapshot): Boolean =
    isOwnedByMemberNumber(owner.memberNumber)

fun BcCharacterSnapshot.isOwnerOf(target: BcCharacterSnapshot): Boolean =
    target.isOwnedByCharacter(this)

fun BcCharacterSnapshot.isLoverOf(target: BcCharacterSnapshot): Boolean =
    target.lovership.any { it.memberNumber == memberNumber }

/** Mirrors BC Character.IsSiblingOfCharacter: two characters share the same owner. */
fun BcCharacterSnapshot.isSiblingOf(target: BcCharacterSnapshot): Boolean =
    memberNumber != target.memberNumber && isOwnedByMemberNumber(target.ownerNumber())

/**
 * Mirrors BC Character.IsInFamilyOfMemberNumber for the actor/target pair available locally:
 * the actor is family of the target if they share an owner, actor owns target, or target owns actor.
 * Lovers are intentionally not family in BC's family-lock semantics.
 */
fun BcCharacterSnapshot.isFamilyOf(target: BcCharacterSnapshot): Boolean =
    memberNumber != target.memberNumber &&
        (target.isSiblingOf(this) || target.isOwnedByCharacter(this) || isOwnedByCharacter(target))

fun BcCharacterSnapshot.isWhitelistedBy(target: BcCharacterSnapshot): Boolean =
    memberNumber in target.whiteList

fun BcCharacterSnapshot.canUseLimitedTargetItem(target: BcCharacterSnapshot): Boolean {
    if (memberNumber == target.memberNumber) return true
    if (isOwnerOf(target)) return true
    if (isLoverOf(target)) return true

    // Mirrors InventoryCheckLimitedPermission: whitelist bypass only applies while the target's
    // interaction setting still permits whitelist-based access (< OwnerLoversWhitelistOnly).
    val level = target.allowedInteractions ?: 0
    return level < ALLOW_OWNER_LOVERS_WHITELIST_ONLY && isWhitelistedBy(target)
}

private const val ALLOW_OWNER_LOVERS_WHITELIST_ONLY = 3

fun BcCharacterSnapshot.hasOwnerRuleFlag(flag: String): Boolean = listOfNotNull(
    onlineSharedSettingsJson,
    gameJson,
    inventoryData,
).any { it.containsBcTokenOrFalse(flag) }

fun BcCharacterSnapshot.reputationValue(type: String): Int = reputation
    .firstOrNull { it.type.equals(type, ignoreCase = true) }
    ?.value
    ?: 0

fun BcCharacterSnapshot.hasBodyPart(part: String): Boolean = body.has(part)
