package dev.vivian.mobilebc.domain.bcspeech

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BcSpeechTransformTest {
    @Test
    fun matchesExecutedNativeR129SenderFixtures() {
        val rows = checkNotNull(javaClass.getResourceAsStream("/bc-fixtures/SpeechTransform-R129.tsv"))
            .bufferedReader().use { reader -> reader.readLines().filterNot { it.startsWith('#') || it.isEmpty() } }
        assertEquals(229, rows.size)
        rows.forEachIndexed { index, row ->
            val values = row.split('\t')
            val operation = values[0]
            val input = decode(values[7])
            val expected = decode(values[8])
            val state = BcSpeechTransformState(
                babyTalk = values[2].toBooleanStrict(),
                gagIntensity = values[3].toDouble(),
                stutterIntensity = values[4].toDouble(),
                translationLanguage = values[1],
                autoCloseOoc = values[5].toBooleanStrict(),
                noSpeechGarble = values[6].toBooleanStrict(),
            )
            val label = "Native fixture ${index + 1}: $operation / ${values[1]} / $input"
            when (operation) {
                "baby" -> assertEquals(label, expected, BcSpeechTransform.babyTalk(input, state.translationLanguage))
                "stutter" -> assertEquals(label, expected, BcSpeechTransform.stutter(input, state.stutterIntensity))
                "close" -> assertEquals(label, expected, BcSpeechTransform.closeOoc(input))
                "prepare" -> {
                    val result = BcSpeechTransform.prepareChat(input, state)
                    assertEquals(label, expected, result.text)
                    assertEquals(label, values[9].split(',').filter { it.isNotEmpty() }, result.effects)
                    assertEquals(label, values[10].takeUnless { it == "-" }?.let(::decode), result.original)
                }
                else -> error("Unsupported native fixture operation $operation")
            }
        }
    }

    @Test
    fun noSpeechGarbleKeepsTransformedWireTextAndOnlyAttachesChangedOriginal() {
        val state = state().copy(babyTalk = true, noSpeechGarble = true)
        val result = BcSpeechTransform.prepareChat("Still (OOC", state)
        assertEquals("shthiww (OOC)", result.text)
        assertEquals(listOf("babyTalk"), result.effects)
        assertEquals("Still (OOC)", result.original)
        assertNull(BcSpeechTransform.prepareChat("(unchanged)", state).original)
        assertNull(BcSpeechTransform.prepareChat("(new OOC", state.copy(babyTalk = false)).original)
        assertFalse(BcSpeechTransform.prepareChat("Still", state.copy(noSpeechGarble = false)).text == "Still")
    }

    @Test
    fun disabledOocClosureDoesNotAppendAClosingParenthesis() {
        val result = BcSpeechTransform.prepareChat("hello (unfinished", state().copy(autoCloseOoc = false))
        assertEquals("hello (unfinished", result.text)
        assertNull(result.original)
    }

    @Test
    fun nonFiniteNormalizedIntensitiesAreRejected() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                BcSpeechTransform.prepareChat("hello", state().copy(gagIntensity = invalid))
            }
            assertThrows(IllegalArgumentException::class.java) {
                BcSpeechTransform.prepareChat("hello", state().copy(stutterIntensity = invalid))
            }
        }
    }

    @Test
    fun nativeStutterUsesOnlyAsciiSpaceAsWordDelimiter() {
        assertEquals("o-one-two\nthree f-four", BcSpeechTransform.stutter("one-two\nthree four", 10.0))
        assertEquals("o-one\tsecond", BcSpeechTransform.stutter("one\tsecond", 10.0))
    }

    @Test
    fun injectedStutterRandomCanExerciseTheNativeThreshold() {
        assertEquals("o-one two t-three", BcSpeechTransform.stutter("one two three", 1.0) { seed ->
            if (seed == "one two three".length + 2) 0.9 else 0.0
        })
    }

    private fun state() = BcSpeechTransformState(
        babyTalk = false,
        gagIntensity = 0.0,
        stutterIntensity = 0.0,
        translationLanguage = "EN",
        autoCloseOoc = true,
        noSpeechGarble = false,
    )

    private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
}
