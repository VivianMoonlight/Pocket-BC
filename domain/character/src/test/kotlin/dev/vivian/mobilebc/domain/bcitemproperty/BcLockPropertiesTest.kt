package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcLockPropertiesTest {
    @Test
    fun lockDefaultsMatchThePinnedNativeExtendedConfiguration() {
        val source = requireNotNull(javaClass.classLoader?.getResource("bc-fixtures/Female3DCGExtended-R129.js")).readText()
        val catalog = BcRuleCatalogParser().parse("R129", "var AssetFemale3DCG = [];", source)
        val names = listOf("MetalPadlock", "IntricatePadlock", "HighSecurityPadlock", "CombinationPadlock",
            "PasswordPadlock", "SafewordPadlock", "TimerPadlock", "TimerPasswordPadlock", "OwnerPadlock",
            "LoversPadlock", "FamilyPadlock", "MistressPadlock", "PandoraPadlock", "ExclusivePadlock",
            "OwnerTimerPadlock", "LoversTimerPadlock", "MistressTimerPadlock")
        names.forEach { name ->
            val native = catalog.extendedItem("ItemMisc", name)?.baselinePropertyJson ?: "{}"
            val mobile = requireNotNull(nativeLockBaseline(name, catalog.extendedItem("ItemMisc", name)))
            assertEquals(name, BcItemPropertyJson.propertyKeys(native), BcItemPropertyJson.propertyKeys(mobile))
            BcItemPropertyJson.propertyKeys(native).forEach { key ->
                assertEquals("$name.$key", BcItemPropertyJson.readPropertyValue(native, key), BcItemPropertyJson.readPropertyValue(mobile, key))
            }
        }
    }

    @Test
    fun typedAndModularLockModesFollowNativeAnyAllowedRecordRules() {
        val source = """
            var AssetFemale3DCGExtended = { ItemArms: {
                Cuffs: { Archetype: ExtendedArchetype.TYPED, Options: [{Name:"Loose",AllowLock:false},{Name:"Tight",AllowLock:true}] },
                Harness: { Archetype: ExtendedArchetype.MODULAR, Modules: [
                    {Name:"a",Key:"a",Options:[{AllowLock:false},{AllowLock:true}]},
                    {Name:"b",Key:"b",Options:[{AllowLock:false},{AllowLock:true}]},
                ]},
            }};
        """.trimIndent()
        val catalog = BcRuleCatalogParser().parse("R129", "var AssetFemale3DCG = [];", source)
        val asset = BcAssetRule("ItemArms", "Cuffs")
        val typed = catalog.extendedItem("ItemArms", "Cuffs")
        assertFalse(asset.allowsLock(null, typed))
        assertFalse(asset.allowsLock("""{"TypeRecord":{}}""", typed))
        assertFalse(asset.allowsLock("""{"TypeRecord":{"typed":0}}""", typed))
        assertTrue(asset.allowsLock("""{"TypeRecord":{"typed":1}}""", typed))
        assertTrue(asset.copy(allowLock = true).allowsLock(null, typed))
        val modular = catalog.extendedItem("ItemArms", "Harness")
        assertTrue(asset.allowsLock("""{"TypeRecord":{"a":0,"b":1}}""", modular))
        assertFalse(asset.allowsLock("""{"TypeRecord":{"a":0,"b":0}}""", modular))
    }
}
