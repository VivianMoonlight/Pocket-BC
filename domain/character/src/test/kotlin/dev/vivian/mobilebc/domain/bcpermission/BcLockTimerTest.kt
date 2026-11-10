package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.bcitemproperty.*
import dev.vivian.mobilebc.domain.bcrules.*
import org.junit.Assert.*
import org.junit.Test

class BcLockTimerTest {
    private val engine = DefaultBcInteractionPermissionEngine()
    private val now = 1_700_000_000_000L
    private val actor = BcCharacterSnapshot("10", 10, "Actor", "Actor", true)
    private fun context(lock: String = "TimerPasswordPadlock", creator: Int = 10, extra: String = ""): BcPermissionContext {
        val property = """{"LockedBy":"$lock","LockMemberNumber":$creator,"RemoveTimer":${now + 600_000},"RemoveItem":false,"ShowTimer":false,"EnableRandomInput":true,"MemberNumberList":[],"Effect":["Lock"],"Text":"中文😀","TypeRecord":{"typed":1},"Custom":{"keep":true}$extra}"""
        val item = BcCharacterItem("ItemArms", "LeatherCuffs", "[\"Red\"]", 5, property, """{"Name":"定制铐"}""")
        val target = BcCharacterSnapshot("20", 20, "Target", "Target", false, appearance = listOf(item))
        val assets = listOf(BcAssetRule(item.group, item.name, allowLock = true), BcAssetRule("ItemMisc", lock, isLock = true, removeTimerSeconds = 300, maxTimerSeconds = 14400))
        return BcPermissionContext(actor, target, BcPermissionRoomContext(memberNumbers = setOf(10, 20)),
            BcRuleCatalog("R133", mapOf("ItemArms" to BcGroupRule("ItemArms")), emptyMap(), assets.associateBy { BcAssetKey(it.group, it.name) }),
            serverAllowItem = true, serverTimeAvailable = true, serverTimeMillis = now)
    }
    private fun evaluate(context: BcPermissionContext, change: BcLockTimerChange): BcPermissionDecision {
        val item = context.target.appearance.first { it.group == "ItemArms" }
        return engine.evaluate(BcPermissionRequest(context, BcInteractionCandidate.ChangeLockTimer(20, item.group, item.name, item.propertyJson.readBcStringProperty("LockedBy")!!, change)))
    }
    private fun command(context: BcPermissionContext, change: BcLockTimerChange) = ((evaluate(context, change) as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command
    private fun club(context: BcPermissionContext, expiry: Long? = null) = context.copy(itemPrivateState = BcItemPermissionPrivateState(
        BcWardrobeState(10, "login", 1, null, null, emptyList(), false, false), listOf(BcOwnerRuleLogRecord("ClubMistress", "Management", expiry))))

    @Test fun settingsAndAdjustmentsPreserveTheItemAndDoNotRequireKeys() {
        val base = context()
        val item = base.target.appearance.single()
        val settings = command(base, BcLockTimerChange.Settings(true, true, false))
        assertEquals(item.colorJson, settings.colorJson)
        assertEquals(item.difficulty, settings.difficulty)
        assertEquals(item.craftJson, settings.craftJson)
        assertEquals("中文😀", settings.propertyJson.readBcStringProperty("Text"))
        assertEquals(mapOf("typed" to 1), BcItemPropertyJson.readTypeRecord(settings.propertyJson))
        assertTrue(settings.propertyJson!!.contains("\"Custom\":{\"keep\":true}"))
        assertEquals(true, settings.propertyJson.readBcBooleanProperty("RemoveItem"))
        assertEquals(true, settings.propertyJson.readBcBooleanProperty("ShowTimer"))
        assertEquals(false, settings.propertyJson.readBcBooleanProperty("EnableRandomInput"))
        assertEquals("TimerPasswordPadlock", settings.propertyJson.readBcStringProperty("LockedBy"))
        assertNull(settings.publishAction)
        val adjusted = command(base, BcLockTimerChange.Adjust(300))
        assertEquals(item.propertyJson, adjusted.propertyJson)
        assertEquals(BcItemTimerAdjustment(300, 14400), adjusted.adjustLockTimer)
        assertEquals(BcItemTimerAdjustment(300, 86400), command(base.copy(target = base.target.copy(
            onlineSharedSettingsJson = """{"LockTimerLimit":"LockTimerLimitDay"}""")), BcLockTimerChange.Adjust(300)).adjustLockTimer)
    }

    @Test fun ownerAndLoverLocksUseTheirNativeControllerRelations() {
        for (lock in listOf("OwnerTimerPadlock", "LoversTimerPadlock")) {
            val base = context(lock, creator = 30)
            assertTrue(evaluate(base, BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Denied)
            val owner = base.copy(target = base.target.copy(ownerMemberNumber = 10))
            assertTrue(evaluate(owner, BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Allowed)
            assertTrue(evaluate(owner, BcLockTimerChange.Settings(true, true, true)) is BcPermissionDecision.Allowed)
            val lover = base.copy(target = base.target.copy(lovership = listOf(BcCharacterLovership("Actor", 10, 2))))
            assertEquals(lock == "LoversTimerPadlock", evaluate(lover, BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Allowed)
        }
    }

    @Test fun clubMistressCanAdjustButCannotChangeAnotherCreatorsFlags() {
        val base = context("MistressTimerPadlock", creator = 30)
        assertTrue(evaluate(base, BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(club(base), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Allowed)
        assertTrue(evaluate(club(base), BcLockTimerChange.Settings(true, true, false)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(club(base, now - 1), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(club(base, now), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Allowed)
    }

    @Test fun limitedInputUsesTheNativeAmountsAndRecordsParticipationOnce() {
        for ((lock, step) in listOf("OwnerTimerPadlock" to 7200, "LoversTimerPadlock" to 7200, "MistressTimerPadlock" to 600, "TimerPasswordPadlock" to 600)) {
            val base = context(lock, creator = 30)
            val command = command(base, BcLockTimerChange.LimitedInput(BcTimerInputChoice.Random, true))
            assertEquals(step * 2, command.adjustLockTimer?.seconds)
            assertEquals("[10]", BcItemPropertyJson.readPropertyValue(command.propertyJson, "MemberNumberList"))
            val used = base.copy(target = base.target.copy(appearance = listOf(base.target.appearance.single().copy(propertyJson = command.propertyJson))))
            assertTrue(evaluate(used, BcLockTimerChange.LimitedInput(BcTimerInputChoice.Increase)) is BcPermissionDecision.Denied)
            val numericUsed = context(lock, creator = 30, extra = """, "MemberNumberList":[1e1]""")
            assertTrue(evaluate(numericUsed, BcLockTimerChange.LimitedInput(BcTimerInputChoice.Decrease)) is BcPermissionDecision.Denied)
        }
    }

    @Test fun basicTimerCanBeRestartedByOthersButOnlyCreatorCanSetRemoval() {
        val base = context("TimerPadlock", creator = 30)
        assertEquals(300, command(base, BcLockTimerChange.Restart).initializeLockTimerSeconds)
        assertTrue(evaluate(base, BcLockTimerChange.Settings(true)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base, BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(context("TimerPadlock"), BcLockTimerChange.Settings(true, true)) is BcPermissionDecision.Denied)
    }

    @Test fun missingFieldsUseActiveLockDefaultsAndSelfUsesTheAssetsTimerMaximum() {
        val base = context()
        val item = base.target.appearance.single().copy(propertyJson = """{"LockedBy":"TimerPasswordPadlock","LockMemberNumber":10,"RemoveTimer":${now + 600000}}""")
        val key = BcAssetKey("ItemMisc", "TimerPasswordPadlock")
        val rule = BcExtendedItemRule(key.group, key.name, BcExtendedItemArchetype.NoArch, "noarch",
            baselinePropertyJson = """{"ShowTimer":true,"RemoveItem":false,"EnableRandomInput":false,"MemberNumberList":[]}""")
        val context = base.copy(target = base.target.copy(appearance = listOf(item)), rules = base.rules.copy(extendedItems = mapOf(key to rule)))
        assertTrue(context.lockTimerState(item)!!.showTimer)
        assertEquals(true, command(context, BcLockTimerChange.Adjust(300)).propertyJson.readBcBooleanProperty("ShowTimer"))
        assertTrue("Lock" in command(context, BcLockTimerChange.Adjust(300)).propertyJson.readBcPropertyTokens("Effect"))
        assertEquals(14400, context.copy(target = context.target.copy(isSelf = true,
            onlineSharedSettingsJson = """{"LockTimerLimit":"LockTimerLimitDay"}""")).lockTimerMaximumSeconds(14400).toInt())
    }

    @Test fun expiredClockBlindHandsBlockedGroupsAndUnsupportedDurationsAreRejected() {
        val base = context()
        assertTrue(evaluate(base.copy(serverTimeMillis = null), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base.copy(serverTimeMillis = now + 600_001), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base, BcLockTimerChange.Adjust(42)) is BcPermissionDecision.Denied)
        for (effect in listOf("Block", "BlindHeavy")) {
            assertTrue(evaluate(base.copy(actor = actor.copy(appearance = listOf(BcCharacterItem("ItemHead", "Hood", propertyJson = """{"Effect":["$effect"]}""")))), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Denied)
        }
        fun blocked(context: BcPermissionContext) = context.copy(target = context.target.copy(appearance = context.target.appearance + BcCharacterItem("ItemTorso", "Cage", propertyJson = """{"Block":["ItemArms"]}""")))
        assertTrue(evaluate(blocked(base), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Denied)
        val owner = context("OwnerTimerPadlock").let { it.copy(target = it.target.copy(ownerMemberNumber = 10)) }
        assertTrue(evaluate(blocked(owner), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Allowed)
        assertTrue(evaluate(base.copy(serverAllowItem = null), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.NeedsServerCheck)
        assertTrue(evaluate(base.copy(serverAllowItem = false), BcLockTimerChange.Adjust(300)) is BcPermissionDecision.Denied)
        val preview = DefaultBcItemManagementQuery().timerControlEntry(base, "ItemArms", BcLockTimerChange.Adjust(300))!!
        assertTrue(preview.enabled); assertNull(preview.candidate); assertNull(preview.executable)
    }
}
