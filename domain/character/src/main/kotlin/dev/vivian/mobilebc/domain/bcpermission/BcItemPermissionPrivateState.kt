package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcItemPermissionPrivateState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.estimatedServerTimeMillis

/** LogQuery includes an entry at its exact expiry. Unknown time keeps a deposit in force. */
fun BcPermissionContext.keysDeposited(): Boolean? = itemPrivateState?.ruleLog?.any { log ->
    val expiry = log.expiresAtMillis
    log.name == "KeyDeposit" && log.group == "Cell" && (expiry == null || serverTimeMillis?.let { expiry >= it } != false)
}

fun BcPermissionContext.withItemPrivateState(snapshot: BcSessionSnapshot?): BcPermissionContext {
    val wardrobe = snapshot?.wardrobeState
    val log = snapshot?.selfRuleLog
    val state = if (wardrobe != null && log != null && snapshot.account?.memberNumber == actor.memberNumber &&
        wardrobe.memberNumber == actor.memberNumber && wardrobe.selfRuleLogSessionId == snapshot.selfRuleLogSessionId) {
        BcItemPermissionPrivateState(wardrobe, log)
    } else null
    return copy(itemPrivateState = state, serverTimeAvailable = snapshot?.serverTimeAnchor?.epochMillis?.let { it >= 0 } == true,
        serverTimeMillis = snapshot?.estimatedServerTimeMillis())
}
