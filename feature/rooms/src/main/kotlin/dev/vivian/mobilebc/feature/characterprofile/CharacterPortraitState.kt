package dev.vivian.mobilebc.feature.characterprofile

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot

data class CharacterPortraitState(
    val version: String,
    val ownerMemberNumber: Int,
    val accountGeneration: Long,
    val roomPresenceEpoch: Long,
    val character: BcCharacterSnapshot,
    val snapshotUpdatedAtMillis: Long? = null,
)

internal fun BcSessionSnapshot.characterPortrait(memberNumber: Int): CharacterPortraitState? {
    if (!isVerifiedOnline || currentRoom == null) return null
    val owner = account ?: return null
    val version = bcVersion ?: return null
    val character = roomCharacters.firstOrNull { it.memberNumber == memberNumber } ?: return null
    if (character.appearance.isEmpty()) return null
    return CharacterPortraitState(version, owner.memberNumber, relationships.accountGeneration, roomPresenceEpoch, character)
}

internal fun BcSessionSnapshot.cachedCharacterPortrait(profile: dev.vivian.mobilebc.core.model.BcContactProfile): CharacterPortraitState? {
    val cached = profile.portrait ?: return null
    val owner = account ?: return null
    val character = BcCharacterSnapshot(
        id = profile.memberNumber.toString(), memberNumber = profile.memberNumber,
        name = profile.name, displayName = profile.nickname ?: profile.name, isSelf = owner.memberNumber == profile.memberNumber,
        assetFamily = cached.assetFamily, activePose = cached.activePose, appearance = cached.appearance,
    )
    return CharacterPortraitState(cached.version, owner.memberNumber, relationships.accountGeneration,
        roomPresenceEpoch, character, cached.updatedAtMillis)
}
