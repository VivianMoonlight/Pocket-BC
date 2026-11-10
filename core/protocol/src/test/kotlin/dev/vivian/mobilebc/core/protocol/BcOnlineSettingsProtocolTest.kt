package dev.vivian.mobilebc.core.protocol

import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.auth.LoginResult
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcOnlineSettingsProtocolTest {
    @Test
    fun loginPreservesNativeBanPreferencesAndUnknownOnlineSettingsWithoutCoercion() {
        val settingsJson = """{
            "AutoBanBlackList":true,
            "AutoBanGhostList":"false",
            "DefaultChatRoomBackground":"MainHall",
            "FutureSetting":{"Values":[null,false,"保留",9007199254740993]}
        }"""
        val account = parseAccount(""""OnlineSettings":$settingsJson,"ChatSettings":{"AutoBanBlackList":false}""")
        val settings = requireNotNull(account.onlineSettings).asJsonObject

        assertEquals(JsonParser.parseString(settingsJson), account.onlineSettings)
        assertTrue(settings["AutoBanBlackList"].asJsonPrimitive.isBoolean)
        assertTrue(settings["AutoBanGhostList"].asJsonPrimitive.isString)
        assertEquals("false", settings["AutoBanGhostList"].asString)
        assertEquals("9007199254740993", settings["FutureSetting"].asJsonObject["Values"].asJsonArray[3].asString)
        assertFalse(requireNotNull(account.chatSettings).asJsonObject["AutoBanBlackList"].asBoolean)
    }

    @Test
    fun missingNullAndUnexpectedOnlineSettingsShapesRemainAvailableForValidation() {
        assertNull(parseAccount().onlineSettings)

        listOf("null", "{}", "[]", "[false,null]", "true", "1.5", "\"false\"").forEach { settingsJson ->
            val account = parseAccount(""""OnlineSettings":$settingsJson""")

            assertEquals(settingsJson, JsonParser.parseString(settingsJson), account.onlineSettings)
        }
    }

    @Test
    fun onlineSettingsUsesOnlyItsExactWireFieldName() {
        assertNull(parseAccount(""""onlineSettings":{"AutoBanBlackList":true},"ONLINESETTINGS":{}""").onlineSettings)

        val account = parseAccount(
            """"OnlineSettings":{"AutoBanBlackList":false},"onlineSettings":{"AutoBanBlackList":true},"ONLINESETTINGS":{}""",
        )

        assertEquals(JsonParser.parseString("""{"AutoBanBlackList":false}"""), account.onlineSettings)
    }

    private fun parseAccount(fieldsJson: String = ""): ServerAccountData {
        val extraFields = if (fieldsJson.isEmpty()) "" else ",$fieldsJson"
        val event = RazorWireParser.parse(
            "LoginResponse",
            """{"ID":"account-1001","MemberNumber":1001,"Name":"Alice"$extraFields}""",
        ) as RazorWireEvent.LoginResponse
        return (event.value as LoginResult.Success).data
    }
}
