package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.Difficulty
import bot.inker.bc.razor.protocol.account.ServerAccountData
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatPreferences
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcPrivateLogMutation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BcPrivateChatRulesTest {
    @Test
    fun clearingOwnershipDeletesOnlyTheOwnerRuleGroupAndPreservesEveryOtherRecordField() {
        val source = state("""[
            {"Name":"BlockTalk","Group":"OwnerRule","Extra":{"keep":1}},
            {"Name":"Unknown","Group":"Other","Value":17,"Extra":{"keep":2}},
            {"Name":"ForbiddenWordsBlock|BAD","Group":"OwnerRule"},
            {"Name":"BlockTalk","Group":"ownerrule","Extra":{"keep":3}}
        ]""")
        val cleared = applyPrivateLogMutation(source, BcPrivateLogMutation.DeleteGroup("OwnerRule"))!!
        assertEquals(listOf(BcOwnerRuleLogRecord("Unknown", "Other", 17L), BcOwnerRuleLogRecord("BlockTalk", "ownerrule")), cleared.ruleLog)
        assertEquals(source.rawLogJson.parsedArray()[1], cleared.rawLogJson.parsedArray()[0])
        assertEquals(source.rawLogJson.parsedArray()[3], cleared.rawLogJson.parsedArray()[1])
        assertEquals(4, source.rawLogJson.parsedArray().size())
    }

    @Test
    fun missingPrivatePreferencesUseNativeDefaultsWithoutInventingATrustedLog() {
        val state = account(logJson = null).toBcPrivateChatState()

        assertEquals(BcPrivateChatPreferences(), state.preferences)
        assertNull(state.rawLogJson)
        assertNull(state.ruleLog)
        assertEquals(0L, state.revision)
    }

    @Test
    fun privatePreferencesAcceptOnlyExactBooleanFields() {
        val enabled = account(
            chatJson = """{"OOCAutoClose":false,"MuStylePoses":true}""",
            restrictionJson = """{"NoSpeechGarble":true}""",
            difficultyLevel = 0,
        ).toBcPrivateChatState().preferences
        val disabled = account(
            chatJson = """{"OOCAutoClose":true,"MuStylePoses":false}""",
            restrictionJson = """{"NoSpeechGarble":false}""",
            difficultyLevel = 0,
        ).toBcPrivateChatState().preferences

        assertFalse(enabled.oocAutoClose)
        assertTrue(enabled.muStylePoses)
        assertTrue(enabled.noSpeechGarble)
        assertTrue(disabled.oocAutoClose)
        assertFalse(disabled.muStylePoses)
        assertFalse(disabled.noSpeechGarble)

        listOf("\"true\"", "1", "null", "[]", "{}").forEach { valueJson ->
            val preferences = account(
                chatJson = """{"OOCAutoClose":$valueJson,"MuStylePoses":$valueJson}""",
                restrictionJson = """{"NoSpeechGarble":$valueJson}""",
                difficultyLevel = 0,
            ).toBcPrivateChatState().preferences

            assertTrue(valueJson, preferences.oocAutoClose)
            assertFalse(valueJson, preferences.muStylePoses)
            assertFalse(valueJson, preferences.noSpeechGarble)
        }
    }

    @Test
    fun differentlyCasedPreferenceFieldsAndNonObjectSettingsUseDefaults() {
        val wrongCase = account(
            chatJson = """{"oocAutoClose":false,"mustyleposes":true}""",
            restrictionJson = """{"nospeechgarble":true}""",
            difficultyLevel = 0,
        ).toBcPrivateChatState().preferences

        assertTrue(wrongCase.oocAutoClose)
        assertFalse(wrongCase.muStylePoses)
        assertFalse(wrongCase.noSpeechGarble)
        listOf("null", "[]", "true", "\"settings\"").forEach { settingsJson ->
            val preferences = account(
                chatJson = settingsJson,
                restrictionJson = settingsJson,
                difficultyLevel = 0,
            ).toBcPrivateChatState().preferences

            assertTrue(settingsJson, preferences.oocAutoClose)
            assertFalse(settingsJson, preferences.muStylePoses)
            assertFalse(settingsJson, preferences.noSpeechGarble)
        }
    }

    @Test
    fun speechGarbleBypassRequiresRoleplayAndHigherOrInvalidDifficultiesUseRegularRules() {
        for (level in 0..3) {
            val preferences = account(
                restrictionJson = """{"NoSpeechGarble":true}""",
                difficultyLevel = level,
            ).toBcPrivateChatState().preferences

            assertEquals(level, preferences.difficultyLevel)
            assertEquals("difficulty=$level", level == 0, preferences.noSpeechGarble)
        }
        listOf(null, -1, 4, 5, 6).forEach { level ->
            val preferences = account(
                restrictionJson = """{"NoSpeechGarble":true}""",
                difficultyLevel = level,
            ).toBcPrivateChatState().preferences

            assertEquals("difficulty=$level", 1, preferences.difficultyLevel)
            assertFalse("difficulty=$level", preferences.noSpeechGarble)
        }
    }

    @Test
    fun privateStateRetainsUnknownSettingsAndRawLogFields() {
        val chatJson = """{"OOCAutoClose":false,"Future":{"Enabled":true},"Values":[1,"two",null]}"""
        val restrictionJson = """{"NoSpeechGarble":true,"FutureRestriction":"keep"}"""
        val logJson = """[
            {"Name":"BlockTalk","Group":"OwnerRule","Value":17,"Future":{"Reason":"保留"}},
            {"Name":"OtherEntry","Group":"FutureGroup","Value":null,"Metadata":[1,true]}
        ]"""
        val source = account(logJson, chatJson, restrictionJson, difficultyLevel = 0)

        val state = source.toBcPrivateChatState()

        assertEquals(source.chatSettings.toString(), state.preferences.chatSettingsJson)
        assertEquals(source.restrictionSettings.toString(), state.preferences.restrictionSettingsJson)
        assertEquals(source.log.toString(), state.rawLogJson)
        assertEquals(
            listOf(
                BcOwnerRuleLogRecord("BlockTalk", "OwnerRule", 17L),
                BcOwnerRuleLogRecord("OtherEntry", "FutureGroup"),
            ),
            state.ruleLog,
        )
        assertEquals(JsonParser.parseString(logJson), source.log)
    }

    @Test
    fun everyLogToggleUsesItsExactNativeOwnerRuleKey() {
        val commands = listOf(
            Triple("TalkAllow", "TalkBlock", "BlockTalk"),
            Triple("EmoteAllow", "EmoteBlock", "BlockEmote"),
            Triple("WhisperAllow", "WhisperBlock", "BlockWhisper"),
            Triple("ChangePoseAllow", "ChangePoseBlock", "BlockChangePose"),
            Triple("AccessSelfAllow", "AccessSelfBlock", "BlockAccessSelf"),
            Triple("AccessOtherAllow", "AccessOtherBlock", "BlockAccessOther"),
            Triple("KeyAllow", "KeyBlock", "BlockKey"),
            Triple("SelfOwnerLockAllow", "SelfOwnerLockBlock", "BlockOwnerLockSelf"),
            Triple("RemoteAllow", "RemoteBlock", "BlockRemote"),
            Triple("NicknameAllow", "NicknameBlock", "BlockNickname"),
            Triple("KeyAllowFamily", "KeyBlockFamily", "BlockFamilyKey"),
            Triple("RemoteAllowSelf", "RemoteBlockSelf", "BlockRemoteSelf"),
        )
        commands.forEach { (allow, block, name) ->
            val source = state(
                """[
                    {"Name":"$name","Group":"OwnerRule","Value":7,"Future":true},
                    {"Name":"$name","Group":"Other","Value":99}
                ]""",
            )
            val allowed = plan(source, "OwnerRule$allow") as BcOwnerRuleUpdate.Apply
            val blocked = plan(source, "OwnerRule$block") as BcOwnerRuleUpdate.Apply

            assertEquals(allow, listOf(BcOwnerRuleLogRecord(name, "Other", 99L)), allowed.state.ruleLog)
            assertEquals(
                block,
                listOf(BcOwnerRuleLogRecord(name, "OwnerRule"), BcOwnerRuleLogRecord(name, "Other", 99L)),
                blocked.state.ruleLog,
            )
            assertFalse(allow, allowed.resetWhisperTarget)
            assertEquals(block, block == "WhisperBlock", blocked.resetWhisperTarget)
            assertTrue(blocked.state.rawLogJson.parsedArray()[0].asJsonObject["Future"].asBoolean)
            assertEquals(7L, source.ruleLog?.first()?.expiresAtMillis)
        }
    }

    @Test
    fun changeAllowDeletesOnlyTheExactOwnerRuleKey() {
        val source = state(
            """[
                {"Name":"BlockChange","Group":"OwnerRule","Value":17},
                {"Name":"BlockChange","Group":"Other","Future":true},
                {"Name":"BlockChangePose","Group":"OwnerRule"}
            ]""",
        )

        val update = plan(source, "OwnerRuleChangeAllow") as BcOwnerRuleUpdate.Apply

        assertEquals(
            listOf(BcOwnerRuleLogRecord("BlockChange", "Other"), BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule")),
            update.state.ruleLog,
        )
    }

    @Test
    fun ownerAuthorizationCannotBeInferredFromARulePrefixAlone() {
        val source = state()

        assertSame(BcOwnerRuleUpdate.Ignore, plan(source, "OwnerRuleTalkBlock", sender = 3003))
        assertSame(BcOwnerRuleUpdate.Ignore, plan(source, "OwnerRuleTalkBlock", ownershipStage = 0))
        assertSame(BcOwnerRuleUpdate.Ignore, plan(source, "OwnerRuleTalkBlock", ownershipStage = -1))
        assertSame(BcOwnerRuleUpdate.Ignore, plan(source, "OwnerRuleTalkBlock", sender = -1, ownerMemberNumber = -1))
        assertSame(BcOwnerRuleUpdate.Ignore, plan(source, "LoverRuleTalkBlock"))
        assertSame(BcOwnerRuleUpdate.Ignore, plan(source, "ownerRuleTalkBlock"))
        assertSame(BcOwnerRuleUpdate.Invalidate, plan(source, "OwnerRuleTalkBlock", ownerMemberNumber = null))
        assertSame(BcOwnerRuleUpdate.Invalidate, plan(source, "OwnerRuleTalkBlock", ownershipStage = null))
    }

    @Test
    fun fullyOwnedStartsAtStageOneAndTheCallerSuppliesTheAuthorizedReceiveContext() {
        val source = state()

        val update = plan(source, "OwnerRuleTalkBlock", ownershipStage = 1) as BcOwnerRuleUpdate.Apply

        assertEquals(listOf(BcOwnerRuleLogRecord("BlockTalk", "OwnerRule")), update.state.ruleLog)
        // Hidden type, recipient, room presence and receive-generation checks belong to the caller.
        assertEquals(source.revision, update.state.revision)
    }

    @Test
    fun unknownAndKnownCommandsWithoutLogEffectsDoNotInventLogChanges() {
        val source = state()
        listOf(
            "OwnerRuleFutureCommand",
            "OwnerRuleTalkBlockExtra",
            "OwnerRuleKeyConfiscate",
            "OwnerRuleRemoteConfiscate",
            "OwnerRuleTimerCell5",
            "OwnerRuleGGTS180",
            "OwnerRuleNicknameNewNew Name",
            "OwnerRuleLaborMaidDrinks",
            "OwnerRuleForceWheelFortune",
        ).forEach { content ->
            assertSame(content, BcOwnerRuleUpdate.Ignore, plan(source, content))
        }
        listOf("OwnerRuleCollarRelease", "OwnerRuleCollarWear").forEach { content ->
            assertSame(content, BcOwnerRuleUpdate.Invalidate, plan(source, content))
        }
    }

    @Test
    fun timedWardrobeCommandsUseTheirNativeDurationsIncludingTheLongBlock() {
        val durations = listOf(
            "OwnerRuleChangeBlock1Hour" to 3_600_000L,
            "OwnerRuleChangeBlock1Day" to 86_400_000L,
            "OwnerRuleChangeBlock1Week" to 604_800_000L,
            "OwnerRuleChangeBlock" to 1_000_000_000_000L,
        )
        durations.forEach { (content, duration) ->
            val update = plan(state(), content) as BcOwnerRuleUpdate.Apply

            assertEquals(content, listOf(BcOwnerRuleLogRecord("BlockChange", "OwnerRule", SERVER_TIME + duration)), update.state.ruleLog)
        }
    }

    @Test
    fun timedCommandsRequireAKnownClockAndCannotWrapTheirExpiry() {
        listOf(null, -1L, Long.MAX_VALUE).forEach { serverTime ->
            assertSame(
                "time=$serverTime",
                BcOwnerRuleUpdate.Invalidate,
                plan(state(), "OwnerRuleChangeBlock1Hour", serverTimeMillis = serverTime),
            )
        }
        val update = plan(state(), "OwnerRuleChangeBlock1Hour", serverTimeMillis = 0L) as BcOwnerRuleUpdate.Apply

        assertEquals(3_600_000L, update.state.ruleLog?.single()?.expiresAtMillis)
    }

    @Test
    fun advancedCommandsCutAtTheAbsoluteContentIndexAndPreserveOtherGroups() {
        listOf(
            "BlockScreen" to 100,
            "BlockAppearance" to 100,
            "BlockItemGroup" to 100,
            "ForbiddenWords" to 10_000,
        ).forEach { (prefix, endIndex) ->
            val command = "OwnerRule$prefix"
            val source = state(
                """[
                    {"Name":"${prefix}Old","Group":"OwnerRule","Value":1},
                    {"Name":"${prefix}Other","Group":"Other","Future":true},
                    {"Name":"Unrelated","Group":"OwnerRule","Future":17}
                ]""",
            )
            val content = command + "x".repeat(endIndex + 10)

            val update = plan(source, content) as BcOwnerRuleUpdate.Apply

            assertEquals(
                prefix,
                listOf(
                    BcOwnerRuleLogRecord("${prefix}Other", "Other"),
                    BcOwnerRuleLogRecord("Unrelated", "OwnerRule"),
                    BcOwnerRuleLogRecord(prefix + "x".repeat(endIndex - command.length), "OwnerRule"),
                ),
                update.state.ruleLog,
            )
            assertTrue(update.state.rawLogJson.parsedArray()[0].asJsonObject["Future"].asBoolean)
        }
    }

    @Test
    fun anEmptyAdvancedSuffixReplacesTheOldMaskWithTheBarePrefixRecord() {
        listOf("BlockScreen", "BlockAppearance", "BlockItemGroup", "ForbiddenWords").forEach { prefix ->
            val source = state("""[{"Name":"${prefix}Old","Group":"OwnerRule"}]""")

            val update = plan(source, "OwnerRule$prefix") as BcOwnerRuleUpdate.Apply

            assertEquals(prefix, listOf(BcOwnerRuleLogRecord(prefix, "OwnerRule")), update.state.ruleLog)
        }
    }

    @Test
    fun advancedSubstringLimitsUseUtf16UnitsAsNativeJavascriptDoes() {
        val content = "OwnerRuleBlockScreen" + "x".repeat(79) + "\uD83D\uDE42"

        val update = plan(state(), content) as BcOwnerRuleUpdate.Apply

        assertEquals(
            listOf(BcOwnerRuleLogRecord("BlockScreen" + "x".repeat(79) + "\uD83D", "OwnerRule")),
            update.state.ruleLog,
        )
    }

    @Test
    fun duplicateProjectionUsesTheLastValueAtTheFirstPositionAndMutationsRetainEveryExtraField() {
        val source = state(
            """[
                {"Name":"BlockTalk","Group":"OwnerRule","Value":10,"FirstExtra":{"Version":1}},
                {"Name":"BlockTalk","Group":"Other","Value":30,"OtherExtra":true},
                {"Name":"BlockTalk","Group":"OwnerRule","Value":20,"LastExtra":[1,2]}
            ]""",
        )

        assertEquals(
            listOf(BcOwnerRuleLogRecord("BlockTalk", "OwnerRule", 20L), BcOwnerRuleLogRecord("BlockTalk", "Other", 30L)),
            source.ruleLog,
        )
        val renewed = applyPrivateLogMutation(source, BcPrivateLogMutation.Add("BlockTalk", "OwnerRule", 50L))!!
        val renewedEntries = renewed.rawLogJson.parsedArray()

        assertEquals(3, renewedEntries.size())
        assertEquals(50L, renewedEntries[0].asJsonObject["Value"].asLong)
        assertEquals(50L, renewedEntries[2].asJsonObject["Value"].asLong)
        assertEquals(source.rawLogJson.parsedArray()[0].asJsonObject["FirstExtra"], renewedEntries[0].asJsonObject["FirstExtra"])
        assertEquals(source.rawLogJson.parsedArray()[1], renewedEntries[1])
        assertEquals(source.rawLogJson.parsedArray()[2].asJsonObject["LastExtra"], renewedEntries[2].asJsonObject["LastExtra"])
        assertEquals(
            listOf(BcOwnerRuleLogRecord("BlockTalk", "OwnerRule", 50L), BcOwnerRuleLogRecord("BlockTalk", "Other", 30L)),
            renewed.ruleLog,
        )
        val deleted = applyPrivateLogMutation(source, BcPrivateLogMutation.Delete("BlockTalk", "OwnerRule"))!!

        assertEquals(JsonParser.parseString("[${source.rawLogJson.parsedArray()[1]}]"), deleted.rawLogJson.parsedArray())
        assertEquals(20L, source.ruleLog?.first()?.expiresAtMillis)
    }

    @Test
    fun logMutationsRejectUnavailableOrDivergentProjections() {
        val unavailable = account(logJson = null).toBcPrivateChatState()
        val malformed = state("""[{"Name":"BlockTalk","Group":"OwnerRule","Value":"17"}]""")
        val divergent = state().copy(ruleLog = listOf(BcOwnerRuleLogRecord("BlockTalk", "OwnerRule")))

        listOf(unavailable, malformed, divergent).forEach { source ->
            assertNull(applyPrivateLogMutation(source, BcPrivateLogMutation.Add("BlockTalk", "OwnerRule")))
            assertSame(BcOwnerRuleUpdate.Invalidate, plan(source, "OwnerRuleTalkBlock"))
        }
    }

    @Test
    fun preparingATimedForbiddenWordMuteLeavesTheSourceAndRevisionUnchangedUntilCommit() {
        val source = state(
            """[{"Name":"FutureEntry","Group":"Other","Future":{"keep":true}}]""",
        ).copy(revision = 7L)
        val originalRaw = source.rawLogJson
        val originalRules = source.ruleLog
        val mutation = BcPrivateLogMutation.Add("BlockTalkForbiddenWords", "OwnerRule", SERVER_TIME + 60_000L)

        val staged = applyPrivateLogMutation(source, mutation)!!

        assertEquals(originalRaw, source.rawLogJson)
        assertEquals(originalRules, source.ruleLog)
        assertEquals(7L, source.revision)
        assertEquals(7L, staged.revision)
        assertEquals(source.rawLogJson.parsedArray()[0], staged.rawLogJson.parsedArray()[0])
        assertEquals(BcOwnerRuleLogRecord("BlockTalkForbiddenWords", "OwnerRule", SERVER_TIME + 60_000L), staged.ruleLog?.last())
        assertSame(source.preferences, staged.preferences)
    }

    private fun plan(
        state: BcPrivateChatState,
        content: String,
        sender: Int = 2002,
        ownerMemberNumber: Int? = 2002,
        ownershipStage: Int? = 1,
        serverTimeMillis: Long? = SERVER_TIME,
    ): BcOwnerRuleUpdate = planOwnerRule(state, content, sender, ownerMemberNumber, ownershipStage, serverTimeMillis)

    private fun state(logJson: String = "[]"): BcPrivateChatState = account(logJson).toBcPrivateChatState()

    private fun account(
        logJson: String? = "[]",
        chatJson: String? = null,
        restrictionJson: String? = null,
        difficultyLevel: Int? = null,
    ): ServerAccountData = ServerAccountData(
        id = "account-1001",
        memberNumber = 1001,
        name = "Alice",
        log = logJson?.let(JsonParser::parseString),
        chatSettings = chatJson?.let(JsonParser::parseString),
        restrictionSettings = restrictionJson?.let(JsonParser::parseString),
        difficulty = difficultyLevel?.let { Difficulty(it) },
    )

    private fun String?.parsedArray() = JsonParser.parseString(this).asJsonArray

    private companion object {
        const val SERVER_TIME = 1_700_000_000_000L
    }
}
