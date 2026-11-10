package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.BcRoomSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BcRoomBackgroundCatalogRepositoryTest {
    @Test
    fun r129NativeTablesKeepSourceOrderAndDistinctRoomTagScopes() {
        val catalog = BcRoomBackgroundCatalogParser().parse("r129", nativeR129Source())

        assertEquals("R129", catalog.version)
        assertEquals(236, catalog.allNames.size)
        assertEquals(225, catalog.generalNames.size)
        assertEquals("AbandonedBuilding", catalog.generalNames.first())
        assertEquals("Yacht3", catalog.generalNames.last())
        assertEquals(
            listOf(
                "AsylumBedroom", "AsylumEntrance", "AsylumGGTSRoom", "AsylumMeeting", "AsylumTherapy",
                "LatexRoom", "OutsideCells", "PaddedCell", "PaddedCell2",
            ),
            catalog.asylumNames,
        )
        assertTrue("LatexRoom" in catalog.generalNames)
        assertTrue("MainHall" in catalog.generalNames)
        assertTrue("ClubCardPlayBoard1" in catalog.allNames)
        assertFalse("ClubCardPlayBoard1" in catalog.generalNames)
        assertFalse("HypnoticSpiral" in catalog.generalNames)
        assertFalse("HypnoSpiral2" in catalog.generalNames)
        assertSame(catalog.generalNames, catalog.namesForSpace(BcRoomSpace.Mixed))
        assertSame(catalog.generalNames, catalog.namesForSpace(BcRoomSpace.Female))
        assertSame(catalog.generalNames, catalog.namesForSpace(BcRoomSpace.Male))
        assertSame(catalog.asylumNames, catalog.namesForSpace(BcRoomSpace.Asylum))
    }

    @Test
    fun handlesCommentsQuotedKeysEscapesAndTrailingCommasWithoutReadingFakeDeclarations() {
        val source = """
            // const BackgroundsList = [{ Name: "Fake", Tag: [] }];
            const unrelated = "const BackgroundsList = [ ];";
            /* const BackgroundsTagList = []; */
            const BackgroundsTagIndoor = 'Indoor';
            const BackgroundsTagAsylum = "Asylum";
            const BackgroundsTagAdded = 'A new native tag';
            const BackgroundsTagList = [BackgroundsTagIndoor, BackgroundsTagAdded,];
            const BackgroundsList2 = [];
            const BackgroundsList = [
                { "Name": "Quoted\u0041", "Tag": [BackgroundsTagIndoor,] },
                { Name: 'Delimiter},]; // /*', Tag: ['A new native tag'] },
                { Name: 'Asylum', Tag: [BackgroundsTagAsylum] },
                { Name: 'Hidden', Tag: [] },
            ];
            function unrelated() { throw new Error(`Not a background expression`); }
        """.trimIndent()

        val catalog = BcRoomBackgroundCatalogParser().parse("R130", source)

        assertEquals(listOf("QuotedA", "Delimiter},]; // /*"), catalog.generalNames)
        assertEquals(listOf("Asylum"), catalog.asylumNames)
        assertEquals(setOf("QuotedA", "Delimiter},]; // /*", "Asylum", "Hidden"), catalog.allNames)
    }

    @Test
    fun malformedOrDynamicRowsNeverBecomeAPartialReadyCatalog() {
        val native = nativeR129Source()
        val invalidSources = listOf(
            native.replace("BackgroundsTagIndoor]", "UnboundTag]"),
            native.replace("Name: \"AbandonedBuilding\"", "Name: getBackgroundName()"),
            native.replace("Name: \"AbandonedSideRoom\"", "Name: \"AbandonedBuilding\""),
            native.replace("Tag: [BackgroundsTagIndoor]", "Tags: [BackgroundsTagIndoor]"),
            native.replace("const BackgroundsTagList =", "const BackgroundsTagList2 ="),
            native.replace("const BackgroundsList =", "const BackgroundsList2 ="),
            native.substringBefore("{ Name: \"Yacht3\""),
            native.replace("BackgroundsTagIndoor = \"Indoor\";", "BackgroundsTagIndoor = \"Indoor\" + dynamicValue;"),
        )

        invalidSources.forEach { source ->
            assertThrows(RuntimeException::class.java) {
                BcRoomBackgroundCatalogParser().parse("R129", source)
            }
        }
    }

    @Test
    fun snapshotsAreImmutableVersionedAndPeekDoesNotReadResources() {
        var reads = 0
        val sourceByVersion = mutableMapOf("R129" to nativeR129Source())
        val repository = BcCachedRoomBackgroundCatalogRepository(resourceReader = { version, path ->
            reads++
            assertEquals(BcResourcePaths.Backgrounds.BackgroundsJs, path)
            sourceByVersion[version]
        })
        assertNull(repository.currentSnapshot("R129"))
        assertEquals(0, reads)

        val ready = repository.refresh(" r129 ") as BcRoomBackgroundCatalogLoadResult.Ready
        repeat(5) { assertSame(ready.catalog, repository.currentSnapshot("R129")) }
        assertEquals(1, reads)
        assertThrows(UnsupportedOperationException::class.java) {
            (ready.catalog.generalNames as MutableList<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (ready.catalog.allNames as MutableSet<String>).clear()
        }
        assertNull(repository.currentSnapshot("R130"))

        sourceByVersion.remove("R129")
        val missing = repository.refresh("R129") as BcRoomBackgroundCatalogLoadResult.MissingResources
        assertEquals(listOf(BcResourcePaths.Backgrounds.BackgroundsJs), missing.missingPaths)
        assertNull(repository.currentSnapshot("R129"))
    }

    @Test
    fun unreadableOrIncompatibleResourceInvalidatesItsSnapshotWithoutBorrowingAnotherVersion() {
        var source = nativeR129Source()
        val repository = BcCachedRoomBackgroundCatalogRepository(resourceReader = { _, _ -> source })
        repository.refresh("R129")
        source = "const BackgroundsList = [];"

        assertTrue(repository.refresh("R130") is BcRoomBackgroundCatalogLoadResult.Failed)
        assertNull(repository.currentSnapshot("R130"))
        assertTrue(repository.currentSnapshot("R129") != null)
        assertTrue(repository.refresh("R129") is BcRoomBackgroundCatalogLoadResult.Failed)
        assertNull(repository.currentSnapshot("R129"))
    }

    private fun nativeR129Source(): String = requireNotNull(
        javaClass.getResourceAsStream("/R129-backgrounds.js"),
    ).bufferedReader().use { it.readText() }
}
