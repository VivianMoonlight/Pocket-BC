package dev.vivian.mobilebc.core.protocol

import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.auth.LoginResult
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcPrivateChatPreferencesProtocolTest {
    @Test
    fun loginResponseReadsEverySupportedDifficultyLevel() {
        (0..5).forEach { level ->
            val account = parseAccount(""""Difficulty":{"Level":$level}""")

            assertEquals("Level=$level", level, account.difficulty?.level)
        }
    }

    @Test
    fun invalidDifficultyLevelsDefaultToRegularWithoutCoercion() {
        listOf(
            "{}",
            """{"Level":"0"}""",
            """{"Level":"5"}""",
            """{"Level":true}""",
            """{"Level":false}""",
            """{"Level":0.5}""",
            """{"Level":-1}""",
            """{"Level":6}""",
            """{"Level":9223372036854775808}""",
            """{"Level":null}""",
            """{"Level":{}}""",
            """{"Level":[]}""",
            """{"level":0}""",
        ).forEach { difficultyJson ->
            val account = parseAccount(""""Difficulty":$difficultyJson""")

            assertEquals(difficultyJson, 1, account.difficulty?.level)
        }
    }

    @Test
    fun exactWholeNumberRepresentationsRemainValidDifficultyLevels() {
        listOf("0.0" to 0, "2.000" to 2, "5e0" to 5,
            "4.999999999999999999" to 5, "1e-999" to 0).forEach { (levelJson, expected) ->
            val account = parseAccount(""""Difficulty":{"Level":$levelJson}""")

            assertEquals(levelJson, expected, account.difficulty?.level)
        }
    }

    @Test
    fun missingAndNullDifficultyRemainAbsent() {
        assertNull(parseAccount().difficulty)
        assertNull(parseAccount(""""Difficulty":null""").difficulty)
    }

    @Test
    fun nonObjectDifficultyUsesRegularDefaultAndHasNoChangeTime() {
        listOf("0", "true", "\"0\"", "[]", "[{\"Level\":0}]").forEach { difficultyJson ->
            val account = parseAccount(""""Difficulty":$difficultyJson""")

            assertEquals(difficultyJson, 1, account.difficulty?.level)
            assertNull(difficultyJson, account.difficulty?.lastChange)
        }
    }

    @Test
    fun lastChangePreservesExactNonNegativeLongValues() {
        listOf(
            "0" to 0L,
            "1700000000123" to 1700000000123L,
            "9007199254740993" to 9007199254740993L,
            "9223372036854775807" to Long.MAX_VALUE,
            "1.000" to 1L,
            "9.007199254740993e15" to 9007199254740993L,
        ).forEach { (lastChangeJson, expected) ->
            val account = parseAccount(""""Difficulty":{"Level":0,"LastChange":$lastChangeJson}""")

            assertEquals(lastChangeJson, expected, account.difficulty?.lastChange)
            assertEquals(lastChangeJson, 0, account.difficulty?.level)
        }
    }

    @Test
    fun invalidLastChangeDoesNotCoerceOrInvalidateTheDifficultyLevel() {
        listOf(
            "-1",
            "-9223372036854775808",
            "1.5",
            "9007199254740993.1",
            "9223372036854775808",
            "1e100",
            "\"0\"",
            "\"9007199254740993\"",
            "true",
            "false",
            "{}",
            "[]",
            "null",
        ).forEach { lastChangeJson ->
            val account = parseAccount(""""Difficulty":{"Level":5,"LastChange":$lastChangeJson}""")

            assertNull(lastChangeJson, account.difficulty?.lastChange)
            assertEquals(lastChangeJson, 5, account.difficulty?.level)
        }
        assertNull(parseAccount(""""Difficulty":{"Level":5}""").difficulty?.lastChange)
    }

    @Test
    fun difficultyFieldsRequireTheirExactWireCase() {
        val account = parseAccount(
            """"Difficulty":{"level":0,"LEVEL":5,"lastChange":123,"LASTCHANGE":456}""",
        )

        assertEquals(1, account.difficulty?.level)
        assertNull(account.difficulty?.lastChange)
    }

    @Test
    fun privatePreferencesRequireTheirExactWireFieldNames() {
        val account = parseAccount(
            """
                "chatSettings":{"OOCAutoClose":true},
                "CHATSETTINGS":{"MuStylePoses":true},
                "restrictionSettings":{"NoSpeechGarble":false},
                "RESTRICTIONSETTINGS":{"NoSpeechGarble":false},
                "difficulty":{"Level":0}
            """.trimIndent(),
        )

        assertNull(account.chatSettings)
        assertNull(account.restrictionSettings)
        assertNull(account.difficulty)
    }

    @Test
    fun exactWireFieldsAreUnaffectedByDifferentlyCasedCopies() {
        val chatJson = """{"OOCAutoClose":true,"MuStylePoses":false}"""
        val restrictionJson = """{"NoSpeechGarble":false}"""
        val account = parseAccount(
            """
                "ChatSettings":$chatJson,
                "chatSettings":{"OOCAutoClose":false,"MuStylePoses":true},
                "RestrictionSettings":$restrictionJson,
                "restrictionSettings":{"NoSpeechGarble":true},
                "Difficulty":{"Level":0,"LastChange":9007199254740993,"level":5,"lastChange":1},
                "difficulty":{"Level":5,"LastChange":1}
            """.trimIndent(),
        )

        assertEquals(JsonParser.parseString(chatJson), account.chatSettings)
        assertEquals(JsonParser.parseString(restrictionJson), account.restrictionSettings)
        assertEquals(0, account.difficulty?.level)
        assertEquals(9007199254740993L, account.difficulty?.lastChange)
    }

    @Test
    fun loginResponsePreservesCompletePreferencesAndUnknownPrivateLogFieldsTogether() {
        val chatJson = """{
            "OOCAutoClose":false,
            "MuStylePoses":true,
            "FutureSetting":{"Mode":"保留原样","Values":[null,true,"0",9007199254740993]},
            "UnexpectedType":"false"
        }"""
        val restrictionJson = """{
            "NoSpeechGarble":false,
            "FutureRestriction":{"Enabled":null,"Threshold":1.5},
            "UnexpectedType":[false,"true",{}]
        }"""
        val logJson = """[
            {"Name":"FutureRecord","Group":"Other","Value":null,"Future":{"Settings":[true,"保留",9007199254740993]}},
            {"Name":"BlockTalk","Group":"OwnerRule","Value":1700000000123,"UnknownFlag":false}
        ]"""
        val account = parseAccount(
            """"ChatSettings":$chatJson,"RestrictionSettings":$restrictionJson,"Difficulty":{"Level":0},"Log":$logJson""",
        )

        assertEquals(JsonParser.parseString(chatJson), account.chatSettings)
        assertEquals(JsonParser.parseString(restrictionJson), account.restrictionSettings)
        assertEquals(JsonParser.parseString(logJson), account.log)
        assertEquals(
            "9007199254740993",
            account.chatSettings?.asJsonObject?.getAsJsonObject("FutureSetting")
                ?.getAsJsonArray("Values")?.get(3)?.asString,
        )
        assertEquals(
            "9007199254740993",
            account.log?.asJsonArray?.get(0)?.asJsonObject?.getAsJsonObject("Future")
                ?.getAsJsonArray("Settings")?.get(2)?.asString,
        )
        assertEquals(0, account.difficulty?.level)
    }

    @Test
    fun missingNullAndEmptyPrivateSettingsRemainDistinguishable() {
        val missing = parseAccount()
        val explicitNull = parseAccount(""""ChatSettings":null,"RestrictionSettings":null""")
        val empty = parseAccount(""""ChatSettings":{},"RestrictionSettings":{}""")

        assertNull(missing.chatSettings)
        assertNull(missing.restrictionSettings)
        assertEquals(JsonParser.parseString("null"), explicitNull.chatSettings)
        assertEquals(JsonParser.parseString("null"), explicitNull.restrictionSettings)
        assertEquals(JsonParser.parseString("{}"), empty.chatSettings)
        assertEquals(JsonParser.parseString("{}"), empty.restrictionSettings)
    }

    @Test
    fun unexpectedPrivateSettingsShapesRemainAvailableForValidation() {
        listOf("\"false\"", "true", "1.5", "[]", "[{},null,false]").forEach { settingsJson ->
            val account = parseAccount(""""ChatSettings":$settingsJson,"RestrictionSettings":$settingsJson""")

            assertEquals(settingsJson, JsonParser.parseString(settingsJson), account.chatSettings)
            assertEquals(settingsJson, JsonParser.parseString(settingsJson), account.restrictionSettings)
        }
    }

    private fun parseAccount(fieldsJson: String = ""): ServerAccountData {
        val extraFields = if (fieldsJson.isEmpty()) "" else ",$fieldsJson"
        val payloadJson = """{"ID":"account-1001","MemberNumber":1001,"Name":"Alice"$extraFields}"""
        val response = RazorWireParser.parse("LoginResponse", payloadJson) as RazorWireEvent.LoginResponse
        return (response.value as LoginResult.Success).data
    }
}
