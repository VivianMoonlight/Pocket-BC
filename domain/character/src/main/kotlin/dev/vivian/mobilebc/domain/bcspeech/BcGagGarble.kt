package dev.vivian.mobilebc.domain.bcspeech

import java.util.Locale

internal object BcGagGarble {
    fun transform(
        text: String,
        intensity: Double,
        translationLanguage: String,
        ignoreOoc: Boolean = false,
    ): String {
        if (intensity == 0.0) return text
        val oocRanges = if (ignoreOoc) emptyList() else BcSpeechTransform.oocRanges(text)
        return buildString {
            // BC iterates UTF-16 code units, including each half of a surrogate pair.
            text.forEachIndexed { index, character ->
                val original = character.toString()
                val lowercase = original.lowercase(Locale.ROOT)
                val isCaps = original.uppercase(Locale.ROOT) == original
                val transformed = when {
                    oocRanges.any { index in it } -> original
                    BcSpeechTransform.isPunctuationOrSpace(lowercase) -> lowercase
                    isChinese(lowercase, translationLanguage) -> chineseGarble(lowercase, intensity)
                    intensity >= 1 && intensity < 10 && accentedCharacters.contains(lowercase) ->
                        stripAccent(lowercase, intensity, isCaps)
                    "ьъ".contains(lowercase) -> ""
                    intensity >= 20 -> if ("zqjxkvbywgpfucdlhr".contains(lowercase)) " " else capitalize("m", isCaps)
                    intensity >= 16 -> if ("zqjxkvbywgpf".contains(lowercase)) " " else capitalize("m", isCaps)
                    intensity >= 12 -> if ("zqjxkv".contains(lowercase)) " " else capitalize("m", isCaps)
                    intensity >= 10 -> capitalize("m", isCaps)
                    // At levels 1 and 2 BC deliberately emits a lowercase replacement for T.
                    intensity >= 1 && intensity < 3 && lowercase == "t" -> if (intensity >= 2) "e" else "h"
                    else -> capitalize(mapCharacter(lowercase, intensity), isCaps)
                }
                append(transformed)
            }
        }
    }

    internal fun isChinese(character: String, translationLanguage: String): Boolean {
        if (translationLanguage != "CN") return false
        var index = 0
        while (index < character.length) {
            val codePoint = character.codePointAt(index)
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    internal fun chineseGarble(character: String, intensity: Double): String {
        if (intensity == 0.0) return character
        val chance = when (intensity) {
            1.0 -> 0.8
            2.0 -> 0.55
            3.0 -> 0.5
            4.0 -> 0.45
            5.0 -> 0.4
            6.0 -> 0.35
            7.0 -> 0.3
            8.0 -> 0.25
            9.0, 10.0, 11.0, 12.0 -> 0.2
            else -> 0.1
        }
        val seed = RandomSeeding(character + chance.toString())
        val decision = Sfc32(seed.next(), seed.next(), seed.next(), seed.next())
        return if (decision.next() >= chance) chineseSounds[(decision.next() * chineseSounds.size).toInt()] else character
    }

    private fun mapCharacter(character: String, intensity: Double): String {
        val rules = when {
            intensity >= 8 -> level8
            intensity >= 7 -> level7
            intensity >= 6 -> level6
            intensity >= 5 -> level5
            intensity >= 4 -> level4
            intensity >= 3 -> level3
            intensity >= 2 -> level2
            intensity >= 1 -> level1
            else -> return ""
        }
        val rule = rules.firstOrNull { (letters, _) -> letters.contains(character) } ?: return ""
        return rule.second ?: character
    }

    private fun stripAccent(character: String, intensity: Double, isCaps: Boolean): String = when {
        "áàãâ".contains(character) -> capitalize("a", isCaps)
        "éêèë".contains(character) -> capitalize("e", isCaps)
        "íîìï".contains(character) -> capitalize("i", isCaps)
        "óôòõ".contains(character) -> capitalize("o", isCaps)
        "úûùü".contains(character) -> "u"
        character == "ñ" -> capitalize(if (intensity >= 5) "m" else "n", isCaps)
        character == "ç" -> capitalize(if (intensity >= 5) "h" else "s", isCaps)
        else -> capitalize(character, isCaps)
    }

    private fun capitalize(character: String, isCaps: Boolean): String =
        if (isCaps) character.uppercase(Locale.ROOT) else character

    private class RandomSeeding(seed: String) {
        private var state = 1779033703 xor seed.length

        init {
            seed.forEach { character ->
                state = (state xor character.code) * 3432918353L.toInt()
                state = (state shl 13) or (state ushr 19)
            }
        }

        fun next(): Int {
            state = (state xor (state ushr 16)) * 2246822507L.toInt()
            state = (state xor (state ushr 13)) * 3266489909L.toInt()
            state = state xor (state ushr 16)
            return state
        }
    }

    private class Sfc32(private var a: Int, private var b: Int, private var c: Int, private var d: Int) {
        fun next(): Double {
            var value = a + b
            a = b xor (b ushr 9)
            b = c + (c shl 3)
            c = (c shl 21) or (c ushr 11)
            d += 1
            value += d
            c += value
            return (value.toLong() and 0xffffffffL).toDouble() / 4294967296.0
        }
    }

    private val accentedCharacters = "áàãâéèêíìîõóòôúùûñç"
    private val chineseSounds = listOf("啊", "恩", "咕", "唔", "哈", "嗷", "呜")
    private val cyrillicCharacters = "абвгґдеєжзиіїйклмнопрстуфхцчшщюяёы"

    private val level8 = listOf(
        "aeiouy" to "e", "jklr" to "a", "szh" to "h", "dfgnmwtcqxpv" to "m", "b" to null,
        "аеиоуюляіїёыйкнм" to "е", "рбвпщсжзтцчгґ" to "а", cyrillicCharacters to null,
    )
    private val level7 = listOf(
        "aeiouyt" to "e", "cqx" to "k", "jklrw" to "a", "szh" to "h", "bpv" to "f", "dfgnm" to "m",
        "аеиоуюляіїёый" to "е", "рбвпщсжз" to "а", "ктц" to "к", "нм" to "м", "чгґ" to "ф",
        cyrillicCharacters to null,
    )
    private val level6 = listOf(
        "eiouyt" to "e", "cqxk" to "k", "jlrwa" to "a", "szh" to "h", "bpv" to "f", "dfgm" to "m", "n" to null,
        "аеиоуюляіїёы" to "e", "бвп" to "у", "жз" to "г", "фкгґ" to "х", "тц" to "ч",
        cyrillicCharacters to null,
    )
    private val level5 = listOf(
        "vbct" to "e", "qkx" to "k", "wyjlr" to "a", "sz" to "h", "df" to "m", "p" to "f", "g" to "n",
        "aeioumnh" to null, "бвп" to "у", "щс" to "ш", "ё" to "е", "ї" to "і", "жз" to "г",
        "фкґ" to "х", "тц" to "ч", cyrillicCharacters to null,
    )
    private val level4 = listOf(
        "vbct" to "e", "qkx" to "k", "wyjlr" to "a", "sz" to "s", "d" to "m", "p" to "f", "g" to "h",
        "aeioumnhf" to null, "бвп" to "у", "щс" to "ш", "ё" to "е", "ї" to "і", "жз" to "г",
        "фк" to "х", "тц" to "ч", cyrillicCharacters to null,
    )
    private val level3 = listOf(
        "ct" to "e", "qkx" to "k", "jlr" to "a", "s" to "z", "z" to "s", "f" to "h", "dmg" to "m",
        "bhnvwpaeiouy" to null, "бв" to "у", "щс" to "ш", "ё" to "е", "ї" to "і", "жз" to "г",
        "фк" to "х", "тц" to "ч", cyrillicCharacters to null,
    )
    private val level2 = listOf(
        "t" to "e", "cqkx" to "k", "jlr" to "a", "dmg" to "m", "bhnvwpaeiouyfsz" to null,
        "бв" to "у", "щс" to "ш", "жз" to "г", "фк" to "х", "тц" to "ч", cyrillicCharacters to null,
    )
    private val level1 = listOf(
        "t" to "h", "cqkx" to "k", "dmg" to "m", "bhnvwpaeiouyfszjlr" to null,
        "бв" to "у", "щс" to "ш", "жз" to "г", "фк" to "х", cyrillicCharacters to null,
    )
}
