package dev.vivian.mobilebc.domain.bcitemproperty

sealed interface BcLockTimerChange {
    data class Settings(val removeItem: Boolean, val showTimer: Boolean? = null, val enableRandomInput: Boolean? = null) : BcLockTimerChange
    data class Adjust(val seconds: Int) : BcLockTimerChange
    data object Restart : BcLockTimerChange
    data class LimitedInput(val choice: BcTimerInputChoice, val randomPositive: Boolean = false) : BcLockTimerChange
}

enum class BcTimerInputChoice { Decrease, Random, Increase }

enum class BcTimerUnit(val seconds: Int) { Minutes(60), Hours(3600), Days(86400) }
data class BcTimerDuration(val amount: Int, val unit: BcTimerUnit) {
    val seconds: Int get() = amount * unit.seconds
}

val bcTimerDurations: List<BcTimerDuration> =
    listOf(5, 10, 15, 30, 60, -30, -15, -10, -5).map { BcTimerDuration(it, BcTimerUnit.Minutes) } +
    listOf(1, 2, 3, 4, 8, 12, 16, 24, -12, -8, -4, -3, -2, -1).map { BcTimerDuration(it, BcTimerUnit.Hours) } +
    listOf(1, 2, 3, 7, 14, 28, -14, -7, -3, -2, -1).map { BcTimerDuration(it, BcTimerUnit.Days) }

data class BcLockTimerState(
    val lockName: String,
    val deadlineMillis: Double,
    val removeItem: Boolean,
    val showTimer: Boolean,
    val enableRandomInput: Boolean,
    val usedMemberNumbers: Set<Int>,
) {
    val basic: Boolean get() = lockName == "TimerPadlock"
    fun limitedStepSeconds(baseSeconds: Int): Int? = if (lockName in setOf("OwnerTimerPadlock", "LoversTimerPadlock")) 7200
        else (baseSeconds.toLong() * 2).takeIf { it in 1..(Int.MAX_VALUE / 2).toLong() }?.toInt()

    companion object {
        fun from(propertyJson: String?, baselineJson: String? = null): BcLockTimerState? {
            fun raw(key: String) = BcItemPropertyJson.readExactPropertyValue(propertyJson, key)
                ?: BcItemPropertyJson.readExactPropertyValue(baselineJson, key)
            val lock = BcItemPropertyJson.readStringValue(raw("LockedBy")) ?: return null
            if (lock !in setOf("TimerPadlock", "OwnerTimerPadlock", "LoversTimerPadlock", "MistressTimerPadlock", "TimerPasswordPadlock")) return null
            val deadline = raw("RemoveTimer")?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
            val members = raw("MemberNumberList")?.trim()?.takeIf { it.startsWith('[') && it.endsWith(']') }
                ?.removeSurrounding("[", "]")?.split(',')?.mapNotNull { token -> token.trim().toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt() }?.toSet().orEmpty()
            return BcLockTimerState(lock, deadline, raw("RemoveItem") == "true", raw("ShowTimer") == "true", raw("EnableRandomInput") == "true", members)
        }
    }
}
