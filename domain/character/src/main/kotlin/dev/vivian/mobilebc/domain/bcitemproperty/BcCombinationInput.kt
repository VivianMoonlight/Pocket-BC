package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.domain.bccharacterstate.BcLockInputConditions
import kotlin.random.Random

/** One native combination-lock inspection. The blind offset is stable until the dialog closes. */
class BcCombinationInput(
    val conditions: BcLockInputConditions,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private val blindOffset = if (conditions.blind) (random() * 10).toInt() else 0
    private var lastValue = ""

    // Native old/new inputs share CombinationPadlockCombinationLastValue.
    fun edit(value: String): String {
        if (!conditions.blind && conditions.clumsiness == 0) return value.also { lastValue = it }
        val prefix = lastValue.zip(value).takeWhile { (a, b) -> a == b }.size
        val previousReverse = lastValue.substring(prefix).reversed()
        val nextReverse = value.substring(prefix).reversed()
        val suffix = previousReverse.zip(nextReverse).takeWhile { (a, b) -> a == b }.size
        val inserted = value.substring(prefix, value.length - suffix).map { digit ->
            if (digit !in '0'..'9') digit else {
                var offset = blindOffset
                if (conditions.clumsiness > 0) offset += (random() * 2 * conditions.clumsiness).toInt() - conditions.clumsiness
                ('0'.code + (digit.digitToInt() + offset + 10) % 10).toChar()
            }
        }.joinToString("")
        return (value.take(prefix) + inserted + value.takeLast(suffix)).also { lastValue = it }
    }
}
