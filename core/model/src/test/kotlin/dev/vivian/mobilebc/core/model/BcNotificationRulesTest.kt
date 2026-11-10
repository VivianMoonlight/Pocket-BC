package dev.vivian.mobilebc.core.model

import org.junit.Assert.*
import org.junit.Test

class BcNotificationRulesTest {
    private val input = BcNotificationInput(
        42, setOf(BcNotificationIdentity.Friend, BcNotificationIdentity.Submissive),
        BcMessageKind.Beep, "Please HELP", involvesSelf = true,
    )

    @Test fun multipleIdentitiesUseHighestLevelAndOffDoesNotVetoOtherIdentity() {
        val settings = BcNotificationSettings(matrix = mapOf(
            "Friend:Beep" to BcNotificationLevel.Off,
            "Submissive:Beep" to BcNotificationLevel.Critical,
        ))
        assertEquals(BcNotificationLevel.Critical, settings.decide(input).level)
        assertEquals(BcNotificationLevel.Off, settings.decide(input.copy(identities = setOf(BcNotificationIdentity.Friend))).level)
    }

    @Test fun firstMatchingRuleCanSilenceCriticalMatrixAndOrderMatters() {
        val silent = BcNotificationRule("silent", "Silent", memberNumber = 42, level = BcNotificationLevel.Off)
        val urgent = BcNotificationRule("urgent", "Urgent", keyword = "help", level = BcNotificationLevel.Critical)
        assertEquals(BcNotificationDecision(BcNotificationLevel.Off, "silent"),
            BcNotificationSettings(rules = listOf(silent, urgent)).decide(input))
        assertEquals(BcNotificationDecision(BcNotificationLevel.Critical, "urgent"),
            BcNotificationSettings(rules = listOf(urgent, silent)).decide(input))
    }

    @Test fun allAnyDisabledAndEmptyConditionsAreDistinct() {
        val rule = BcNotificationRule("r", "Rule", memberNumber = 99, keyword = "help")
        assertFalse(rule.matches(input))
        assertTrue(rule.copy(matchAll = false).matches(input))
        assertFalse(rule.copy(enabled = false, matchAll = false).matches(input))
        assertFalse(BcNotificationRule("empty", "Empty").matches(input))
        assertFalse(BcNotificationRule("empty", "Empty", matchAll = false).matches(input))
        assertTrue(rule.copy(memberNumber = 42, involvesSelf = true).matches(input))
        assertFalse(rule.copy(memberNumber = 42, involvesSelf = false).matches(input))
    }

    @Test fun systemEventsUseSeparateLevelUnlessOverridden() {
        val system = input.copy(type = BcMessageKind.Error, system = true)
        assertEquals(BcNotificationLevel.Off, BcNotificationSettings(systemLevel = BcNotificationLevel.Off).decide(system).level)
        val rule = BcNotificationRule("errors", "Errors", type = BcMessageKind.Error, level = BcNotificationLevel.Critical)
        assertEquals(BcNotificationLevel.Critical, BcNotificationSettings(systemLevel = BcNotificationLevel.Off, rules = listOf(rule)).decide(system).level)
    }
}
