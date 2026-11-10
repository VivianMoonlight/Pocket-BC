package dev.vivian.mobilebc.domain.bcitemproperty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcItemPropertyJsonTest {
    @Test
    fun escapedUnicodeAndControlCharactersSurvivePropertyRewrites() {
        val property = """{"Text":"\u4f60\u597d \ud83d\udd12\b\f","\u0045ffect":["\u004c\u006fck"],"LockedBy":"Padlock"}"""
        assertEquals("你好 🔒\b\u000C", property.readBcStringProperty("Text"))
        assertEquals(setOf("Lock"), property.readBcPropertyTokens("Effect"))
        val unlocked = BcItemPropertyJson.withoutLock(property)
        assertEquals("你好 🔒\b\u000C", unlocked.readBcStringProperty("Text"))
        assertTrue(unlocked.readBcPropertyTokens("Effect").isEmpty())
        val modified = BcItemPropertyJson.mergeForModification(property, """{"Effect":["A\b\f\u0000B"]}""")
        assertEquals(setOf("A\b\u000C\u0000B", "Lock"), modified.readBcPropertyTokens("Effect"))
        assertTrue(modified.none { it < ' ' })
    }

    @Test
    fun fieldNamesInsideTextAndNestedObjectsDoNotBecomeProperties() {
        val property = """{"Text":"Effect: [Gag], ReceiveShock: true, LockedBy: 'OwnerPadlock', Difficulty: 9","Nested":{"Effect":["Gag"],"ReceiveShock":true,"LockedBy":"OwnerPadlock","Difficulty":9}}"""

        assertTrue(property.readBcPropertyTokens("Effect").isEmpty())
        assertNull(property.readBcBooleanProperty("ReceiveShock"))
        assertNull(property.readBcStringProperty("LockedBy"))
        assertNull(property.readBcIntProperty("Difficulty"))
        assertEquals(BcItemPropertyOverlay(), BcItemPropertyJson.readOverlay(property))
    }

    @Test
    fun readsTopLevelQuotedAndStaticUnquotedFieldsCaseInsensitively() {
        val property = """{effect: ['Gag', Slow, "Gag"], 'Block': 'ItemHands', "ReceiveShock": false, 'lockedby': 'Owner\'s Padlock', Difficulty: -3, TypeRecord: {hood: 2}}"""

        assertEquals(setOf("Gag", "Slow"), property.readBcPropertyTokens("Effect"))
        assertEquals(setOf("ItemHands"), property.readBcPropertyTokens("block"))
        assertEquals(false, property.readBcBooleanProperty("receiveshock"))
        assertEquals("Owner's Padlock", property.readBcStringProperty("LockedBy"))
        assertEquals(-3, property.readBcIntProperty("difficulty"))
        val overlay = BcItemPropertyJson.readOverlay(property)
        assertEquals(setOf("Gag", "Slow"), overlay.effects)
        assertEquals(setOf("ItemHands"), overlay.blocks)
        assertEquals(mapOf("hood" to 2), overlay.typeRecord)
        assertEquals("Owner's Padlock", overlay.lockedBy)
    }

    @Test
    fun escapedTextDoesNotHideTheRealTopLevelProperties() {
        val property = """{"Text":"fake \"LockedBy\":\"OwnerPadlock\", Effect: [Gag]","Effect":["Slow"],"ReceiveShock":true,"Difficulty":4}"""

        assertEquals(setOf("Slow"), property.readBcPropertyTokens("Effect"))
        assertEquals(true, property.readBcBooleanProperty("ReceiveShock"))
        assertEquals(4, property.readBcIntProperty("Difficulty"))
        assertNull(property.readBcStringProperty("LockedBy"))
    }
}
