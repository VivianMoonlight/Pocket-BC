package dev.vivian.mobilebc.domain.bcrules

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class BcCachedRuleCatalogRepositoryTest {
    @Test
    fun refreshReportsMissingJsResources() {
        val repository = BcCachedRuleCatalogRepository(
            resourceReader = { _, _ -> null },
        )

        val result = repository.refresh("R129")

        assertTrue(result is BcRuleCatalogLoadResult.MissingResources)
        val missing = result as BcRuleCatalogLoadResult.MissingResources
        assertEquals(
            listOf(
                BcResourcePaths.Assets.Female3DCG.Female3DCGJs,
                BcResourcePaths.Assets.Female3DCG.Female3DCGExtendedJs,
            ),
            missing.missingPaths,
        )
    }

    @Test
    fun refreshCachesParsedCatalog() {
        val resources = mapOf(
            BcResourcePaths.Assets.Female3DCG.Female3DCGJs to """
                var AssetFemale3DCG = [
                    { Group: "ItemArms", ArousalZoneID: 7, MirrorActivitiesFrom: "ItemHands", Asset: [{ Name: "HempRope" }] },
                ];
                var ActivityFemale3DCG = [
                    { Name: "Spank", ActivityID: 12, Target: ["ItemArms"], TargetSelf: ["ItemArms"] },
                ];
            """.trimIndent(),
            BcResourcePaths.Assets.Female3DCG.Female3DCGExtendedJs to "var AssetFemale3DCGExtended = {};",
        )
        val repository = BcCachedRuleCatalogRepository(
            resourceReader = { _, path -> resources[path] },
        )

        val result = repository.refresh("r129")

        assertTrue(result is BcRuleCatalogLoadResult.Ready)
        val ready = result as BcRuleCatalogLoadResult.Ready
        assertEquals("R129", ready.catalog.version)
        assertTrue(ready.catalog.group("ItemArms") != null)
        assertTrue(ready.catalog.activity("Spank") != null)
        assertTrue(ready.catalog.asset("ItemArms", "HempRope") != null)
        assertEquals(7, ready.catalog.group("ItemArms")?.arousalZoneId)
        assertEquals("ItemHands", ready.catalog.group("ItemArms")?.mirrorActivitiesFrom)
        assertEquals(12, ready.catalog.activity("Spank")?.activityId)
        assertTrue(ready.catalog.activity("歪头")?.source == BcActivitySource.Echo)
        assertTrue(repository.currentSnapshot("R129") === ready.catalog)
    }

    @Test
    fun snapshotsAreSeparatedByLanguage() {
        val resources = mapOf(
            BcResourcePaths.Assets.Female3DCG.Female3DCGJs to """
                var AssetFemale3DCG = [
                    { Group: "ItemArms", Asset: [{ Name: "HempRope" }] },
                ];
                var ActivityFemale3DCG = [
                    { Name: "Spank", Target: ["ItemArms"] },
                ];
            """.trimIndent(),
            BcResourcePaths.Assets.Female3DCG.Female3DCGExtendedJs to "var AssetFemale3DCGExtended = {};",
        )
        var language = "CN"
        val repository = BcCachedRuleCatalogRepository(
            resourceReader = { _, path -> resources[path] },
            languageProvider = { language },
        )

        val chinese = (repository.refresh("R129") as BcRuleCatalogLoadResult.Ready).catalog
        language = "EN"
        assertNull(repository.currentSnapshot("R129"))
        val english = (repository.refresh("R129") as BcRuleCatalogLoadResult.Ready).catalog

        assertEquals("钻进怀里", chinese.activity("钻进怀里")?.displayName)
        assertEquals("Get In Arms", english.activity("钻进怀里")?.displayName)
    }

    @Test
    fun refreshRejectsSchemaThatParsesNoNativeCatalog() {
        val resources = mapOf(
            BcResourcePaths.Assets.Female3DCG.Female3DCGJs to "var RenamedAssetTable = []; var RenamedActivityTable = [];",
            BcResourcePaths.Assets.Female3DCG.Female3DCGExtendedJs to "var AssetFemale3DCGExtended = {};",
        )
        val repository = BcCachedRuleCatalogRepository(
            resourceReader = { _, path -> resources[path] },
        )

        val result = repository.refresh("R999")

        assertTrue(result is BcRuleCatalogLoadResult.Failed)
        assertTrue((result as BcRuleCatalogLoadResult.Failed).reason.contains("incompatible"))
        assertNull(repository.currentSnapshot("R999"))
    }

    @Test
    fun refreshReportsEchoNativeNameCollisionsWithoutReplacingNativeRule() {
        val echoName = BcEchoActivityCatalog.rules.first().name
        val resources = mapOf(
            BcResourcePaths.Assets.Female3DCG.Female3DCGJs to """
                var AssetFemale3DCG = [
                    { Group: "ItemHood", Asset: [{ Name: "TestAsset" }] },
                ];
                var ActivityFemale3DCG = [
                    { Name: "$echoName", Target: ["ItemHood"] },
                ];
            """.trimIndent(),
            BcResourcePaths.Assets.Female3DCG.Female3DCGExtendedJs to "var AssetFemale3DCGExtended = {};",
        )
        val repository = BcCachedRuleCatalogRepository(
            resourceReader = { _, path -> resources[path] },
        )

        val ready = repository.refresh("R999") as BcRuleCatalogLoadResult.Ready

        assertEquals(setOf(echoName), ready.catalog.health.echoNativeCollisions)
        assertEquals(BcActivitySource.Native, ready.catalog.activity(echoName)?.source)
    }
}
