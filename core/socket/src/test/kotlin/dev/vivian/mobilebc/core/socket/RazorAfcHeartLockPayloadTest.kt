package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.model.BcAfcHeartLockChange
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import org.junit.Assert.*
import org.junit.Test

class RazorAfcHeartLockPayloadTest {
    private val command = BcCharacterItemUpdateCommand(2, "ItemArms", "Cuffs", "[\"Red\"]", 7,
        """{"LockedBy":"HighSecurityPadlock","Name":"Heart Padlock","LockMemberNumber":1,"Effect":["Slow","Lock"],"Text":"中文😀","Custom":{"keep":true}}""",
        """{"Name":"定制铐😀","Description":"说明"}""", afcHeartLockChange = BcAfcHeartLockChange.Apply("昵称😀", "Female3DCG"))

    @Test fun applySnapshotAndNativeItemShareOneIdentityAndPreserveCustomMetadata() {
        val update = buildAfcHeartLockUpdate(command, 1, 1_800_000_000_000, "identity-1")
        assertTrue(update.companionFirst)
        assertEquals("HeartLockApply", update.companion.get("Content").asString)
        assertEquals(2, update.companion.get("Target").asInt)
        val entry = update.companion.getAsJsonArray("Dictionary")[0].asJsonObject
        assertEquals("identity-1", entry.get("LockId").asString)
        assertEquals(1, entry.get("Owner").asInt)
        assertEquals("昵称😀", entry.get("OwnerName").asString)
        val snapshot = entry.getAsJsonObject("Snapshot")
        assertEquals(2, snapshot.get("version").asInt)
        assertEquals("runtime", snapshot.get("format").asString)
        assertEquals(update.item.get("Property"), snapshot.get("property"))
        assertEquals(update.item.get("Craft"), snapshot.get("craft"))
        assertEquals(update.item.get("Color"), snapshot.get("color"))
        assertEquals(7, snapshot.get("difficulty").asInt)
        assertEquals("identity-1", snapshot.getAsJsonObject("property").get("HeartLockId").asString)
        assertEquals("中文😀", snapshot.getAsJsonObject("property").get("Text").asString)
        assertFalse(update.item.has("afcHeartLockChange"))
        assertFalse(command.propertyJson!!.contains("identity-1"))
    }

    @Test fun removalIsIdentityScopedAndLegacyRemovalFollowsActualUnlock() {
        val removal = command.copy(propertyJson = "{}", afcHeartLockChange = BcAfcHeartLockChange.Remove("identity-1"))
        val update = buildAfcHeartLockUpdate(removal, 1, 0)
        assertEquals("HeartLock::Remove", update.companion.get("Content").asString)
        assertEquals("identity-1", update.companion.getAsJsonArray("Dictionary")[0].asJsonObject.get("LockId").asString)
        val legacy = buildAfcHeartLockUpdate(removal.copy(afcHeartLockChange = BcAfcHeartLockChange.Remove(null)), 1, 0)
        assertFalse(legacy.companionFirst)
        assertFalse(legacy.companion.getAsJsonArray("Dictionary")[0].asJsonObject.has("LockId"))
    }
}
