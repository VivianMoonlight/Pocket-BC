package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.bcpermission.*
import dev.vivian.mobilebc.domain.bcrules.*
import org.junit.Assert.*
import org.junit.Test

class BcAfcHeartLockTest {
    private val actor = BcCharacterSnapshot("1", 1, "Actor", "Actor", true)
    private val wearer = BcCharacterSnapshot("2", 2, "Wearer", "Wearer", false)
    private val locked = BcCharacterItem("ItemArms", "Cuffs", propertyJson = """{"LockedBy":"HighSecurityPadlock","Name":"Heart Padlock","HeartLockId":"lock-1","LockMemberNumber":1,"Text":"定制文字","Effect":["Lock","Slow"]}""")
    private fun shared(enabled: Boolean = true, owner: Boolean = false, lovers: String = "[{\"memberNumber\":1}]") =
        """{"AFC":{"lockPerms":{"enableAFCLock":$enabled,"enableOwnerLock":$owner},"lovers":$lovers}}"""

    @Test fun applyingRequiresWearerPermissionAndAnActualRelationship() {
        assertTrue(BcAfcHeartLock.canApply(actor, wearer.copy(onlineSharedSettingsJson = shared())))
        assertFalse(BcAfcHeartLock.canApply(actor, wearer.copy(onlineSharedSettingsJson = shared(false))))
        assertFalse(BcAfcHeartLock.canApply(actor, wearer.copy(onlineSharedSettingsJson = shared(lovers = "[]"))))
        assertFalse(BcAfcHeartLock.canApply(actor, wearer))
        assertFalse(BcAfcHeartLock.canApply(actor, actor.copy(onlineSharedSettingsJson = shared())))
        assertTrue(BcAfcHeartLock.canApply(actor, wearer.copy(ownerMemberNumber = 1, onlineSharedSettingsJson = shared(false, true, "[]"))))
        assertFalse(BcAfcHeartLock.canApply(actor, wearer.copy(ownerMemberNumber = 1, onlineSharedSettingsJson = shared(false, false, "[]"))))
        assertTrue(BcAfcHeartLock.canApply(actor, wearer.copy(lovership = listOf(BcCharacterLovership("Actor", 1, 0, 0)), onlineSharedSettingsJson = shared(lovers = "[]"))))
    }

    @Test fun originalLockOwnerCanUnlockAfterRelationshipRevocationButNativeKeysCannotBypassAfc() {
        assertTrue(BcAfcHeartLock.canUnlock(actor, wearer.copy(onlineSharedSettingsJson = shared(false)), locked))
        assertFalse(BcAfcHeartLock.canUnlock(actor.copy(memberNumber = 3), wearer, locked))
        assertTrue(BcAfcHeartLock.canUnlock(actor.copy(memberNumber = 3), wearer.copy(onlineSharedSettingsJson = shared(lovers = "[{\"memberNumber\":3}]")), locked))
        val unlocked = BcAfcHeartLock.unlockedProperty(locked.propertyJson)
        assertNull(unlocked.readBcStringProperty("LockedBy"))
        assertNull(unlocked.readBcStringProperty("Name"))
        assertNull(unlocked.readBcStringProperty("HeartLockId"))
        assertEquals("定制文字", unlocked.readBcStringProperty("Text"))
        assertEquals(setOf("Slow"), unlocked.readBcPropertyTokens("Effect"))
    }

    @Test fun strippedMarkersRequireExactPublicSummaryIdentity() {
        val stripped = locked.copy(propertyJson = """{"LockedBy":"HighSecurityPadlock","LockMemberNumber":1}""")
        val summary = """{"AFC_HeartLock":{"memberNumber":2,"padlocks":{"ItemArms":{"owner":1,"assetName":"Cuffs","lockId":"lock-1"}}}}"""
        assertEquals("lock-1", BcAfcHeartLock.identity(wearer.copy(onlineSharedSettingsJson = summary), stripped)?.lockId)
        assertNull(BcAfcHeartLock.identity(wearer, stripped))
        assertNull(BcAfcHeartLock.identity(wearer.copy(onlineSharedSettingsJson = summary.replace("Cuffs", "Other")), stripped))
        assertNull(BcAfcHeartLock.identity(wearer.copy(onlineSharedSettingsJson = summary.replace("\"owner\":1", "\"owner\":3")), stripped))
        assertNull(BcAfcHeartLock.identity(wearer.copy(onlineSharedSettingsJson = summary.replace("\"memberNumber\":2", "\"memberNumber\":3")), stripped))
    }

    @Test fun lockQueryAndExecutionPreserveEquipmentAndRequireExplicitUnlockBeforeRemoval() {
        val assets = listOf(BcAssetRule("ItemArms", "Cuffs", allowLock = true), BcAssetRule("ItemMisc", "HighSecurityPadlock", isLock = true))
        val rules = BcRuleCatalog("test", mapOf("ItemArms" to BcGroupRule("ItemArms")), emptyMap(), assets.associateBy { BcAssetKey(it.group, it.name) },
            extendedItems = mapOf(BcAssetKey("ItemMisc", "HighSecurityPadlock") to BcExtendedItemRule("ItemMisc", "HighSecurityPadlock", BcExtendedItemArchetype.NoArch, "")))
        val equipped = locked.copy(propertyJson = """{"Text":"keep","Effect":["Slow"]}""", craftJson = """{"Name":"自定义铐"}""", difficulty = 7)
        val context = BcPermissionContext(actor, wearer.copy(appearance = listOf(equipped), onlineSharedSettingsJson = shared()), BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules,
            serverAllowItem = true, itemPrivateState = BcItemPermissionPrivateState(BcWardrobeState(1, "login", 1, null, null, emptyList(), false, false), emptyList()))
        val engine = DefaultBcInteractionPermissionEngine()
        val decision = engine.evaluate(BcPermissionRequest(context, BcInteractionCandidate.LockItem(2, "ItemArms", "Cuffs", BcAfcHeartLock.Name))) as BcPermissionDecision.Allowed
        val command = (decision.executable as BcExecutableInteraction.ItemUpdate).command
        assertTrue(command.afcHeartLockChange is BcAfcHeartLockChange.Apply)
        assertEquals(equipped.craftJson, command.craftJson)
        assertEquals(7, command.difficulty)
        assertEquals("keep", command.propertyJson.readBcStringProperty("Text"))
        assertEquals("HighSecurityPadlock", command.propertyJson.readBcStringProperty("LockedBy"))
        val page = DefaultBcItemManagementQuery().page(BcItemManagementPageRequest(context, "ItemArms", BcItemManagementOperation.Lock, query = "拓展恋人"))
        assertEquals(BcAfcHeartLock.Name, page.entries.single().itemName)
        val lockedContext = context.copy(target = context.target.copy(appearance = listOf(locked)))
        assertTrue(engine.evaluate(BcPermissionRequest(lockedContext, BcInteractionCandidate.RemoveItem(2, "ItemArms"))) is BcPermissionDecision.Denied)
        val unlocked = engine.evaluate(BcPermissionRequest(lockedContext, BcInteractionCandidate.UnlockItem(2, "ItemArms", "Cuffs", "HighSecurityPadlock"))) as BcPermissionDecision.Allowed
        assertEquals(BcAfcHeartLockChange.Remove("lock-1"), (unlocked.executable as BcExecutableInteraction.ItemUpdate).command.afcHeartLockChange)
        val stranger = lockedContext.copy(actor = actor.copy(memberNumber = 3), room = BcPermissionRoomContext(memberNumbers = setOf(2, 3)))
        assertTrue(engine.evaluate(BcPermissionRequest(stranger, BcInteractionCandidate.UnlockItem(2, "ItemArms", "Cuffs", "HighSecurityPadlock"))) is BcPermissionDecision.Denied)
        val nativeOwnerWithKey = stranger.copy(target = stranger.target.copy(ownerMemberNumber = 3),
            rules = rules.copy(assets = rules.assets + (BcAssetKey("ItemMisc", "OwnerPadlockKey") to BcAssetRule("ItemMisc", "OwnerPadlockKey", value = 0.0))))
        assertTrue(engine.evaluate(BcPermissionRequest(nativeOwnerWithKey, BcInteractionCandidate.UnlockItem(2, "ItemArms", "Cuffs", "HighSecurityPadlock"))) is BcPermissionDecision.Denied)
    }
}
