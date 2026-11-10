package dev.vivian.mobilebc.domain.bctext

import org.junit.Assert.assertEquals
import org.junit.Test

class BcEchoDataNamesTest {
    @Test fun suppliedSnapshotNamesAreUsedByDictionaryAndInteractionCatalog() {
        val names = """{"groups":[{"group":"Hat","translation":{"CN":"帽子"}}],
          "assets":[{"group":"Hat","name":"NewEchoHat","translation":{"CN":"新帽子"}}],
          "aliases":[],"mirrors":[]}"""
        var nameReads = 0
        val reader: (String, String) -> String? = { _, path -> when {
            path == "echo/names.json" -> { nameReads++; names }
            path.endsWith("Female3DCG.csv") -> "Hat,,Hat\nHat,NativeHat,Native Hat"
            path.endsWith("ActivityDictionary.csv") -> "ActivityTest,Test"
            else -> null
        } }
        val resolver = BcCachedDictionaryValueResolver(reader) { "CN" }
        assertEquals("新帽子", resolver.assetDisplayName("R133", "Hat", "NewEchoHat"))
        assertEquals(1, nameReads)
        nameReads = 0
        val repository = BcCachedInteractionCatalogRepository(reader, { "CN" })
        repository.refresh("R133")
        assertEquals("新帽子", repository.currentSnapshot("R133")!!.items.single { it.name == "NewEchoHat" }.displayName)
        assertEquals(1, nameReads)
    }
}
