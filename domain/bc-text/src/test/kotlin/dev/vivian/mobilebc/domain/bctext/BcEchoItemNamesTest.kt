package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.BcInteractionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcEchoItemNamesTest {
    @Test
    fun mirrorsInheritNativeTranslationsAndEchoAliasesKeepTheirNames() {
        val items = BcEchoItemNames().items(listOf(BcInteractionItem("Cloth", "Dress", "连衣裙")), "CN")
        assertEquals("连衣裙", items.single { it.group == "Cloth_笨笨蛋Luzi" && it.name == "Dress" }.displayName)
        val tail = items.single { it.group == "TailStraps" && it.name == "尾巴1_Luzi" }
        assertEquals("❤尖尾巴1", tail.displayName)
        assertEquals("尾巴1_Luzi", tail.toWearCommand(2).name)
    }

    @Test
    fun dictionaryResolvesEchoInBothLanguagesAndDoesNotGuessUnknownNames() {
        var language = "CN"
        val resolver = BcCachedDictionaryValueResolver(
            resourceReader = { _, path -> when (path) {
                BcResourcePaths.Assets.Female3DCG.Female3DCGCsv -> "Cloth,Dress,Dress"
                BcResourcePaths.Assets.Female3DCG.Female3DCGCn -> "Dress\n连衣裙"
                else -> null
            } },
            languageProvider = { language },
        )
        assertEquals("白色礼服", resolver.assetDisplayName("R133", "Cloth", "白色礼服"))
        assertEquals("连衣裙", resolver.assetDisplayName("R133", "Cloth_笨笨蛋Luzi", "Dress"))
        language = "EN"
        assertEquals("White Dress", resolver.assetDisplayName("R133", "Cloth_笨笨蛋Luzi", "白色礼服"))
        assertNull(resolver.assetDisplayName("R133", "Cloth", "UnknownCustomItem"))
    }
}
