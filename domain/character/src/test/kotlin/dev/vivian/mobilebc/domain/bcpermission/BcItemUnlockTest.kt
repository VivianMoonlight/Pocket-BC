package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty
import dev.vivian.mobilebc.domain.bcitemproperty.readBcPropertyTokens
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockCredential
import dev.vivian.mobilebc.domain.bcrules.*
import org.junit.Assert.*
import org.junit.Test

class BcItemUnlockTest {
    private fun credentialContext(lock: String = "PasswordPadlock", extra: String = ""): BcPermissionContext {
        val base = context(false)
        val lockAsset = BcAssetRule("ItemMisc", lock, isLock = true)
        val property = """{"LockedBy":"$lock","Password":"SECRET","CombinationNumber":"0123","LockMemberNumber":2,"Effect":["Lock","BlockArms"],"TypeRecord":{"typed":1},"Text":"你好"$extra}"""
        return base.copy(itemPrivateState = null,
            rules = base.rules.copy(assets = base.rules.assets + (BcAssetKey(lockAsset.group, lockAsset.name) to lockAsset)),
            target = target.copy(appearance = listOf(item.copy(propertyJson = property))))
    }

    private fun withCode(context: BcPermissionContext, code: String, lock: String = "PasswordPadlock") = engine.evaluate(
        BcPermissionRequest(context, BcInteractionCandidate.UnlockItem(context.target.memberNumber, "ItemArms", "Cuffs", lock, BcLockCredential(code))))

    @Test fun passwordUnlockNeedsNoKeyAndDoesNotLeakInputOrEraseCustomProperties() {
        val context = credentialContext()
        val result = withCode(context, "secret") as BcPermissionDecision.Allowed
        val command = (result.executable as BcExecutableInteraction.ItemUpdate).command
        assertEquals("Cuffs", command.name)
        assertEquals(item.colorJson, command.colorJson)
        assertEquals(item.craftJson, command.craftJson)
        assertEquals(item.difficulty, command.difficulty)
        assertEquals("你好", command.propertyJson.readBcStringProperty("Text"))
        assertEquals(setOf("BlockArms"), command.propertyJson.readBcPropertyTokens("Effect"))
        assertNull(command.propertyJson.readBcStringProperty("Password"))
        assertNull(command.propertyJson.readBcStringProperty("CombinationNumber"))
        assertNull(command.propertyJson.readBcStringProperty("LockedBy"))
        assertFalse(command.toString().contains("secret", ignoreCase = true))
        assertFalse(BcInteractionCandidate.UnlockItem(2, "ItemArms", "Cuffs", "PasswordPadlock", BcLockCredential("SECRET")).toString().contains("SECRET"))
    }

    @Test fun incorrectCodeNeverProducesACommandAndCombinationKeepsLeadingZero() {
        assertEquals(BcPermissionReasonCode.IncorrectLockCredential,
            (withCode(credentialContext(), "wrong") as BcPermissionDecision.Denied).reasons.single().code)
        assertTrue(withCode(credentialContext(), "SECRET ") is BcPermissionDecision.Denied)
        for (code in listOf("123", " 0123", "０１２３", "0124")) {
            assertTrue(withCode(credentialContext("CombinationPadlock"), code, "CombinationPadlock") is BcPermissionDecision.Denied)
        }
        assertTrue(withCode(credentialContext("CombinationPadlock"), "0123", "CombinationPadlock") is BcPermissionDecision.Allowed)
        assertTrue(withCode(credentialContext("TimerPasswordPadlock"), "secret", "TimerPasswordPadlock") is BcPermissionDecision.Allowed)
        assertTrue(withCode(credentialContext("HighSecurityPadlock"), "SECRET", "HighSecurityPadlock") is BcPermissionDecision.Denied)
    }

    @Test fun passwordRemoveOnUnlockOnlyAppliesToCredentialUnlock() {
        val context = credentialContext(extra = """, "RemoveOnUnlock":true""")
        val command = ((withCode(context, "SECRET") as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command
        assertNull(command.name)
        assertNull(command.propertyJson)
        assertNull(command.colorJson)
        assertNull(command.craftJson)
        assertEquals("ActionUnlock", command.publishAction?.content)
        val timerOnly = credentialContext("TimerPasswordPadlock", """, "RemoveItem":true""")
        val timerCommand = ((withCode(timerOnly, "SECRET", "TimerPasswordPadlock") as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command
        assertEquals("Cuffs", timerCommand.name)
    }

    @Test fun credentialsRespectInspectionPermissionsZoneAndServerChecks() {
        val base = credentialContext()
        val blocked = base.copy(target = base.target.copy(blockItemsJson = """[{"Group":"ItemMisc","Name":"PasswordPadlock"}]"""))
        assertTrue(withCode(blocked, "SECRET") is BcPermissionDecision.Denied)
        val inaccessible = base.copy(target = base.target.copy(appearance = base.target.appearance +
            BcCharacterItem("ItemTorso", "Cage", propertyJson = """{"Block":["ItemArms"]}""")))
        assertTrue(withCode(inaccessible, "SECRET") is BcPermissionDecision.Denied)
        assertTrue(withCode(base.copy(serverAllowItem = null), "SECRET") is BcPermissionDecision.NeedsServerCheck)
        assertTrue(withCode(base.copy(serverAllowItem = false), "SECRET") is BcPermissionDecision.Denied)
        val bound = actor.copy(appearance = listOf(BcCharacterItem("ItemHands", "Mittens", propertyJson = """{"Effect":["Block"]}""")))
        assertTrue(withCode(base.copy(actor = bound), "SECRET") is BcPermissionDecision.Allowed)
        val combination = credentialContext("CombinationPadlock").copy(actor = bound)
        assertTrue(withCode(combination, "0123", "CombinationPadlock") is BcPermissionDecision.Allowed)
        val blindCombination = combination.copy(actor = bound.copy(appearance = bound.appearance +
            BcCharacterItem("ItemEyes", "Blindfold", propertyJson = """{"Effect":["BlindHeavy"]}""")))
        assertTrue(withCode(blindCombination, "0123", "CombinationPadlock") is BcPermissionDecision.Allowed)
        val blind = actor.copy(appearance = listOf(BcCharacterItem("ItemEyes", "Blindfold", propertyJson = """{"Effect":["BlindHeavy"]}""")))
        assertTrue(withCode(base.copy(actor = blind), "SECRET") is BcPermissionDecision.Denied)
        assertTrue(withCode(credentialContext("SafewordPadlock").copy(actor = blind), "SECRET", "SafewordPadlock") is BcPermissionDecision.Allowed)
        val craftedBlind = actor.copy(appearance = listOf(BcCharacterItem("ItemHead", "Hood", craftJson = """{"Effects":{"Thick":1}}""")))
        assertTrue(withCode(base.copy(actor = craftedBlind), "SECRET") is BcPermissionDecision.Denied)
        val thin = actor.copy(appearance = listOf(BcCharacterItem("ItemEyes", "Blindfold", propertyJson = """{"Effect":["BlindLight"]}""", craftJson = """{"Effects":{"Thin":1}}""")))
        assertTrue(withCode(base.copy(actor = thin), "SECRET") is BcPermissionDecision.Allowed)
    }

    @Test fun credentialQueryExposesOnlyAnInputActionAndNeverAnAutomaticUnlock() {
        val query = DefaultBcItemManagementQuery()
        val entry = query.page(BcItemManagementPageRequest(credentialContext(), "ItemArms", BcItemManagementOperation.Unlock)).entries.single()
        assertTrue(entry.enabled)
        assertTrue(entry.requiresUnlockInput)
        assertNull(entry.candidate)
        assertNull(entry.executable)
        assertNull(entry.propertyJson)
        assertFalse(entry.toString().contains("SECRET"))
    }

    @Test fun credentialUnlockStaysAvailableWhenTheActorAlsoHasAnOwnerKey() {
        val base = credentialContext()
        val privateState = context().itemPrivateState
        val key = BcAssetRule("ItemMisc", "OwnerPadlockKey", value = 0.0)
        val context = base.copy(itemPrivateState = privateState,
            rules = base.rules.copy(assets = base.rules.assets + (BcAssetKey(key.group, key.name) to key)),
            target = base.target.copy(ownerMemberNumber = 1))
        val query = DefaultBcItemManagementQuery()
        val keyEntry = query.page(BcItemManagementPageRequest(context, "ItemArms", BcItemManagementOperation.Unlock)).entries.single()
        assertTrue(keyEntry.enabled)
        assertFalse(keyEntry.requiresUnlockInput)
        val codeEntry = query.credentialUnlockEntry(context, "ItemArms")!!
        assertTrue(codeEntry.enabled)
        assertTrue(codeEntry.requiresUnlockInput)
        assertNull(codeEntry.candidate)
        assertNull(codeEntry.executable)
    }

    private val engine = DefaultBcInteractionPermissionEngine()
    private val actor = BcCharacterSnapshot("1", 1, "Self", "Self", true)
    private val item = BcCharacterItem("ItemArms", "Cuffs", colorJson = "[\"Red\"]", difficulty = 7,
        propertyJson = """{"LockedBy":"Padlock","LockMemberNumber":2,"Effect":["Lock","BlockArms"],"TypeRecord":{"typed":1},"Text":"你好"}""",
        craftJson = """{"Name":"Custom"}""")
    private val target = BcCharacterSnapshot("2", 2, "Target", "Target", false, appearance = listOf(item))
    private val assets = listOf(BcAssetRule("ItemArms", "Cuffs"), BcAssetRule("ItemMisc", "Padlock"),
        BcAssetRule("ItemMisc", "PadlockKey", effects = setOf("UnlockPadlock"), value = 10.0))
    private fun context(owned: Boolean = true): BcPermissionContext {
        val inventory = BcWardrobePrivateInputs(inventoryAvailable = true,
            inventoryItems = if (owned) listOf(BcWardrobeInventoryItem("ItemMisc", "PadlockKey")) else emptyList())
        val wardrobe = BcWardrobeState(1, "login", 1, null, null, emptyList(), false, false, inventory)
        return BcPermissionContext(actor, target, BcPermissionRoomContext(memberNumbers = setOf(1, 2)),
            BcRuleCatalog("test", mapOf("ItemArms" to BcGroupRule("ItemArms")), emptyMap(), assets.associateBy { BcAssetKey(it.group, it.name) }),
            serverAllowItem = true, itemPrivateState = BcItemPermissionPrivateState(wardrobe, emptyList()))
    }
    private fun evaluate(context: BcPermissionContext, lock: String = "Padlock") = engine.evaluate(
        BcPermissionRequest(context, BcInteractionCandidate.UnlockItem(context.target.memberNumber, "ItemArms", "Cuffs", lock)))

    @Test fun keyUnlockPreservesItemMetadataAndRemovesOnlyLockProperties() {
        val context = context()
        val command = ((evaluate(context) as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command
        assertEquals(item.name, command.name)
        assertEquals(item.colorJson, command.colorJson)
        assertEquals(item.difficulty, command.difficulty)
        assertEquals(item.craftJson, command.craftJson)
        assertNull(command.propertyJson.readBcStringProperty("LockedBy"))
        assertEquals("你好", command.propertyJson.readBcStringProperty("Text"))
        assertEquals(setOf("BlockArms"), command.propertyJson.readBcPropertyTokens("Effect"))
        assertEquals(BcItemActionMessage("ActionUnlock", previousAssetName = "Cuffs", previousCraftName = "Custom"), command.publishAction)
        assertEquals(context.itemPrivateState, command.permissionPrivateState)
    }

    @Test fun absentKeyMissingPrivateStateAndDepositedKeysDenyUnlock() {
        assertTrue(evaluate(context(false)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(context().copy(itemPrivateState = null)) is BcPermissionDecision.Denied)
        val context = context()
        assertTrue(evaluate(context.copy(itemPrivateState = context.itemPrivateState!!.copy(
            ruleLog = listOf(BcOwnerRuleLogRecord("KeyDeposit", "Cell"))))) is BcPermissionDecision.Denied)
        val expiring = context.copy(itemPrivateState=context.itemPrivateState!!.copy(ruleLog=listOf(BcOwnerRuleLogRecord("KeyDeposit","Cell",1000))))
        assertTrue(evaluate(expiring.copy(serverTimeMillis=1000)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(expiring.copy(serverTimeMillis=1001)) is BcPermissionDecision.Allowed)
        assertTrue(evaluate(context, "OwnerPadlock") is BcPermissionDecision.Denied)
        assertTrue(evaluate(context.copy(serverAllowItem = null)) is BcPermissionDecision.NeedsServerCheck)
        assertTrue(evaluate(context.copy(serverAllowItem = false)) is BcPermissionDecision.Denied)
    }

    @Test fun exclusivePadlockCanBeUnlockedOnlyOnAnotherCharacter() {
        val context = context(false).copy(itemPrivateState = null,
            target = target.copy(appearance = listOf(item.copy(propertyJson = """{"LockedBy":"ExclusivePadlock","Effect":["Lock"]}"""))))
        assertTrue(evaluate(context, "ExclusivePadlock") is BcPermissionDecision.Allowed)
        assertTrue(evaluate(context.copy(target = context.target.copy(memberNumber = 1, isSelf = true)), "ExclusivePadlock") is BcPermissionDecision.Denied)
    }

    @Test fun unlockQueryHidesUnlockedItemsAndKeepsDeniedLocksVisibleWhenRequested() {
        val query = DefaultBcItemManagementQuery()
        val request = BcItemManagementPageRequest(context(false), "ItemArms", BcItemManagementOperation.Unlock, includeDenied = true)
        assertFalse(query.page(request).entries.single().enabled)
        assertTrue(query.page(request.copy(context = context())).entries.single().enabled)
        assertTrue(query.page(request.copy(context = context().copy(target = target.copy(appearance = listOf(item.copy(propertyJson = "{}")))))).entries.isEmpty())
    }

    @Test fun emptyExclusiveKeyholderListDoesNotFallBackToTheLockCreator() {
        val base = context()
        val lock = BcAssetRule("ItemMisc", "HighSecurityPadlock", exclusiveUnlock = true)
        val context = base.copy(rules = base.rules.copy(assets = base.rules.assets + (BcAssetKey(lock.group, lock.name) to lock)),
            target = target.copy(appearance = listOf(item.copy(propertyJson = """{"LockedBy":"HighSecurityPadlock","MemberNumberListKeys":"","LockMemberNumber":1}"""))))
        assertTrue(evaluate(context, lock.name) is BcPermissionDecision.Denied)
        val permitted = context.copy(target = target.copy(appearance = listOf(item.copy(propertyJson = """{"LockedBy":"HighSecurityPadlock","MemberNumberListKeys":"1, 2"}"""))))
        assertTrue(evaluate(permitted, lock.name) is BcPermissionDecision.Allowed)
        val nativeNumberForms = context.copy(target = target.copy(appearance = listOf(item.copy(propertyJson = """{"LockedBy":"HighSecurityPadlock","MemberNumberListKeys":"1e0, 0x2"}"""))))
        assertTrue(evaluate(nativeNumberForms, lock.name) is BcPermissionDecision.Allowed)
    }

    @Test fun itemTypePermissionsDoNotTrapAnExistingLockedItemWhenTheKeyIsAvailable() {
        val base = context()
        val blocked = base.copy(target = target.copy(blockItemsJson = """{"ItemArms":{"Cuffs":["","typed1"]}}"""))
        assertTrue(evaluate(blocked) is BcPermissionDecision.Allowed)
    }

    @Test fun selfUnlockFlagAndBoundHandsRequireTheNativeStruggleFlow() {
        val base = context()
        val self = actor.copy(appearance = listOf(item.copy(propertyJson = """{"LockedBy":"Padlock","SelfUnlock":false}""")))
        assertTrue(evaluate(base.copy(actor = self, target = self)) is BcPermissionDecision.Denied)
        val bound = actor.copy(appearance = listOf(BcCharacterItem("ItemHands", "Mittens", propertyJson = """{"Effect":["Block"]}""")))
        assertTrue(evaluate(base.copy(actor = bound)) is BcPermissionDecision.Denied)
    }
}
