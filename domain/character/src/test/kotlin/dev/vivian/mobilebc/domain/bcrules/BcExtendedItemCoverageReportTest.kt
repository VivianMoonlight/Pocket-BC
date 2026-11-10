package dev.vivian.mobilebc.domain.bcrules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcExtendedItemCoverageReportTest {
    private val parser = BcRuleCatalogParser()

    @Test
    fun scansBundledR129ExtendedItemFixture() {
        val extendedJs = requireNotNull(
            javaClass.classLoader?.getResource("bc-fixtures/Female3DCGExtended-R129.js"),
        ).readText()
        val catalog = parser.parse(
            version = "R129",
            female3dJs = """
                var AssetFemale3DCG = [];
                var ActivityFemale3DCG = [];
            """.trimIndent(),
            female3dExtendedJs = extendedJs,
        )
        val report = BcExtendedItemCoverageReport.from(catalog)
        println(report.toDisplayText())

        assertTrue("Expected the R129 fixture to contain most known extended item definitions.", report.total >= 720)
        assertTrue("Typed items should dominate the fixture.", report.byArchetype.getValue(BcExtendedItemArchetype.Typed) > 400)
        assertTrue("Modular items should be parsed.", report.byArchetype.getValue(BcExtendedItemArchetype.Modular) > 200)
        assertTrue("Vibrating items should be parsed.", report.byArchetype.getValue(BcExtendedItemArchetype.Vibrating) >= 30)
        assertTrue("Custom/noarch/text items should be explicitly reported as unsupported.", report.unsupported > 0)
        assertTrue("Most typed/modular/vibrating items should be configurable.", report.configurable > 650)
    }

    @Test
    fun buildsStandardAndAdvancedVibratingOptions() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemVulva: {
                        VibratingEgg: {
                            Archetype: ExtendedArchetype.VIBRATING,
                        },
                    },
                };
            """.trimIndent(),
        )

        val extended = requireNotNull(catalog.extendedItem("ItemVulva", "VibratingEgg"))
        assertEquals(BcExtendedItemArchetype.Vibrating, extended.archetype)
        assertEquals(BcExtendedItemSupportLevel.Supported, extended.supportLevel)
        assertEquals(listOf("Off", "Low", "Medium", "High", "Maximum", "Random", "Escalate", "Tease", "Deny", "Edge"), extended.typedOptions.map { it.name })
        assertEquals("""{"TypeRecord":{"vibrating":0},"Mode":"Off","Intensity":-1,"Effect":["Egged"]}""", extended.defaultPropertyJson)
        assertEquals("""{"TypeRecord":{"vibrating":4},"Mode":"Maximum","Intensity":3,"Effect":["Egged","Vibrating"]}""", extended.typedOptions[4].propertyJson)
    }

    @Test
    fun standardOnlyVibratingOptionsRespectOptionsField() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemPelvis: {
                        TrainingBelt: {
                            Archetype: ExtendedArchetype.VIBRATING,
                            Options: [VibratorModeSet.STANDARD],
                        },
                    },
                };
            """.trimIndent(),
        )

        val extended = requireNotNull(catalog.extendedItem("ItemPelvis", "TrainingBelt"))
        assertEquals(listOf("Off", "Low", "Medium", "High", "Maximum"), extended.typedOptions.map { it.name })
    }

    @Test
    fun unsupportedArchetypesAreStillCataloged() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemNeck: {
                        SlaveCollar: {
                            Archetype: ExtendedArchetype.NOARCH,
                        },
                    },
                };
            """.trimIndent(),
        )

        val extended = requireNotNull(catalog.extendedItem("ItemNeck", "SlaveCollar"))
        assertEquals(BcExtendedItemArchetype.NoArch, extended.archetype)
        assertEquals(BcExtendedItemSupportLevel.Unsupported, extended.supportLevel)
        assertNotNull(extended.unsupportedReason)
        assertTrue(extended.typedOptions.isEmpty())
    }

    @Test
    fun partialOptionsReportSubscreenWarnings() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemDevices: {
                        SuspensionFrame: {
                            Archetype: ExtendedArchetype.TYPED,
                            Options: [
                                {
                                    Name: "Suspension",
                                    HasSubscreen: true,
                                    Property: { Difficulty: 8 },
                                    ArchetypeConfig: { Archetype: ExtendedArchetype.VARIABLEHEIGHT },
                                },
                            ],
                        },
                    },
                };
            """.trimIndent(),
        )

        val extended = requireNotNull(catalog.extendedItem("ItemDevices", "SuspensionFrame"))
        assertEquals(BcExtendedItemSupportLevel.Partial, extended.supportLevel)
        assertTrue(extended.typedOptions.single().warnings.single().contains("subscreen"))
        assertEquals("""{"TypeRecord":{"typed":0},"Difficulty":8}""", extended.defaultPropertyJson)
    }

    private fun female3dJs(): String = """
        var AssetFemale3DCG = [
            { Group: "ItemVulva", Asset: [{ Name: "VibratingEgg" }] },
            { Group: "ItemPelvis", Asset: [{ Name: "TrainingBelt" }] },
            { Group: "ItemNeck", Asset: [{ Name: "SlaveCollar" }] },
            { Group: "ItemDevices", Asset: [{ Name: "SuspensionFrame" }] },
        ];
        var ActivityFemale3DCG = [];
    """.trimIndent()
}
