package dev.vivian.mobilebc.domain.bcspeech

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterOwnership
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatPreferences
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcPrivateLogMutation
import dev.vivian.mobilebc.core.model.BcRoomChatRejection
import dev.vivian.mobilebc.core.model.BcRoomChatRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcRoomChatPreparationTest {
    @Test
    fun talkBlockRequiresFullOnlineOwnershipAndCurrentOwnerPresence() {
        val state = privateState(log("BlockTalk"))
        assertRejected(BcRoomChatRejectionReason.TalkBlockedByOwner, prepare(state = state))
        assertPrepared(prepare(state = state, self = owned(stage = 0)))
        assertPrepared(prepare(state = state, self = owned(stage = null)))
        assertPrepared(prepare(state = state, characters = listOf(other)))
        assertPrepared(prepare(state = state, self = unowned))
        assertPrepared(prepare(state = privateState(log("BlockTalk", group = "Other"))))
        assertPrepared(prepare(state = privateState(log("BlockTalkSuffix"))))
    }

    @Test
    fun timedTalkBlockIsActiveAtItsExactExpiryAndInactiveAfterward() {
        val state = privateState(log("BlockTalk", expiry = now))
        assertRejected(BcRoomChatRejectionReason.TalkBlockedByOwner, prepare(state = state, time = now))
        assertPrepared(prepare(state = state, time = now + 1))
    }

    @Test
    fun duplicateExactRulesUseTheFirstMatchingEntryAsNativeLogQueryDoes() {
        val state = privateState(log("BlockTalk", expiry = now - 1), log("BlockTalk"))
        assertPrepared(prepare(state = state))
        assertRejected(BcRoomChatRejectionReason.TalkBlockedByOwner, prepare(state = privateState(log("BlockTalk"), log("BlockTalk", expiry = now - 1))))
    }

    @Test
    fun missingServerClockRejectsRelevantTimedRulesButNotAbsentOrPermanentRules() {
        assertRejected(BcRoomChatRejectionReason.OwnerRulesUnavailable, prepare(state = privateState(log("BlockTalk", expiry = now)), time = null))
        assertRejected(BcRoomChatRejectionReason.TalkBlockedByOwner, prepare(state = privateState(log("BlockTalk")), time = null))
        assertPrepared(prepare(time = null))
        assertPrepared(prepare(state = privateState(log("BlockTalk", expiry = now)), characters = emptyList(), time = null))
    }

    @Test
    fun forbiddenWordBlockAppliesDuringOwnershipTrialWithoutOwnerPresence() {
        val state = privateState(log("ForbiddenWordsBlock|BAD"))
        assertRejected(BcRoomChatRejectionReason.ForbiddenWord, prepare("bad", state = state, self = owned(stage = 0), characters = emptyList()), "BAD")
        assertRejected(BcRoomChatRejectionReason.ForbiddenWord, prepare("bad", state = state, self = unowned.copy(owner = "  NPC-Owner"), characters = emptyList()), "BAD")
        assertPrepared(prepare("bad", state = state, self = unowned))
    }

    @Test
    fun forbiddenListUsesLastOwnerRulePrefixEvenWhenThatPrefixEntryHasExpired() {
        val state = privateState(
            log("ForbiddenWordsBlock|ONE"),
            log("ForbiddenWordsBlock|TWO", expiry = now - 1),
            log("ForbiddenWordsBlock|THREE", group = "Other"),
        )
        assertPrepared(prepare("one", state = state))
        assertRejected(BcRoomChatRejectionReason.ForbiddenWord, prepare("two", state = state), "TWO")
        assertPrepared(prepare("three", state = state))
    }

    @Test
    fun forbiddenMatchingUppercasesInputRemovesHyphensAndSplitsOnlyNativeSeparators() {
        val state = privateState(log("ForbiddenWordsBlock|BAD"))
        listOf(" bad ", "other,bad.", "b-a-d", "other|bad").forEach { message ->
            assertRejected(BcRoomChatRejectionReason.ForbiddenWord, prepare(message, state = state), "BAD")
        }
        listOf("bad!", "other\tbad", "other\nbad", "other:bad", "badly", "notbad", "other\u00a0bad").forEach { message ->
            assertPrepared(prepare(message, state = state))
        }
    }

    @Test
    fun forbiddenWordsAreNotTrimmedOrUppercasedAndConsequenceIsCaseSensitive() {
        assertPrepared(prepare("bad", state = privateState(log("ForbiddenWordsBlock|bad"))))
        assertPrepared(prepare("bad", state = privateState(log("ForbiddenWordsBlock| BAD"))))
        assertPrepared(prepare("bad", state = privateState(log("ForbiddenWordsblock|BAD"))))
        assertPrepared(prepare("bad", state = privateState(log("ForbiddenWordsMute10|BAD"))))
        assertRejected(BcRoomChatRejectionReason.ForbiddenWord, prepare("bad", state = privateState(log("ForbiddenWords Block |BAD"))), "BAD")
    }

    @Test
    fun firstForbiddenWordReportedFollowsMessageOrderRatherThanRuleListOrder() {
        val state = privateState(log("ForbiddenWordsBlock|ONE|TWO"))
        assertRejected(BcRoomChatRejectionReason.ForbiddenWord, prepare("two one", state = state), "TWO")
    }

    @Test
    fun muteConsequencesAllowCurrentMessageAndStageTheCorrectDeadlineWithoutEditingAccountState() {
        listOf(5L, 15L, 30L).forEach { minutes ->
            val state = privateState(log("ForbiddenWordsMute$minutes|BAD"))
            val current = assertPrepared(prepare("bad", state = state, characters = emptyList()))
            val deadline = now + minutes * 60_000L
            assertEquals("bad", current.text)
            assertEquals(BcPrivateLogMutation.Add("BlockTalkForbiddenWords", "OwnerRule", deadline), current.logMutation)
            assertEquals(1, state.ruleLog!!.size)
            val afterCommit = state.copy(ruleLog = requireNotNull(state.ruleLog) + log("BlockTalkForbiddenWords", expiry = deadline))
            assertRejected(BcRoomChatRejectionReason.TalkMutedByForbiddenWord, prepare("anything", state = afterCommit, time = deadline, characters = emptyList()))
            assertPrepared(prepare("anything", state = afterCommit, time = deadline + 1, characters = emptyList()))
        }
    }

    @Test
    fun aMatchingTimedMuteNeedsServerTimeButANonmatchingWordDoesNot() {
        val state = privateState(log("ForbiddenWordsMute5|BAD"))
        assertRejected(BcRoomChatRejectionReason.OwnerRulesUnavailable, prepare("bad", state = state, time = null))
        assertNull(assertPrepared(prepare("safe", state = state, time = null)).logMutation)
    }

    @Test
    fun timedMuteRejectsNegativeServerTimeAndAcceptsTheZeroBoundary() {
        listOf(5L, 15L, 30L).forEach { minutes ->
            val state = privateState(log("ForbiddenWordsMute$minutes|BAD"))
            listOf(-1L, Long.MIN_VALUE).forEach { invalidTime ->
                assertRejected(BcRoomChatRejectionReason.OwnerRulesUnavailable, prepare("bad", state = state, time = invalidTime))
            }
            assertEquals(
                BcPrivateLogMutation.Add("BlockTalkForbiddenWords", "OwnerRule", minutes * 60_000L),
                assertPrepared(prepare("bad", state = state, time = 0L)).logMutation,
            )
        }
    }

    @Test
    fun timedMuteRejectsDeadlineOverflowAndAcceptsTheLargestRepresentableDeadline() {
        listOf(5L, 15L, 30L).forEach { minutes ->
            val state = privateState(log("ForbiddenWordsMute$minutes|BAD"))
            val latestSafeTime = Long.MAX_VALUE - minutes * 60_000L
            listOf(latestSafeTime + 1L, Long.MAX_VALUE).forEach { invalidTime ->
                assertRejected(BcRoomChatRejectionReason.OwnerRulesUnavailable, prepare("bad", state = state, time = invalidTime))
            }
            assertEquals(
                BcPrivateLogMutation.Add("BlockTalkForbiddenWords", "OwnerRule", Long.MAX_VALUE),
                assertPrepared(prepare("bad", state = state, time = latestSafeTime)).logMutation,
            )
        }
    }

    @Test
    fun activeForbiddenWordMuteAppliesWithoutOwnerPresenceButDoesNotApplyWhenUnowned() {
        val state = privateState(log("BlockTalkForbiddenWords"))
        assertRejected(BcRoomChatRejectionReason.TalkMutedByForbiddenWord, prepare(state = state, self = owned(stage = 0), characters = emptyList()))
        assertPrepared(prepare(state = state, self = unowned))
    }

    @Test
    fun whisperSendBypassesTalkForbiddenAndWhisperSelectionGatesAndPreservesText() {
        val state = privateState(
            log("BlockTalk"), log("BlockWhisper"), log("BlockTalkForbiddenWords"), log("ForbiddenWordsBlock|BAD"),
        ).copy(preferences = BcPrivateChatPreferences(noSpeechGarble = true))
        val result = assertPrepared(prepare("Talk BAD", kind = BcMessageKind.Whisper, state = state, self = babyTalkingSelf()))
        assertEquals("Talk BAD", result.text)
        assertNull(result.original)
        assertTrue(result.effects.isEmpty())
        assertNull(result.logMutation)
    }

    @Test
    fun emoteOnlyHonorsBlockEmoteAndDoesNotRequireSpeechAssetsOrTransformText() {
        val state = privateState(log("BlockTalk"), log("BlockTalkForbiddenWords"), log("ForbiddenWordsBlock|BAD"))
        val text = "Talk BAD (unfinished"
        val result = assertPrepared(prepare(text, kind = BcMessageKind.Emote, state = state, self = babyTalkingSelf()))
        assertEquals(text, result.text)
        assertEquals(emptyList<String>(), result.effects)
        assertNull(result.original)
        assertNull(result.logMutation)
        assertRejected(BcRoomChatRejectionReason.EmoteBlockedByOwner, prepare(kind = BcMessageKind.Emote, state = privateState(log("BlockEmote"))))
        assertPrepared(prepare(kind = BcMessageKind.Emote, state = privateState(log("BlockEmote")), characters = emptyList()))
    }

    @Test
    fun explicitFalseOocPreferenceAndOriginalTextArePreservedDespiteSpeechPreferences() {
        val state = privateState().copy(preferences = BcPrivateChatPreferences(oocAutoClose = false, noSpeechGarble = true))
        val result = assertPrepared(prepare("Talk (OOC", state = state, self = babyTalkingSelf()))
        assertEquals("Talk (OOC", result.text)
        assertNull(result.original)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun oocAutoClosingDoesNotAddSpeechMetadata() {
        val state = privateState().copy(preferences = BcPrivateChatPreferences(noSpeechGarble = true))
        val transformed = assertPrepared(prepare("Talk (OOC", state = state, self = babyTalkingSelf()))
        assertEquals("Talk (OOC)", transformed.text)
        assertNull(transformed.original)
        assertTrue(transformed.effects.isEmpty())
        val unchanged = assertPrepared(prepare("(OOC", state = state, self = babyTalkingSelf()))
        assertEquals("(OOC)", unchanged.text)
        assertNull(unchanged.original)
        assertNull(assertPrepared(prepare("Talk", state = privateState(), self = babyTalkingSelf())).original)
    }

    @Test
    fun unavailableSelfAccountAndOwnerRulesRemainDistinctRejections() {
        assertRejected(BcRoomChatRejectionReason.SelfStateUnavailable, prepare(self = null))
        assertRejected(BcRoomChatRejectionReason.AccountStateUnavailable, prepare(state = null))
        assertRejected(BcRoomChatRejectionReason.OwnerRulesUnavailable, prepare(state = privateState().copy(ruleLog = null)))
    }

    @Test
    fun speechStateIsNotEvaluatedForChatOrWhisper() {
        val state = privateState().copy(preferences = BcPrivateChatPreferences(noSpeechGarble = true))
        val unknown = owned().copy(appearance = listOf(BcCharacterItem("ItemMouth", "Unknown")))
        val malformed = babyTalkingSelf().copy(arousalSettingsJson = "[]")
        for (kind in listOf(BcMessageKind.Chat, BcMessageKind.Whisper)) {
            listOf(
                prepare("Hello 中文 (OOC", kind = kind, state = state, self = babyTalkingSelf()),
                prepare("Hello 中文 (OOC", kind = kind, state = state, self = unknown),
                prepare("Hello 中文 (OOC", kind = kind, state = state, self = malformed),
                prepare("Hello 中文 (OOC", kind = kind, state = state, self = owned().copy(
                    appearance = listOf(BcCharacterItem("ItemMouth", "Unknown", propertyJson = "[]", craftJson = "[]")),
                    assetFamily = "Custom")),
            ).forEach { result ->
                assertEquals(BcRoomChatPreparation.Prepared("Hello 中文 (OOC)"), result)
            }
        }
        assertEquals(BcRoomChatPreparation.Prepared("Hello (OOC"), prepare("Hello (OOC",
            state = state.copy(preferences = state.preferences.copy(oocAutoClose = false))))
    }

    @Test
    fun disabledSpeechEffectsDoNotBypassOwnerTalkAndForbiddenWordRules() {
        assertRejected(BcRoomChatRejectionReason.TalkBlockedByOwner,
            prepare(state = privateState(log("BlockTalk"))))
        assertRejected(BcRoomChatRejectionReason.ForbiddenWord,
            prepare("BAD", state = privateState(log("ForbiddenWordsBlock|BAD"))), "BAD")
        val result = assertPrepared(prepare("BAD",
            state = privateState(log("ForbiddenWordsMute5|BAD"))))
        assertEquals("BAD", result.text)
        assertEquals(BcPrivateLogMutation.Add("BlockTalkForbiddenWords", "OwnerRule", now + 300_000), result.logMutation)
    }

    @Test
    fun otherMessageKindsRemainOutsideRoomSpeechPreparation() {
        listOf(BcMessageKind.Action, BcMessageKind.Beep, BcMessageKind.Activity).forEach { kind ->
            assertEquals(BcRoomChatPreparation.Prepared("unchanged"), prepare("unchanged", kind = kind, self = null, state = null))
        }
    }

    @Test
    fun whisperTargetSelectionBlocksOtherMembersButExemptsOwnerAndIncompleteOwnership() {
        val state = privateState(log("BlockWhisper"))
        assertEquals(BcRoomChatRejectionReason.WhisperBlockedByOwner, selection(state = state)?.reason)
        assertNull(selection(state = state, target = owner.memberNumber))
        assertNull(selection(state = state, self = owned(stage = 0)))
        assertNull(selection(state = state, self = unowned))
        assertNull(selection(state = state, characters = listOf(other)))
    }

    @Test
    fun whisperTargetSelectionTreatsExpiryAndUnavailableStateExplicitly() {
        val state = privateState(log("BlockWhisper", expiry = now))
        assertEquals(BcRoomChatRejectionReason.WhisperBlockedByOwner, selection(state = state, time = now)?.reason)
        assertNull(selection(state = state, time = now + 1))
        assertEquals(BcRoomChatRejectionReason.OwnerRulesUnavailable, selection(state = state, time = null)?.reason)
        assertEquals(BcRoomChatRejectionReason.OwnerRulesUnavailable, selection(state = null)?.reason)
        assertEquals(BcRoomChatRejectionReason.OwnerRulesUnavailable, selection(state = privateState().copy(ruleLog = null))?.reason)
        assertEquals(BcRoomChatRejectionReason.SelfStateUnavailable, selection(self = null)?.reason)
        assertNull(selection(state = null, target = owner.memberNumber))
        assertNull(selection(state = null, characters = emptyList()))
    }

    private fun prepare(
        text: String = "safe",
        kind: BcMessageKind = BcMessageKind.Chat,
        self: BcCharacterSnapshot? = owned(),
        characters: List<BcCharacterSnapshot> = listOf(owner, other),
        state: BcPrivateChatState? = privateState(),
        time: Long? = now,
    ) = prepareBcRoomChat(text, kind, self, characters, state, time)

    private fun selection(
        self: BcCharacterSnapshot? = owned(),
        characters: List<BcCharacterSnapshot> = listOf(owner, other),
        state: BcPrivateChatState? = privateState(),
        time: Long? = now,
        target: Int = other.memberNumber,
    ) = bcWhisperSelectionRejection(self, characters, state, time, target)

    private fun assertPrepared(result: BcRoomChatPreparation): BcRoomChatPreparation.Prepared {
        assertTrue("Expected preparation, got $result", result is BcRoomChatPreparation.Prepared)
        return result as BcRoomChatPreparation.Prepared
    }

    private fun assertRejected(reason: BcRoomChatRejectionReason, result: BcRoomChatPreparation, word: String? = null) {
        assertEquals(BcRoomChatPreparation.Rejected(BcRoomChatRejection(reason, word)), result)
    }

    private fun privateState(vararg logs: BcOwnerRuleLogRecord) = BcPrivateChatState(
        rawLogJson = "[]", ruleLog = logs.toList(), preferences = BcPrivateChatPreferences(),
    )

    private fun log(name: String, group: String = "OwnerRule", expiry: Long? = null) = BcOwnerRuleLogRecord(name, group, expiry)

    private fun owned(stage: Int? = 1) = unowned.copy(ownership = BcCharacterOwnership("Owner", owner.memberNumber, stage = stage))

    private fun babyTalkingSelf() = owned().copy(appearance = listOf(BcCharacterItem("ItemMouth", "Baby")))

    private val now = 1_000_000L
    private val unowned = BcCharacterSnapshot("1", 1, "Self", "Self", true)
    private val owner = BcCharacterSnapshot("2", 2, "Owner", "Owner", false)
    private val other = BcCharacterSnapshot("3", 3, "Other", "Other", false)
}
