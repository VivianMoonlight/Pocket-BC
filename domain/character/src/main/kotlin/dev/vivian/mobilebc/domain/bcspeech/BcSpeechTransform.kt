package dev.vivian.mobilebc.domain.bcspeech

import java.util.Locale
import kotlin.math.floor
import kotlin.math.sin

/** Inputs after BC's character effects, crafting and arousal rules have been resolved. */
data class BcSpeechTransformState(
    val babyTalk: Boolean,
    val gagIntensity: Double,
    val stutterIntensity: Double,
    val translationLanguage: String,
    val autoCloseOoc: Boolean,
    val noSpeechGarble: Boolean,
)

data class BcSpeechTransformResult(
    val text: String,
    val effects: List<String>,
    val original: String?,
)

/** R129 ChatRoomGenerateChatRoomChatMessage and SpeechTransformProcess sender behavior. */
object BcSpeechTransform {
    fun prepareChat(text: String, state: BcSpeechTransformState): BcSpeechTransformResult {
        require(state.gagIntensity.isFinite() && state.stutterIntensity.isFinite())
        val original = if (state.autoCloseOoc) closeOoc(text) else text
        var transformed = original
        val effects = mutableListOf<String>()
        if (state.babyTalk) {
            effects += "babyTalk"
            transformed = babyTalk(transformed, state.translationLanguage)
        }
        if (state.gagIntensity > 0) {
            effects += "gagGarble"
            transformed = gagGarble(transformed, state.gagIntensity, state.translationLanguage)
        }
        if (state.stutterIntensity > 0) {
            effects += "stutter"
            transformed = stutter(transformed, state.stutterIntensity)
        }
        return BcSpeechTransformResult(
            text = transformed,
            effects = effects,
            original = original.takeIf { state.noSpeechGarble && transformed != original },
        )
    }

    fun closeOoc(text: String): String {
        val lastRange = oocRanges(text).lastOrNull() ?: return text
        return if (lastRange.last == text.lastIndex && lastRange.first != lastRange.last && text.last() != ')') {
            "$text)"
        } else {
            text
        }
    }

    fun babyTalk(text: String, translationLanguage: String): String {
        val ranges = oocRanges(text)
        return buildString {
            text.forEachIndexed { index, original ->
                if (ranges.any { index in it }) {
                    append(original)
                    return@forEachIndexed
                }
                val character = original.toString().lowercase(Locale.ROOT)
                when (character) {
                    "k", "l" -> append('w')
                    "s" -> append("sh")
                    "t" -> append("th")
                    else -> when {
                        isPunctuationOrSpace(character) || character.any { it in 'a'..'z' } -> append(character)
                        BcGagGarble.isChinese(character, translationLanguage) -> append(BcGagGarble.chineseGarble(character, 1.0))
                    }
                }
            }
        }
    }

    fun gagGarble(text: String, intensity: Double, translationLanguage: String, ignoreOoc: Boolean = false): String =
        BcGagGarble.transform(text, intensity, translationLanguage, ignoreOoc)

    fun stutter(
        text: String,
        intensity: Double,
        randomAtSeed: (Int) -> Double = ::nativeStutterRandom,
    ): String {
        var transformed = text
        var inWord = 1
        var seed = text.length
        val ranges = oocRanges(text)
        var index = 0
        // Native inserts into the string while keeping the original range indexes.
        while (index < transformed.length) {
            val character = transformed[index].toString().lowercase(Locale.ROOT)
            val inOoc = ranges.any { index in it }
            if (!inOoc && inWord >= 0 && character.any { it == '[' || it in 'a'..'z' || it in 'а'..'я' || it == 'ё' }) {
                val random = randomAtSeed(seed++)
                require(random.isFinite() && random >= 0 && random < 1)
                if (inWord == 1 || floor(random * 10) + intensity >= 10) {
                    transformed = transformed.substring(0, index) + transformed[index] + "-" + transformed.substring(index)
                    index += 2
                }
                inWord = -1
            }
            if (character == " ") inWord = 0
            index++
        }
        return transformed
    }

    internal fun oocRanges(text: String): List<IntRange> = buildList {
        var end = -1
        while (true) {
            val start = text.indexOf('(', end + 1)
            if (start < 0) break
            end = text.indexOf(')', start).takeIf { it >= 0 } ?: text.lastIndex
            add(start..end)
        }
    }

    internal fun isPunctuationOrSpace(character: String): Boolean = character.codePoints().anyMatch { codePoint ->
        when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.SPACE_SEPARATOR.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            -> true
            else -> false
        }
    }

    private fun nativeStutterRandom(seed: Int): Double {
        val value = sin(seed.toDouble()) * 10_000
        return value - floor(value)
    }
}
