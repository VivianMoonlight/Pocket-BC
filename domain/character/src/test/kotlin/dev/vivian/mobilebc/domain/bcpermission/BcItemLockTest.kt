package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.bcitemproperty.*
import dev.vivian.mobilebc.domain.bcrules.*
import org.junit.Assert.*
import org.junit.Test

class BcItemLockTest {
    private val engine = DefaultBcInteractionPermissionEngine()
    private val actor = BcCharacterSnapshot("1", 1, "Self", "Nickname", true)
    private val item = BcCharacterItem("ItemArms", "Cuffs", "[\"Red\"]", 7,
        """{"Effect":["Slow"],"Text":"定制文字","TypeRecord":{"typed":1},"Custom":{"keep":true}}""", """{"Name":"定制铐"}""")
    private val target = BcCharacterSnapshot("2", 2, "Target", "Target", false, appearance = listOf(item))
    private val names = listOf("MetalPadlock", "HighSecurityPadlock", "CombinationPadlock", "PasswordPadlock", "TimerPadlock", "OwnerPadlock")
    private val nativeExtended = BcRuleCatalogParser().parse("R129", "var AssetFemale3DCG = [];",
        requireNotNull(javaClass.classLoader?.getResource("bc-fixtures/Female3DCGExtended-R129.js")).readText()).extendedItems
    private val assets = listOf(BcAssetRule("ItemArms", "Cuffs", allowLock = true)) + names.map {
        BcAssetRule("ItemMisc", it, isLock = true, value = 10.0, ownerOnly = it == "OwnerPadlock", removeTimerSeconds = if (it == "TimerPadlock") 300 else 0)
    }
    private fun context() = BcPermissionContext(actor, target, BcPermissionRoomContext(memberNumbers = setOf(1, 2)),
        BcRuleCatalog("test", mapOf("ItemArms" to BcGroupRule("ItemArms")), emptyMap(), assets.associateBy { BcAssetKey(it.group, it.name) }, extendedItems = nativeExtended),
        serverAllowItem = true, itemPrivateState = BcItemPermissionPrivateState(
            BcWardrobeState(1, "login", 1, null, null, emptyList(), false, false,
                BcWardrobePrivateInputs(inventoryAvailable = true, inventoryItems = names.map { BcWardrobeInventoryItem("ItemMisc", it) })), emptyList()),
        serverTimeAvailable = true)
    private fun evaluate(context: BcPermissionContext = context(), name: String = "MetalPadlock") = engine.evaluate(
        BcPermissionRequest(context, BcInteractionCandidate.LockItem(2, "ItemArms", "Cuffs", name)))

    @Test fun addingLockPreservesCustomItemAndPublishesLockInItsOwnAssetGroup() {
        val context = context()
        val command = ((evaluate(context) as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command
        assertEquals(item.name, command.name)
        assertEquals(item.colorJson, command.colorJson)
        assertEquals(item.difficulty, command.difficulty)
        assertEquals(item.craftJson, command.craftJson)
        assertEquals("定制文字", command.propertyJson.readBcStringProperty("Text"))
        assertEquals(setOf("Slow", "Lock"), command.propertyJson.readBcPropertyTokens("Effect"))
        assertEquals("MetalPadlock", command.propertyJson.readBcStringProperty("LockedBy"))
        assertEquals("Self", command.propertyJson.readBcStringProperty("LockMemberName"))
        assertEquals(1, command.propertyJson.readBcIntProperty("LockMemberNumber"))
        assertEquals(BcItemActionMessage("ActionAddLock", "Cuffs", "MetalPadlock", "定制铐", nextAssetGroup = "ItemMisc"), command.publishAction)
        assertEquals(context.itemPrivateState, command.permissionPrivateState)
    }

    @Test fun unavailableInventoryExistingLockOwnerRestrictionAndBlockedLockAreRejected() {
        val base = context()
        assertTrue(evaluate(base.copy(itemPrivateState = null)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base.copy(itemPrivateState = base.itemPrivateState!!.copy(wardrobe = base.itemPrivateState.wardrobe.copy(
            privateInputs = BcWardrobePrivateInputs(inventoryAvailable = true, inventoryItems = emptyList()))))) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base.copy(target = target.copy(appearance = listOf(item.copy(propertyJson = """{"LockedBy":"ExclusivePadlock"}"""))))) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base, "OwnerPadlock") is BcPermissionDecision.Denied)
        assertTrue(evaluate(base.copy(target = target.copy(ownerMemberNumber = 1)), "OwnerPadlock") is BcPermissionDecision.Allowed)
        assertTrue(evaluate(base.copy(target = target.copy(blockItemsJson = """{"ItemMisc":{"MetalPadlock":[""]}}"""))) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base.copy(serverAllowItem = null)) is BcPermissionDecision.NeedsServerCheck)
        assertTrue(evaluate(base.copy(serverAllowItem = false)) is BcPermissionDecision.Denied)
    }

    @Test fun specialLocksReceiveNativeDefaultsAndTimersRequireAClock() {
        fun command(name: String) = ((evaluate(name = name) as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command
        assertEquals("1", command("HighSecurityPadlock").propertyJson.readBcStringProperty("MemberNumberListKeys"))
        assertEquals("0000", command("CombinationPadlock").propertyJson.readBcStringProperty("CombinationNumber"))
        assertEquals("PASSWORD", command("PasswordPadlock").propertyJson.readBcStringProperty("Password"))
        assertEquals(false, command("PasswordPadlock").propertyJson.readBcBooleanProperty("LockSet"))
        assertEquals(300, command("TimerPadlock").initializeLockTimerSeconds)
        assertTrue(evaluate(context().copy(serverTimeAvailable = false), "TimerPadlock") is BcPermissionDecision.Denied)
    }

    @Test fun lockCatalogUsesLockNamesForSearchAndRetainsTheWornItemAsTheTarget() {
        val request = BcItemManagementPageRequest(context(), "ItemArms", BcItemManagementOperation.Lock,
            query = "高安全", itemDisplayNames = mapOf("HighSecurityPadlock" to "高安全挂锁"), includeDenied = true)
        val page = DefaultBcItemManagementQuery().page(request)
        val entry = page.entries.single()
        assertEquals("高安全挂锁", entry.label)
        assertEquals("HighSecurityPadlock", entry.itemName)
        assertEquals("Cuffs", entry.currentItemName)
        assertEquals(BcInteractionCandidate.LockItem(2, "ItemArms", "Cuffs", "HighSecurityPadlock"), entry.candidate)
    }
}
