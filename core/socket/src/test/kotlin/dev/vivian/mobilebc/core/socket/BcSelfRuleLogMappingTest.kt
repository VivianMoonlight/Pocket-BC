package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.ServerAccountData
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcSelfRuleLogMappingTest {
    @Test
    fun missingNullAndNonArrayLogsAreUnavailable() {
        assertNull(account().toBcSelfRuleLog())
        listOf("null", "{}", "true", "123", "\"[]\"").forEach { logJson ->
            assertNull(logJson, account(logJson).toBcSelfRuleLog())
        }
    }

    @Test
    fun emptyArrayIsAnAuthoritativeEmptyLog() {
        assertEquals(emptyList<BcOwnerRuleLogRecord>(), account("[]").toBcSelfRuleLog())
    }

    @Test
    fun missingAndNullValuesMeanPermanentRules() {
        val mapped = account(
            """[
                {"Name":"BlockChangePose","Group":"Rule"},
                {"Name":"BlockTalk","Group":"Rule","Value":null}
            ]""",
        ).toBcSelfRuleLog()

        assertEquals(
            listOf(
                BcOwnerRuleLogRecord("BlockChangePose", "Rule"),
                BcOwnerRuleLogRecord("BlockTalk", "Rule"),
            ),
            mapped,
        )
    }

    @Test
    fun lastExactNameGroupDuplicateWinsWhileOtherGroupsAndNamesStayDistinct() {
        val mapped = account(
            """[
                {"Name":"BlockChangePose","Group":"Rule","Value":1700000000123},
                {"Name":"BlockChangePose","Group":"Other","Value":0},
                {"Name":"FutureRule ","Group":"Rule","Value":-1},
                {"Name":"BlockChangePose","Group":"Rule","Value":1700000000456}
            ]""",
        ).toBcSelfRuleLog()

        assertEquals(
            listOf(
                BcOwnerRuleLogRecord("BlockChangePose", "Rule", 1700000000456L),
                BcOwnerRuleLogRecord("BlockChangePose", "Other", 0L),
                BcOwnerRuleLogRecord("FutureRule ", "Rule", -1L),
            ),
            mapped,
        )
    }

    @Test
    fun aLastPermanentDuplicateReplacesTheEarlierExpiry() {
        listOf(
            """{"Name":"BlockChangePose","Group":"Rule"}""",
            """{"Name":"BlockChangePose","Group":"Rule","Value":null}""",
        ).forEach { permanentEntry ->
            val mapped = account(
                """[
                    {"Name":"BlockChangePose","Group":"Rule","Value":1700000000123},
                    $permanentEntry
                ]""",
            ).toBcSelfRuleLog()

            assertEquals(
                permanentEntry,
                listOf(BcOwnerRuleLogRecord("BlockChangePose", "Rule")),
                mapped,
            )
        }
    }

    @Test
    fun acceptsIntegralJsonNumbersWithoutRoundingThroughDouble() {
        val cases = listOf(
            "1.0" to 1L,
            "1e3" to 1000L,
            "9007199254740993" to 9007199254740993L,
            "9223372036854775807" to Long.MAX_VALUE,
            "-9223372036854775808" to Long.MIN_VALUE,
        )
        cases.forEach { (jsonNumber, expected) ->
            val mapped = account(
                """[{"Name":"BlockChangePose","Group":"Rule","Value":$jsonNumber}]""",
            ).toBcSelfRuleLog()

            assertEquals(
                jsonNumber,
                listOf(BcOwnerRuleLogRecord("BlockChangePose", "Rule", expected)),
                mapped,
            )
        }
    }

    @Test
    fun fractionalAndOutOfRangeExpiryNumbersInvalidateTheWholeLog() {
        listOf(
            "0.5",
            "-1.5",
            "1700000000123.0000001",
            "9223372036854775808",
            "-9223372036854775809",
            "1e400",
        ).forEach { jsonNumber ->
            assertNull(
                jsonNumber,
                account("""[{"Name":"BlockChangePose","Group":"Rule","Value":$jsonNumber}]""")
                    .toBcSelfRuleLog(),
            )
        }
    }

    @Test
    fun stringBooleanObjectAndArrayExpiryValuesAreNotCoerced() {
        listOf("\"1700000000123\"", "true", "false", "{}", "[]").forEach { valueJson ->
            assertNull(
                valueJson,
                account("""[{"Name":"BlockChangePose","Group":"Rule","Value":$valueJson}]""")
                    .toBcSelfRuleLog(),
            )
        }
    }

    @Test
    fun nonFiniteNumericPrimitivesInvalidateTheLog() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            val entry = JsonObject().apply {
                addProperty("Name", "BlockChangePose")
                addProperty("Group", "Rule")
                add("Value", JsonPrimitive(value))
            }
            val log = JsonArray().apply { add(entry) }

            assertNull(value.toString(), account().copy(log = log).toBcSelfRuleLog())
        }
    }

    @Test
    fun namesAndGroupsRequireExactFieldNamesAndStringValues() {
        listOf(
            """{"Group":"Rule"}""",
            """{"Name":"BlockChangePose"}""",
            """{"name":"BlockChangePose","Group":"Rule"}""",
            """{"Name":"BlockChangePose","group":"Rule"}""",
            """{"Name":null,"Group":"Rule"}""",
            """{"Name":"BlockChangePose","Group":null}""",
            """{"Name":123,"Group":"Rule"}""",
            """{"Name":"BlockChangePose","Group":true}""",
            """{"Name":[],"Group":"Rule"}""",
            """{"Name":"BlockChangePose","Group":{}}""",
        ).forEach { entryJson ->
            assertNull(entryJson, account("[$entryJson]").toBcSelfRuleLog())
        }
    }

    @Test
    fun malformedEntriesCannotSilentlyRemoveAnOwnerRule() {
        listOf(
            "null",
            "[]",
            "true",
            "\"BlockChangePose\"",
            """{"Name":"BlockChangePose","Group":"Rule","Value":"1700000000123"}""",
        ).forEach { malformedEntry ->
            val mapped = account(
                """[
                    {"Name":"SomeValidRecord","Group":"Other"},
                    $malformedEntry,
                    {"Name":"BlockTalk","Group":"Rule"}
                ]""",
            ).toBcSelfRuleLog()

            assertNull(malformedEntry, mapped)
        }
    }

    private fun account(logJson: String? = null): ServerAccountData = ServerAccountData(
        id = "account-1001",
        memberNumber = 1001,
        name = "Alice",
        log = logJson?.let(JsonParser::parseString),
    )
}
