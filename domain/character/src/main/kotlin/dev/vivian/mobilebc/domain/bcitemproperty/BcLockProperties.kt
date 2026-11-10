package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule

/** InventoryDoesItemAllowLock after TypedItem/ModularItem generates AllowLockType. */
fun BcAssetRule.allowsLock(propertyJson: String?, extended: BcExtendedItemRule?): Boolean {
    val options = when {
        extended == null -> return allowLock
        extended.typedOptions.isNotEmpty() -> mapOf(extended.recordKey to extended.typedOptions.map { it.index to (it.allowLock ?: allowLock) })
        extended.modules.isNotEmpty() -> extended.modules.associate { module ->
            module.name to module.options.map { it.index to (it.allowLock ?: allowLock) }
        }
        else -> return allowLock
    }
    val all = options.values.flatten()
    if (all.all { it.second }) return true
    if (all.none { it.second }) return false
    val typeRecord = BcItemPropertyJson.readPropertyValue(propertyJson, "TypeRecord")
    if (typeRecord?.trim()?.startsWith("{") != true) return allowLock
    return BcItemPropertyJson.readTypeRecord(propertyJson).any { (key, value) ->
        options[key]?.any { it.first == value && it.second } == true
    }
}

/** Native lock Init uses the active version's NoArch BaselineProperty. */
internal fun nativeLockBaseline(lockName: String, extended: BcExtendedItemRule?): String? = when (lockName) {
    "MetalPadlock", "IntricatePadlock", "OwnerPadlock", "LoversPadlock", "FamilyPadlock",
    "MistressPadlock", "PandoraPadlock", "ExclusivePadlock" -> "{}"
    "HighSecurityPadlock", "CombinationPadlock", "PasswordPadlock", "SafewordPadlock",
    "TimerPadlock", "OwnerTimerPadlock", "LoversTimerPadlock", "MistressTimerPadlock",
    "TimerPasswordPadlock" -> extended?.let { it.baselinePropertyJson ?: "{}" }
    else -> null
}
