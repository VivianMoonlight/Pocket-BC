package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BcInteractionCatalogRepositoryTest {
    @Test
    fun r133UsesActivityKeysAndKeepsAssetDescriptionTranslations() {
        val repository = BcCachedInteractionCatalogRepository(
            resourceReader = { _, path ->
                when (path) {
                    BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv ->
                        "Label-ChatOther-ItemArms-Spank,Spank Arm"
                    BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCn ->
                        "Label-ChatOther-ItemArms-Spank\n拍打手臂"
                    BcResourcePaths.Assets.Female3DCG.Female3DCGCsv -> "ItemArms,,Arms"
                    BcResourcePaths.Assets.Female3DCG.Female3DCGCn -> "Arms\n手臂"
                    else -> null
                }
            },
            languageProvider = { "CN" },
        )
        val result = repository.refresh("R133") as BcInteractionCatalogLoadResult.Ready
        assertEquals("拍打手臂", result.snapshot.activitiesForGroup("ItemArms").single().displayName)
        assertEquals("手臂", result.snapshot.groups.single { it.name == "ItemArms" }.displayName)
    }

    @Test
    fun parsesPerGroupActionsAndItemsFromCachedBcResources() {
        val reader: (String, String) -> String? = { _, relativePath ->
            when (relativePath) {
                BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv -> """
                    ActivitySpank,Spank
                    Label-ChatOther-ItemArms-Spank,Spank Arm
                    ChatOther-ItemArms-Spank,SourceCharacter spanks TargetCharacter's arm.
                    Label-ChatSelf-ItemArms-Spank,Spank Own Arm
                    ChatSelf-ItemArms-Spank,SourceCharacter spanks PronounPossessive own arm.
                    Label-ChatOther-ItemFeet-Tickle,Tickle Feet
                    ChatOther-ItemFeet-Tickle,SourceCharacter tickles TargetCharacter's feet.
                """.trimIndent()
                BcResourcePaths.Assets.Female3DCG.Female3DCGCsv -> """
                    ItemArms,,Arms
                    ItemArms,HempRope,Hemp Rope
                    ItemFeet,,Feet
                    ItemFeet,NylonRope,Nylon Rope
                """.trimIndent()
                else -> null
            }
        }
        val repository = BcCachedInteractionCatalogRepository(reader, clockMillis = { 42L })

        val result = repository.refresh("r129")

        assertTrue(result is BcInteractionCatalogLoadResult.Ready)
        val snapshot = (result as BcInteractionCatalogLoadResult.Ready).snapshot
        assertEquals("R129", snapshot.version)
        assertEquals(42L, snapshot.generatedAtEpochMillis)
        assertEquals(listOf("Spank"), snapshot.activitiesForGroup("ItemArms").map { it.name })
        assertEquals("Spank Arm", snapshot.activitiesForGroup("ItemArms").single().displayName)
        val armItems = snapshot.itemsForGroup("ItemArms")
        assertEquals("Hemp Rope", armItems.single { it.name == "HempRope" }.displayName)
        assertTrue(armItems.any { it.name == "书包" })
        assertEquals(armItems.size, armItems.map { it.name }.distinct().size)
        assertEquals("Arms", snapshot.groups.single { it.name == "ItemArms" }.displayName)
    }

    @Test
    fun reportsMissingResourcesInsteadOfReturningEmptyCatalog() {
        val repository = BcCachedInteractionCatalogRepository(
            resourceReader = { _, _ -> null },
            clockMillis = { 42L },
        )

        val result = repository.refresh("R129")

        assertTrue(result is BcInteractionCatalogLoadResult.MissingResources)
        val missing = (result as BcInteractionCatalogLoadResult.MissingResources).missingPaths
        assertTrue(BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv in missing)
        assertTrue(BcResourcePaths.Assets.Female3DCG.Female3DCGCsv in missing)
    }
}
