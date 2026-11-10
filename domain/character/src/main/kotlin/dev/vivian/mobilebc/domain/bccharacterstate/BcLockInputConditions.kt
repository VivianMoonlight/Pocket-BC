package dev.vivian.mobilebc.domain.bccharacterstate

import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty

data class BcLockInputConditions(val blind: Boolean = false, val clumsiness: Int = 0)

/** Character.GetBlindLevel (> 0) and GetClumsiness, using the current resolved appearance. */
fun BcCharacterRuleState.lockInputConditions(): BcLockInputConditions {
    var level = wornItems.sumOf { worn -> worn.effects.sumOf { effect ->
        when (effect) { "BlindTotal" -> 99; "BlindHeavy" -> 3; "BlindNormal" -> 2; "BlindLight" -> 1; else -> 0 }
    } }
    character.appearance.forEach { worn ->
        val effects = BcItemPropertyJson.readPropertyValue(worn.craftJson, "Effects")
        fun stack(key: String) = BcItemPropertyJson.readPropertyValue(effects, key)?.toIntOrNull()
            ?.takeIf { it != 0 } ?: if (worn.craftJson.readBcStringProperty("Property") == key) 1 else 0
        level += stack("Thick") - stack("Thin")
    }
    // The local eye-blindness preference is not in room snapshots; keep closed eyes impaired.
    if (item("Eyes")?.item?.propertyJson.readBcStringProperty("Expression") == "Closed" &&
        item("Eyes2")?.item?.propertyJson.readBcStringProperty("Expression") == "Closed") level++
    var clumsiness = if (canInteract()) 0 else 1
    if (item("ItemArms")?.let { it.assetRule?.isRestraint == true && "Block" in it.effects } == true) clumsiness += 2
    if (item("ItemHands")?.let { it.assetRule?.isRestraint == true && "Block" in it.effects } == true) clumsiness += 3
    return BcLockInputConditions(blind = level > 0, clumsiness = clumsiness.coerceAtMost(5))
}
