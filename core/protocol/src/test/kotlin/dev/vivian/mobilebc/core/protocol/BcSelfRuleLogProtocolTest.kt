package dev.vivian.mobilebc.core.protocol

import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.auth.LoginResult
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcSelfRuleLogProtocolTest {
    @Test
    fun loginResponsePreservesTheCompletePrivateAccountLog() {
        val logJson = """[
            {"Name":"BlockChangePose","Group":"Rule","Value":1700000000123},
            {"Name":"BlockTalk","Group":"Rule"},
            {"Name":"FutureRecord","Group":"Other","Value":null,"Extra":{"Reason":"保留原始记录"}},
            {"Name":"BlockChangePose","Group":"Other","Value":9223372036854775807}
        ]"""
        val account = parseAccount("""{"ID":"account-1001","MemberNumber":1001,"Name":"Alice","Log":$logJson}""")

        assertEquals(1001, account.memberNumber)
        assertEquals("Alice", account.name)
        assertEquals(JsonParser.parseString(logJson), account.log)
        assertEquals("9223372036854775807", account.log?.asJsonArray?.get(3)?.asJsonObject?.get("Value")?.asString)
    }

    @Test
    fun missingNullAndEmptyArrayLogRemainDistinguishable() {
        val missing = parseAccount("""{"ID":"account-1001","MemberNumber":1001,"Name":"Alice"}""")
        val nullLog = parseAccount("""{"ID":"account-1001","MemberNumber":1001,"Name":"Alice","Log":null}""")
        val emptyLog = parseAccount("""{"ID":"account-1001","MemberNumber":1001,"Name":"Alice","Log":[]}""")

        assertNull(missing.log)
        assertEquals(JsonParser.parseString("null"), nullLog.log)
        assertEquals(JsonParser.parseString("[]"), emptyLog.log)
    }

    @Test
    fun malformedLogShapesAndRecordsReachValidationWithoutDroppingEntries() {
        listOf(
            "{}",
            "\"[]\"",
            """[{"Name":"BlockChangePose","Group":"Rule","Value":"1700000000123"},null,true]""",
        ).forEach { logJson ->
            val account = parseAccount("""{"ID":"account-1001","MemberNumber":1001,"Name":"Alice","Log":$logJson}""")

            assertEquals(logJson, JsonParser.parseString(logJson), account.log)
        }
    }

    @Test
    fun similarlyNamedFieldsCannotPopulateThePrivateLog() {
        val account = parseAccount(
            """{
                "ID":"account-1001","MemberNumber":1001,"Name":"Alice",
                "log":[{"Name":"BlockChangePose","Group":"Rule"}],
                "OwnerLog":[{"Name":"BlockTalk","Group":"Rule"}]
            }""",
        )

        assertNull(account.log)
    }

    private fun parseAccount(payloadJson: String): ServerAccountData {
        val response = RazorWireParser.parse("LoginResponse", payloadJson) as RazorWireEvent.LoginResponse
        return (response.value as LoginResult.Success).data
    }
}
