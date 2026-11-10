package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.bcitemproperty.*
import dev.vivian.mobilebc.domain.bcrules.*
import org.junit.Assert.*
import org.junit.Test

class BcLockConfigurationTest {
    private val engine = DefaultBcInteractionPermissionEngine()
    private val actor = BcCharacterSnapshot("1", 1, "Actor", "Actor", true)
    private fun context(lock: String = "PasswordPadlock", extra: String = ""): BcPermissionContext {
        val item = BcCharacterItem("ItemArms", "Cuffs", "[\"Red\"]", 5,
            """{"LockedBy":"$lock","Password":"PASSWORD","Hint":"old","LockSet":false,"CombinationNumber":"0123","LockMemberNumber":1,"Effect":["Lock","Slow"],"Text":"中文","TypeRecord":{"typed":1},"Custom":{"a":true}$extra}""", """{"Name":"定制铐"}""")
        val target = BcCharacterSnapshot("2", 2, "Target", "Target", false, appearance = listOf(item))
        val assets = listOf(BcAssetRule("ItemArms", "Cuffs", allowLock = true), BcAssetRule("ItemMisc", lock, isLock = true))
        return BcPermissionContext(actor, target, BcPermissionRoomContext(memberNumbers = setOf(1, 2)),
            BcRuleCatalog("R133", mapOf("ItemArms" to BcGroupRule("ItemArms")), emptyMap(), assets.associateBy { BcAssetKey(it.group, it.name) }), serverAllowItem = true)
    }
    private fun evaluate(context: BcPermissionContext, configuration: BcLockConfiguration): BcPermissionDecision {
        val item = context.target.appearance.first()
        return engine.evaluate(BcPermissionRequest(context, BcInteractionCandidate.ConfigureLock(2, "ItemArms", "Cuffs", item.propertyJson.readBcStringProperty("LockedBy")!!, configuration)))
    }
    private fun initial(password: String = "secret", hint: String = "提示") = BcLockConfiguration.InitializePassword(BcLockCredential(password), hint, true)
    private fun command(context: BcPermissionContext, configuration: BcLockConfiguration) =
        ((evaluate(context, configuration) as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command

    @Test fun passwordInitializationKeepsTheLockAndCustomItemMetadata() {
        val context = context()
        val previous = context.target.appearance.first()
        val command = command(context, initial())
        assertEquals("SECRET", command.propertyJson.readBcStringProperty("Password"))
        assertEquals("提示", command.propertyJson.readBcStringProperty("Hint"))
        assertEquals(true, command.propertyJson.readBcBooleanProperty("LockSet"))
        assertEquals(true, command.propertyJson.readBcBooleanProperty("RemoveOnUnlock"))
        assertEquals("PasswordPadlock", command.propertyJson.readBcStringProperty("LockedBy"))
        assertEquals(1, command.propertyJson.readBcIntProperty("LockMemberNumber"))
        assertEquals("中文", command.propertyJson.readBcStringProperty("Text"))
        assertEquals(mapOf("typed" to 1), BcItemPropertyJson.readTypeRecord(command.propertyJson))
        assertTrue(command.propertyJson!!.contains("\"Custom\":{\"a\":true}"))
        assertEquals(previous.colorJson, command.colorJson)
        assertEquals(previous.difficulty, command.difficulty)
        assertEquals(previous.craftJson, command.craftJson)
        assertEquals(BcItemActionMessage("PasswordChangeSuccess"), command.publishAction)
        val finalized = context.copy(target = context.target.copy(appearance = listOf(previous.copy(propertyJson = command.propertyJson))))
        assertTrue(evaluate(finalized, initial("another")) is BcPermissionDecision.Denied)
    }

    @Test fun blankPasswordAndHintKeepTheExistingValuesAndTimerSettingsArePreserved() {
        val command = command(context("TimerPasswordPadlock", """, "RemoveTimer":123456789, "RemoveItem":true"""),
            BcLockConfiguration.InitializePassword(BcLockCredential(""), ""))
        assertEquals("PASSWORD", command.propertyJson.readBcStringProperty("Password"))
        assertEquals("old", command.propertyJson.readBcStringProperty("Hint"))
        assertEquals(123456789, command.propertyJson.readBcIntProperty("RemoveTimer"))
        assertEquals(true, command.propertyJson.readBcBooleanProperty("RemoveItem"))
        assertNull(command.propertyJson.readBcBooleanProperty("RemoveOnUnlock"))
        assertTrue(evaluate(context("TimerPasswordPadlock"), initial()) is BcPermissionDecision.Denied)
    }

    @Test fun invalidPasswordsHintsWrongCreatorAndExpiredConfigurationAreRejected() {
        for (password in listOf("abc123", "123", "A B", "ABCDEFGHI", "中文", "ßßßßß")) {
            assertEquals(BcPermissionReasonCode.InvalidLockConfiguration,
                (evaluate(context(), initial(password)) as BcPermissionDecision.Denied).reasons.single().code)
        }
        assertTrue(evaluate(context(), initial(hint = "a".repeat(141))) is BcPermissionDecision.Denied)
        assertTrue(evaluate(context().copy(actor = actor.copy(memberNumber = 3), room = BcPermissionRoomContext(memberNumbers = setOf(2, 3))), initial()) is BcPermissionDecision.Denied)
        assertTrue(evaluate(context(extra = """, "LockSet":true"""), initial()) is BcPermissionDecision.Denied)
        val query = DefaultBcItemManagementQuery()
        val entry = query.lockConfigurationEntry(context(), "ItemArms")!!
        assertTrue(entry.enabled)
        assertNull(entry.candidate)
        assertNull(entry.executable)
        assertFalse(entry.toString().contains("PASSWORD"))
    }

    @Test fun changingCombinationRequiresTheCorrectOldCodeAndPreservesLeadingZeros() {
        val base = context("CombinationPadlock")
        val change = BcLockConfiguration.ChangeCombination(BcLockCredential("0123"), BcLockCredential("0007"))
        val command = command(base, change)
        assertEquals("0007", command.propertyJson.readBcStringProperty("CombinationNumber"))
        assertEquals("CombinationPadlock", command.propertyJson.readBcStringProperty("LockedBy"))
        assertEquals(BcItemActionMessage("CombinationChangeSuccess"), command.publishAction)
        assertTrue(evaluate(base, change.copy(current = BcLockCredential("123"))) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base, change.copy(next = BcLockCredential("０００７"))) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base.copy(serverAllowItem = null), change) is BcPermissionDecision.NeedsServerCheck)
        assertTrue(evaluate(base.copy(serverAllowItem = false), change) is BcPermissionDecision.Denied)
        val blocked = base.copy(target = base.target.copy(blockItemsJson = """[{"Group":"ItemMisc","Name":"CombinationPadlock"}]"""))
        assertTrue(evaluate(blocked, change) is BcPermissionDecision.Denied)
        val unsupported = base.copy(rules = base.rules.copy(assets = base.rules.assets + (BcAssetKey("ItemArms", "Cuffs") to base.rules.asset("ItemArms", "Cuffs")!!.copy(allowLock = false))))
        assertTrue(evaluate(unsupported, change) is BcPermissionDecision.Denied)
        val boundBlind = actor.copy(appearance = listOf(BcCharacterItem("ItemHands", "Mittens", propertyJson = """{"Effect":["Block","BlindHeavy"]}""")))
        assertTrue(evaluate(base.copy(actor = boundBlind), change) is BcPermissionDecision.Allowed)
        assertFalse(change.toString().contains("0123"))
    }
}
