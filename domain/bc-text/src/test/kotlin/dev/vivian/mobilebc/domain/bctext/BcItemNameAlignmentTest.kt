package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BcItemNameAlignmentTest {
    @Test fun nativeAndEchoNamesAgreeAcrossChatAndCatalog() {
        val csv = """
            Hat,,Hats
            Hat,NativeHat,Native Hat
            Hat,NativeHat,Duplicate label
            Hat,BlankHat,
            EmptyGroup,,
        """.trimIndent()
        val echo = """{
            "groups":[{"group":"OverlayHat"}],
            "assets":[
              {"group":"Hat","name":"EchoHat","translation":{"EN":"A hat","CN":"帽子甲"}},
              {"group":"Hat","name":"BlankEchoHat"}
            ],
            "aliases":[{"group":"Hat","oldName":"OldEchoHat","name":"EchoHat"}],
            "mirrors":[{"group":"OverlayHat","from":"Hat"}]
        }"""
        val reader: (String, String) -> String? = { _, path ->
            when (path) {
                BcResourcePaths.Assets.Female3DCG.Female3DCGCsv -> csv
                BcResourcePaths.Assets.Female3DCG.Female3DCGCn -> "Hats\n帽子\nNative Hat\n原生帽"
                BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv -> "ActivityTest,Test"
                "echo/names.json" -> echo
                else -> null
            }
        }
        for (language in listOf("EN", "CN")) {
            val dictionary = BcCachedDictionaryValueResolver(reader) { language }
            val repository = BcCachedInteractionCatalogRepository(reader, { language })
            val snapshot = (repository.refresh("R133") as BcInteractionCatalogLoadResult.Ready).snapshot
            snapshot.items.forEach { item ->
                assertEquals(item.displayName, dictionary.assetDisplayName("R133", item.group, item.name))
            }
            snapshot.groups.forEach { group ->
                assertEquals(group.displayName, dictionary.groupDisplayName("R133", group.name))
            }
            assertEquals(if (language == "CN") "原生帽" else "Native Hat",
                dictionary.assetDisplayName("R133", "Hat", "NativeHat"))
            assertEquals("Blank Hat", dictionary.assetDisplayName("R133", "Hat", "BlankHat"))
            assertEquals("Blank Echo Hat", dictionary.assetDisplayName("R133", "Hat", "BlankEchoHat"))
            assertEquals(dictionary.assetDisplayName("R133", "Hat", "EchoHat"),
                dictionary.assetDisplayName("R133", "OverlayHat", "OldEchoHat"))
            assertEquals(snapshot.items.size, snapshot.items.distinctBy { it.group to it.name }.size)
            val hats = snapshot.items.filter { it.group == "Hat" }.map { it.displayName }
            assertEquals(hats.sortedBy { it.lowercase() }, hats)
        }
    }
}
