package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bccharacterstate.ownerNumber
import java.math.BigDecimal
import java.math.BigInteger

/** CommonConvertStringToArray uses JavaScript Number, including exponent and radix forms. */
fun readBcKeyholderNumbers(value: String): List<Double> = value.split(',').mapNotNull { token ->
    if (token.isEmpty()) return@mapNotNull null
    val trimmed = token.trimBcNumber()
    when {
        trimmed.isEmpty() -> 0.0
        trimmed in listOf("Infinity", "+Infinity") -> Double.POSITIVE_INFINITY
        trimmed == "-Infinity" -> Double.NEGATIVE_INFINITY
        trimmed.matches(Regex("0[xX][0-9a-fA-F]+")) -> BigInteger(trimmed.substring(2), 16).toDouble()
        trimmed.matches(Regex("0[bB][01]+")) -> BigInteger(trimmed.substring(2), 2).toDouble()
        trimmed.matches(Regex("0[oO][0-7]+")) -> BigInteger(trimmed.substring(2), 8).toDouble()
        trimmed.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) -> trimmed.toDoubleOrNull()
        else -> null
    }
}

/** The native initialization hook repairs an empty list from the lock creator. */
fun highSecurityKeyholderText(propertyJson: String?): String {
    val raw = BcItemPropertyJson.readExactPropertyValue(propertyJson, "MemberNumberListKeys")
    val text = BcItemPropertyJson.readStringValue(raw)
    if (!text.isNullOrEmpty()) return text
    val missing = raw == null || raw in listOf("null", "false") || text == "" || raw.toDoubleOrNull() == 0.0
    return if (missing) propertyJson.readBcIntProperty("LockMemberNumber")?.takeIf { it != 0 }?.toString().orEmpty() else ""
}

fun BcLockConfiguration.UpdateKeyholders.keyholderText(actor: BcCharacterSnapshot, canKeepSelf: Boolean): String {
    val members = readBcKeyholderNumbers(memberNumbers.trimBcNumber()).map { if (it == 0.0) 0.0 else it }.toMutableSet()
    if (!canKeepSelf) members.remove(actor.memberNumber.toDouble())
    if (appendOwner) actor.ownerNumber()?.let { members.add(it.toDouble()) }
    if (appendLovers) actor.lovership.mapNotNull { it.memberNumber }.forEach { members.add(it.toDouble()) }
    if (appendWhitelist) actor.whiteList.forEach { members.add(it.toDouble()) }
    return members.joinToString(",") { number ->
        when {
            number == Double.POSITIVE_INFINITY -> "Infinity"
            number == Double.NEGATIVE_INFINITY -> "-Infinity"
            number == 0.0 -> "0"
            kotlin.math.abs(number) >= 1e-6 && kotlin.math.abs(number) < 1e21 -> BigDecimal.valueOf(number).stripTrailingZeros().toPlainString()
            else -> number.toString().lowercase().replace(Regex("\\.0e"), "e").replace(Regex("e(?=[0-9])"), "e+")
        }
    }
}

private fun String.trimBcNumber(): String = trim { it in '\u0009'..'\u000D' || it in '\u2000'..'\u200A' ||
    it in listOf(' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF') }
