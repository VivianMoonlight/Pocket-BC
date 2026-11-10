package dev.vivian.mobilebc.domain.bcrules

import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcitemproperty.modificationOptionsPage
import dev.vivian.mobilebc.domain.bcitemproperty.readBcIntProperty
import dev.vivian.mobilebc.domain.bcitemproperty.readBcPropertyTokens
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcRuleCatalogParserTest {
    @Test fun timerLimitsAndDurationsFollowAssetCopyConfig() {
        val catalog = BcRuleCatalogParser().parse("R133", """
            var AssetFemale3DCG = [{ Group: "ItemMisc", Category: "Item", Asset: [
                { Name: "BaseTimer", IsLock: true, RemoveTimer: 300, MaxTimer: 14400 },
                { Name: "CopiedTimer", CopyConfig: { GroupName: "ItemMisc", AssetName: "BaseTimer" }, MaxTimer: 86400 },
            ] }];
        """.trimIndent())
        assertEquals(14400, catalog.asset("ItemMisc", "BaseTimer")!!.maxTimerSeconds)
        assertEquals(86400, catalog.asset("ItemMisc", "CopiedTimer")!!.maxTimerSeconds)
        assertEquals(300, catalog.asset("ItemMisc", "CopiedTimer")!!.removeTimerSeconds)
    }

    @Test fun restraintFlagsInheritFromGroupAndRespectAssetAndAppearanceOverrides() {
        val catalog = BcRuleCatalogParser().parse("R133", """
            var AssetFemale3DCG = [
                { Group: "ItemArms", Category: "Item", IsRestraint: true, Asset: [{ Name: "Rope" }, { Name: "Decoration", IsRestraint: false }] },
                { Group: "ItemHands", Category: "Item", Asset: [{ Name: "Mittens", IsRestraint: true }] },
                { Group: "Gloves", Category: "Appearance", IsRestraint: true, Asset: [{ Name: "Gloves", IsRestraint: true }] },
            ];
        """.trimIndent())
        assertTrue(catalog.asset("ItemArms", "Rope")!!.isRestraint)
        assertFalse(catalog.asset("ItemArms", "Decoration")!!.isRestraint)
        assertTrue(catalog.asset("ItemHands", "Mittens")!!.isRestraint)
        assertFalse(catalog.asset("Gloves", "Gloves")!!.isRestraint)
    }

    private val parser = BcRuleCatalogParser()

    @Test
    fun unlockFlagsAreParsedForItemsAndKeyholderLocks() {
        val catalog = parser.parse("R129", """
            var AssetFemale3DCG = [
                { Group: "ItemArms", Asset: [{ Name: "Cuffs", SelfUnlock: false }, { Name: "Rope" }] },
                { Group: "ItemMisc", Asset: [{ Name: "HighSecurityPadlock", ExclusiveUnlock: true }] },
            ];
        """.trimIndent())
        assertFalse(catalog.asset("ItemArms", "Cuffs")!!.selfUnlock)
        assertTrue(catalog.asset("ItemArms", "Rope")!!.selfUnlock)
        assertTrue(catalog.asset("ItemMisc", "HighSecurityPadlock")!!.exclusiveUnlock)
    }

    @Test
    fun wardrobeDefaultsIncludeBodyGroupsStringAssetsAndNormalizedLayerColors() {
        val catalog = parser.parse("R129", """
            var AssetFemale3DCG = [
                { Group: "Body", AllowNone: false, Color: ["White", "Black"], BodyCosplay: true, Asset: [
                    "",
                    { Name: "Colored", DefaultColor: ["#123456", "#abcdef", "Unused"], Layer: [
                        { Name: "Base" }, { Name: "Copy", CopyLayerColor: "Base" },
                        { Name: "Detail" }, { Name: "Hidden", AllowColorize: false },
                        { Name: "Mask", TextureMask: {} },
                    ] },
                    { Name: "Short", DefaultColor: ["#123456"], Layer: [{ Name: "First" }, { Name: "Second" }] },
                    { Name: "Empty", Layer: [] },
                ] },
                { Group: "Mirror", MirrorGroup: "Body", AllowCustomize: false, Default: false, Asset: ["Default"] },
                { Group: "ItemArms", Category: "Item", BodyCosplay: true, Asset: ["Restraint"] },
            ];
        """.trimIndent())

        val body = catalog.group("Body")!!
        assertEquals("Appearance", body.category)
        assertEquals("", body.defaultAssetName)
        assertEquals(listOf("White", "Black"), body.colorSchema)
        assertEquals("White", body.defaultColor)
        assertFalse(body.clothing)
        assertFalse(body.allowNone)
        assertTrue(body.bodyCosplay)
        assertTrue(body.allowCustomize)
        assertTrue(body.allowColorize)
        assertEquals(0.0, catalog.asset("Body", "")!!.value)
        assertEquals(listOf("White"), catalog.asset("Body", "")!!.defaultColor)
        assertEquals(listOf("#123456", "#abcdef"), catalog.asset("Body", "Colored")!!.defaultColor)
        assertEquals(listOf("#123456", "White"), catalog.asset("Body", "Short")!!.defaultColor)
        assertEquals(emptyList<String>(), catalog.asset("Body", "Empty")!!.defaultColor)
        assertEquals("Body", catalog.group("Mirror")!!.mirrorGroup)
        assertFalse(catalog.group("Mirror")!!.isDefault)
        assertFalse(catalog.group("Mirror")!!.allowCustomize)
        assertFalse(catalog.asset("ItemArms", "Restraint")!!.bodyCosplay)
    }

    @Test
    fun wardrobeCopyConfigMutationsFinishBeforeEarlierAssetsArePublished() {
        val catalog = parser.parse("R129", """
            var AssetFemale3DCG = [{ Group: "Body", Asset: [
                { Name: "Root", Value: 500, InventoryID: 17, Enable: false, AvailableLocations: ["Asylum"] },
                { Name: "Copy", CopyConfig: { AssetName: "Middle", BuyGroup: true } },
                { Name: "Middle", CopyConfig: { AssetName: "Root" } },
                { Name: "Independent", CopyConfig: { AssetName: "Root" }, BuyGroup: "Other", Value: 0 },
            ] }];
        """.trimIndent())

        assertEquals(500.0, catalog.asset("Body", "Root")!!.value)
        assertEquals("Root", catalog.asset("Body", "Root")!!.buyGroup)
        listOf("Copy", "Middle").forEach { name ->
            val asset = catalog.asset("Body", name)!!
            assertEquals(-1.0, asset.value)
            assertEquals("Root", asset.buyGroup)
            assertEquals(17, asset.inventoryId)
            assertEquals(setOf("Asylum"), asset.availableLocations)
            assertFalse(asset.enable)
            assertTrue(asset.unsupportedWardrobeMetadata.isEmpty())
        }
        assertEquals(0.0, catalog.asset("Body", "Independent")!!.value)
        assertEquals("Other", catalog.asset("Body", "Independent")!!.buyGroup)
    }

    @Test
    fun wardrobeMetadataDoesNotMakeUnknownPricesOrScriptsFree() {
        val catalog = parser.parse("R129", """
            var AssetFemale3DCG = [
                { Group: "Dynamic", Color: selectColors(), AllowNone: decide(), Asset: [
                    { Name: "Price", Value: getPrice(), InventoryID: 1.5 },
                    { Name: "Location", AvailableLocations: [getLocation()], Enable: isEnabled() },
                    { Name: "Color", Layer: getLayers(), DefaultColor: chooseColor() },
                    { Name: "Copy", CopyConfig: { AssetName: "Price", BuyGroup: decide() } },
                    { Name: "BooleanExpression", Enable: true && isEnabled(), Value: 0 + getPrice() },
                    { Name: "Shorthand", Value },
                    { Name: "Spread", ...defaults },
                ] },
            ];
        """.trimIndent())

        assertEquals(setOf("Color", "AllowNone", "Asset"), catalog.group("Dynamic")!!.unsupportedWardrobeMetadata)
        val price = catalog.asset("Dynamic", "Price")!!
        assertEquals(null, price.value)
        assertEquals(null, price.inventoryId)
        assertTrue(price.unsupportedWardrobeMetadata.containsAll(setOf("Value", "InventoryID", "DefaultColor")))
        assertTrue(catalog.asset("Dynamic", "Location")!!.unsupportedWardrobeMetadata.containsAll(setOf("AvailableLocations", "Enable")))
        assertEquals(null, catalog.asset("Dynamic", "Copy")!!.value)
        assertTrue("CopyConfig.BuyGroup" in catalog.asset("Dynamic", "Copy")!!.unsupportedWardrobeMetadata)
        assertEquals(null, catalog.asset("Dynamic", "BooleanExpression")!!.value)
        assertTrue("Enable" in catalog.asset("Dynamic", "BooleanExpression")!!.unsupportedWardrobeMetadata)
        listOf("Shorthand", "Spread").forEach {
            assertEquals(null, catalog.asset("Dynamic", it)!!.value)
            assertTrue("CopyConfig" in catalog.asset("Dynamic", it)!!.unsupportedWardrobeMetadata)
        }
    }

    @Test
    fun fullR129WardrobeMetadataMatchesNativeCatalogBuilders() {
        val source = javaClass.getResource("/bc-fixtures/WardrobeCatalog-R129.js")!!.readText()
        val catalog = parser.parse("R129", source)
        assertEquals(85, catalog.groups.size)
        assertEquals(2411, catalog.assets.size)
        val expected = javaClass.getResource("/bc-fixtures/WardrobeMetadata-R129.tsv")!!.readText().lineSequence().filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        assertEquals(2496, expected.size)
        expected.forEach { row ->
            val columns = row.split('\t')
            val actual = if (columns[0] == "G") {
                val group = catalog.group(columns[1])!!
                assertEquals("Group ${group.name}", emptySet<String>(), group.unsupportedWardrobeMetadata)
                assertEquals("Group ${group.name}", emptySet<String>(), group.unsupportedSpeechMetadata)
                listOf("G", group.name, group.category, group.clothing, group.allowNone, group.mirrorGroup ?: "<null>", group.defaultAssetName ?: "<null>", group.colorSchema.joinToString(","), group.defaultColor, group.bodyCosplay, group.underwear, group.allowCustomize, group.allowColorize, group.isDefault, group.effects.joinToString(","))
            } else {
                val asset = catalog.asset(columns[1], columns[2])!!
                assertEquals("Asset ${asset.group}/${asset.name}", emptySet<String>(), asset.unsupportedWardrobeMetadata)
                assertEquals("Asset ${asset.group}/${asset.name}", emptySet<String>(), asset.unsupportedSpeechMetadata)
                listOf("A", asset.group, asset.name, asset.value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }, asset.buyGroup ?: "<null>", asset.availableLocations.joinToString(","), asset.enable, asset.bodyCosplay, asset.defaultColor!!.joinToString(","), asset.inventoryId ?: "<null>", asset.effects.joinToString(","))
            }
            assertEquals(row, actual.joinToString("\t"))
        }
        assertEquals(21, catalog.groups.values.count { it.category == "Appearance" && !it.allowNone })
    }

    @Test
    fun speechEffectsUseRuntimeAssetInheritanceWithVerifiedNativeConstants() {
        val catalog = parser.parse("R129", """
            const E = /** @type {const} */ ({ GagNormal: "GagNormal", Egged: "Egged" });
            var AssetFemale3DCG = [
                { Group: "ItemMouth", Effect: [E.GagNormal /* base */], Asset: [
                    { Name: "Inherited" },
                    { Name: "Null", Effect: null },
                    { Name: "Empty", Effect: [] },
                    { Name: "Override", Effect: [E.Egged], AllowEffect: ["GagHeavy", E.GagNormal] },
                ] },
            ];
        """.trimIndent())

        assertEquals(setOf("GagNormal"), catalog.group("ItemMouth")?.effects)
        listOf("Inherited", "Null").forEach { assertEquals(setOf("GagNormal"), catalog.asset("ItemMouth", it)?.effects) }
        assertEquals(emptySet<String>(), catalog.asset("ItemMouth", "Empty")?.effects)
        assertEquals(setOf("Egged"), catalog.asset("ItemMouth", "Override")?.effects)
        assertEquals(setOf("GagHeavy", "GagNormal"), catalog.asset("ItemMouth", "Override")?.allowedEffects)
        assertEquals(emptySet<String>(), catalog.asset("ItemMouth", "Override")?.unsupportedSpeechMetadata)
    }

    @Test
    fun speechEffectsResolveCopyConfigChainsBeforeDestinationGroupFallback() {
        val catalog = parser.parse("R129", """
            var AssetFemale3DCG = [
                { Group: "Source", Effect: ["GagLight"], Asset: [
                    { Name: "Explicit", Effect: ["RegressedTalk"], AllowEffect: ["GagHeavy"] },
                    { Name: "GroupOnly" },
                ] },
                { Group: "Target", Effect: ["GagHeavy"], Asset: [
                    { Name: "Copy", CopyConfig: { GroupName: "Source", AssetName: "Explicit" } },
                    { Name: "Chain", CopyConfig: { AssetName: "Copy" } },
                    { Name: "Empty", CopyConfig: { AssetName: "Copy" }, Effect: [] },
                    { Name: "Fallback", CopyConfig: { GroupName: "Source", AssetName: "GroupOnly" } },
                    { Name: "Missing", CopyConfig: { AssetName: "DoesNotExist" } },
                    { Name: "CycleA", CopyConfig: { AssetName: "CycleB" } },
                    { Name: "CycleB", CopyConfig: { AssetName: "CycleA" } },
                ] },
            ];
        """.trimIndent())

        listOf("Copy", "Chain").forEach {
            assertEquals(setOf("RegressedTalk"), catalog.asset("Target", it)?.effects)
            assertEquals(setOf("GagHeavy"), catalog.asset("Target", it)?.allowedEffects)
        }
        assertEquals(emptySet<String>(), catalog.asset("Target", "Empty")?.effects)
        assertEquals(setOf("GagHeavy"), catalog.asset("Target", "Fallback")?.effects)
        listOf("Missing", "CycleA", "CycleB").forEach {
            assertEquals(setOf("CopyConfig"), catalog.asset("Target", it)?.unsupportedSpeechMetadata)
        }
    }

    @Test
    fun speechMetadataRejectsUnverifiedEffectExpressionsAndConstants() {
        val catalog = parser.parse("R129", """
            const E = ({ GagNormal: "GagNormal" });
            var AssetFemale3DCG = [
                { Group: "ItemMouth", Effect: ["GagLight"], Asset: [
                    { Name: "Call", Effect: ["GagNormal", selectEffect()] },
                    { Name: "UnknownConstant", Effect: [E.Future], AllowEffect: [Custom.GagHeavy] },
                    { Name: "Spread", Effect: [...effects] },
                    { Name: "Sparse", Effect: [, "GagNormal"] },
                    { Name: "Static", Effect: ["GagNormal", /* trailing */] },
                ] },
            ];
        """.trimIndent())

        listOf("Call", "Spread", "Sparse").forEach {
            assertEquals(setOf("Effect"), catalog.asset("ItemMouth", it)?.unsupportedSpeechMetadata)
        }
        assertEquals(setOf("Effect", "AllowEffect"), catalog.asset("ItemMouth", "UnknownConstant")?.unsupportedSpeechMetadata)
        assertEquals(emptySet<String>(), catalog.asset("ItemMouth", "Static")?.unsupportedSpeechMetadata)
    }

    @Test
    fun parsesNativeStringAssetDefinitionsAndCopyConfigDestinationOrder() {
        val catalog = parser.parse("R129", """
            var AssetFemale3DCG = [
                { Group: "Target", Effect: ["GagNormal"], Asset: [
                    "Shorthand",
                    { Name: "Origin", Effect: ["GagLight"] },
                    { Name: "Copy", CopyConfig: { GroupName: "Source", AssetName: "Base" } },
                ] },
                { Group: "Source", Asset: [
                    { Name: "Origin", Effect: ["GagHeavy"] },
                    { Name: "Base", CopyConfig: { GroupName: null, AssetName: "Origin" } },
                ] },
                { Group: "After", Asset: [
                    { Name: "Copy", CopyConfig: { GroupName: "Source", AssetName: "Base" } },
                ] },
            ];
        """.trimIndent())
        assertEquals(setOf("GagNormal"), catalog.asset("Target", "Shorthand")?.effects)
        assertEquals(setOf("GagLight"), catalog.asset("Target", "Copy")?.effects)
        assertEquals(setOf("GagHeavy"), catalog.asset("After", "Copy")?.effects)
    }

    @Test
    fun speechAllowedEffectsIncludeExtendedOptionsConfigCopiesAndSubscreens() {
        val catalog = parser.parse("R129", """
            const E = ({ GagHeavy: "GagHeavy" });
            var AssetFemale3DCG = [{ Group: "ItemMouth", Asset: [
                { Name: "Typed", Effect: ["BlockMouth"] },
                { Name: "Modular" }, { Name: "Copy" }, { Name: "NoArch" }, { Name: "Dynamic" },
            ] }];
        """.trimIndent(), """
            var AssetFemale3DCGExtended = { ItemMouth: {
                Typed: { Archetype: ExtendedArchetype.TYPED, Options: [
                    { Name: "Base", Property: { Effect: ["GagNormal"] },
                      ArchetypeConfig: { Archetype: ExtendedArchetype.NOARCH, AllowEffect: [E.GagHeavy] } },
                ] },
                Modular: { Archetype: ExtendedArchetype.MODULAR, AllowEffect: ["GagLight"], Modules: [
                    { Name: "Gag", Key: "g", Options: [{}, { Property: { Effect: ["GagTotal"] } }] },
                ] },
                Copy: { Archetype: ExtendedArchetype.MODULAR, CopyConfig: { AssetName: "Modular" } },
                NoArch: { Archetype: ExtendedArchetype.NOARCH, AllowEffect: ["GagMedium"] },
                Dynamic: { Archetype: ExtendedArchetype.TYPED, Options: buildOptions([{ Property: { Effect: ["GagTotal"] } }]) },
            } };
        """.trimIndent())

        assertEquals(setOf("BlockMouth", "GagNormal", "GagHeavy"), catalog.asset("ItemMouth", "Typed")?.allowedEffects)
        listOf("Modular", "Copy").forEach {
            assertEquals(setOf("GagLight", "GagTotal"), catalog.asset("ItemMouth", it)?.allowedEffects)
            assertEquals(emptySet<String>(), catalog.asset("ItemMouth", it)?.unsupportedSpeechMetadata)
        }
        assertEquals(setOf("GagMedium"), catalog.asset("ItemMouth", "NoArch")?.allowedEffects)
        assertEquals(setOf("AllowEffect"), catalog.asset("ItemMouth", "Dynamic")?.unsupportedSpeechMetadata)
    }

    @Test
    fun speechAllowedEffectsFollowNativeVibratorModeSets() {
        val catalog = parser.parse("R129", """
            var AssetFemale3DCG = [{ Group: "ItemPelvis", Asset: [
                { Name: "Standard" }, { Name: "Advanced" }, { Name: "Both" }, { Name: "None" }, { Name: "Unknown" },
            ] }];
        """.trimIndent(), """
            var AssetFemale3DCGExtended = { ItemPelvis: {
                Standard: { Archetype: ExtendedArchetype.VIBRATING, Options: [VibratorModeSet.STANDARD] },
                Advanced: { Archetype: ExtendedArchetype.VIBRATING, Options: ["Advanced"] },
                Both: { Archetype: ExtendedArchetype.VIBRATING },
                None: { Archetype: ExtendedArchetype.VIBRATING, Options: [] },
                Unknown: { Archetype: ExtendedArchetype.VIBRATING, Options: [VibratorModeSet.FUTURE] },
            } };
        """.trimIndent())
        assertEquals(setOf("Egged", "Vibrating"), catalog.asset("ItemPelvis", "Standard")?.allowedEffects)
        listOf("Advanced", "Both").forEach {
            assertEquals(setOf("Egged", "Vibrating", "Edged"), catalog.asset("ItemPelvis", it)?.allowedEffects)
        }
        assertEquals(emptySet<String>(), catalog.asset("ItemPelvis", "None")?.allowedEffects)
        assertEquals(setOf("AllowEffect"), catalog.asset("ItemPelvis", "Unknown")?.unsupportedSpeechMetadata)
    }

    @Test
    fun parsesNativePoseCategoriesAndBothMenuFlags() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs() + """
                var PoseFemale3DCG = [
                    { Name: "BaseUpper", Category: "BodyUpper", AllowMenu: true },
                    {
                        Name: "Kneel", Category: "BodyLower", AllowMenu: true,
                        OverrideHeight: { Height: -250, Priority: 20 },
                    },
                    {
                        Name: "AllFours", Category: "BodyFull", AllowMenuTransient: true,
                        MovePosition: [{ Group: "Head", X: 0, Y: 1 }],
                    },
                    { Name: "Suspension", Category: "BodyAddon" },
                    { Name: "TapedHands", Category: "BodyHands", AllowMenu: false, AllowMenuTransient: false },
                    { Name: "FuturePose" },
                ];
            """.trimIndent(),
        )

        assertEquals(6, catalog.poses.size)
        assertEquals(BcPoseRule("BaseUpper", "BodyUpper", allowMenu = true), catalog.pose("BaseUpper"))
        assertEquals(BcPoseRule("Kneel", "BodyLower", allowMenu = true), catalog.pose("Kneel"))
        assertEquals(BcPoseRule("AllFours", "BodyFull", allowMenuTransient = true), catalog.pose("AllFours"))
        assertEquals(BcPoseRule("Suspension", "BodyAddon"), catalog.pose("Suspension"))
        assertEquals(BcPoseRule("TapedHands", "BodyHands"), catalog.pose("TapedHands"))
        assertEquals(BcPoseRule("FuturePose"), catalog.pose("FuturePose"))
        assertEquals(null, catalog.pose("Head"))
    }

    @Test
    fun preservesRawGroupExpressionMetadataForAssetFallback() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(assets = """
                {
                    Group: "Pussy",
                    AllowExpression: [null, "Hard"],
                    ExpressionPrerequisite: ["CanBeLimp", "CanHaveErection"],
                    Asset: [
                        { Name: "Penis" },
                        { Name: "Inherited", AllowExpression: null /* fallback */, ExpressionPrerequisite: null },
                    ],
                },
                { Group: "BodyUpper", Asset: [{ Name: "Normal" }] },
            """.trimIndent()),
        )

        assertEquals(listOf(null, "Hard"), catalog.group("Pussy")?.allowExpression)
        assertEquals(listOf("CanBeLimp", "CanHaveErection"), catalog.group("Pussy")?.expressionPrerequisites)
        assertEquals(emptySet<String>(), catalog.group("Pussy")?.unsupportedExpressionMetadata)
        listOf("Penis", "Inherited").forEach { name ->
            assertEquals(null, catalog.asset("Pussy", name)?.allowExpression)
            assertEquals(null, catalog.asset("Pussy", name)?.expressionPrerequisites)
            assertEquals(emptySet<String>(), catalog.asset("Pussy", name)?.unsupportedExpressionMetadata)
        }
        assertEquals(null, catalog.group("BodyUpper")?.allowExpression)
        assertEquals(null, catalog.group("BodyUpper")?.expressionPrerequisites)
    }

    @Test
    fun keepsExplicitEmptyAssetExpressionListsAndAlignedNullEntries() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(assets = """
                {
                    Group: "Eyes",
                    AllowExpression: ["Closed"],
                    ExpressionPrerequisite: ["GroupRequirement"],
                    Asset: [
                        { Name: "Disabled", AllowExpression: [], ExpressionPrerequisite: [] },
                        {
                            Name: "Custom",
                            AllowExpression: [null /* default */, 'Soft', null],
                            ExpressionPrerequisite: [null, "CanSmile" /* soft */, null],
                        },
                    ],
                },
                { Group: "Blush", AllowExpression: [], ExpressionPrerequisite: [], Asset: [{ Name: "None" }] },
            """.trimIndent()),
        )

        val disabled = requireNotNull(catalog.asset("Eyes", "Disabled"))
        assertEquals(emptyList<String?>(), disabled.allowExpression)
        assertEquals(emptyList<String?>(), disabled.expressionPrerequisites)
        val custom = requireNotNull(catalog.asset("Eyes", "Custom"))
        assertEquals(listOf(null, "Soft", null), custom.allowExpression)
        assertEquals(listOf(null, "CanSmile", null), custom.expressionPrerequisites)
        assertEquals(emptyList<String?>(), catalog.group("Blush")?.allowExpression)
        assertEquals(emptyList<String?>(), catalog.group("Blush")?.expressionPrerequisites)
    }

    @Test
    fun flagsUnsupportedExpressionMetadataInsteadOfTreatingItAsAbsentOrEmpty() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(assets = """
                {
                    Group: "Eyes", AllowExpression: ["Closed"],
                    ExpressionPrerequisite: [["DynamicRequirement"]],
                    Asset: [
                        { Name: "Mixed", AllowExpression: ["Soft", computeExpression()] },
                        { Name: "Scalar", AllowExpression: false, ExpressionPrerequisite: "Unknown" },
                        { Name: "Sparse", AllowExpression: [, "Closed"], ExpressionPrerequisite: [null, , null] },
                        { Name: "Static", AllowExpression: ["Soft", /* trailing comma */] /* array */, ExpressionPrerequisite: [null] },
                    ],
                },
            """.trimIndent()),
        )

        assertEquals(setOf("ExpressionPrerequisite"), catalog.group("Eyes")?.unsupportedExpressionMetadata)
        assertEquals(null, catalog.group("Eyes")?.expressionPrerequisites)
        assertEquals(setOf("AllowExpression"), catalog.asset("Eyes", "Mixed")?.unsupportedExpressionMetadata)
        listOf("Scalar", "Sparse").forEach { name ->
            assertEquals(setOf("AllowExpression", "ExpressionPrerequisite"), catalog.asset("Eyes", name)?.unsupportedExpressionMetadata)
            assertEquals(null, catalog.asset("Eyes", name)?.allowExpression)
            assertEquals(null, catalog.asset("Eyes", name)?.expressionPrerequisites)
        }
        assertEquals(emptySet<String>(), catalog.asset("Eyes", "Static")?.unsupportedExpressionMetadata)
        assertEquals(listOf("Soft"), catalog.asset("Eyes", "Static")?.allowExpression)
        assertEquals(listOf<String?>(null), catalog.asset("Eyes", "Static")?.expressionPrerequisites)
    }

    @Test
    fun normalizesSetPoseIntoOrderedAllowedAssetPoses() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(assets = """
                { Group: "ItemArms", Asset: [
                    { Name: "Fixed", SetPose: ["BackCuffs"] },
                    { Name: "Choice", SetPose: ["BackCuffs", "Yoked"], AllowActivePose: ["Yoked", "OverTheHead"] },
                ] },
            """.trimIndent()),
        )

        val fixed = requireNotNull(catalog.asset("ItemArms", "Fixed"))
        assertEquals(listOf("BackCuffs"), fixed.setPose.toList())
        assertEquals(listOf("BackCuffs"), fixed.allowActivePose.toList())
        val choice = requireNotNull(catalog.asset("ItemArms", "Choice"))
        assertEquals(listOf("BackCuffs", "Yoked"), choice.setPose.toList())
        assertEquals(listOf("BackCuffs", "Yoked", "OverTheHead"), choice.allowActivePose.toList())
    }

    @Test
    fun selectsFirstAllowedPoseOnlyWhenSetPoseIsAbsent() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(assets = """
                { Group: "ItemLegs", Asset: [
                    { Name: "Choice", AllowActivePose: ["Kneel", "LegsClosed"] },
                    { Name: "Empty", SetPose: [], AllowActivePose: ["Kneel", "LegsClosed"] },
                    { Name: "None", AllowActivePose: [] },
                ] },
            """.trimIndent()),
        )

        val choice = requireNotNull(catalog.asset("ItemLegs", "Choice"))
        assertEquals(listOf("Kneel"), choice.setPose.toList())
        assertEquals(listOf("Kneel", "LegsClosed", "Hogtied", "AllFours"), choice.allowActivePose.toList())
        val empty = requireNotNull(catalog.asset("ItemLegs", "Empty"))
        assertTrue(empty.setPose.isEmpty())
        assertEquals(choice.allowActivePose, empty.allowActivePose)
        val none = requireNotNull(catalog.asset("ItemLegs", "None"))
        assertTrue(none.setPose.isEmpty())
        assertTrue(none.allowActivePose.isEmpty())
    }

    @Test
    fun inheritsGroupSetPoseForMissingAndNullAssetsWhileRespectingExplicitEmptyAndOverrides() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(assets = """
                { Group: "ItemLegs", SetPose: ["Kneel"], AllowActivePose: ["Spread"], Asset: [
                    { Name: "Inherited", AllowActivePose: ["LegsClosed"] },
                    { Name: "Null", SetPose: null /* inherit */ },
                    { Name: "Empty", SetPose: [], AllowActivePose: ["LegsClosed"] },
                    { Name: "Override", SetPose: ["BackCuffs"] },
                ] },
                { Group: "ItemArms", SetPose: null, AllowActivePose: ["BackBoxTie"], Asset: [{ Name: "None" }] },
            """.trimIndent()),
        )

        val inherited = requireNotNull(catalog.asset("ItemLegs", "Inherited"))
        assertEquals(setOf("Kneel"), inherited.setPose)
        assertEquals(listOf("Kneel", "LegsClosed", "Hogtied", "AllFours"), inherited.allowActivePose.toList())
        val nullPose = requireNotNull(catalog.asset("ItemLegs", "Null"))
        assertEquals(setOf("Kneel"), nullPose.setPose)
        assertEquals(setOf("Kneel", "Hogtied", "AllFours"), nullPose.allowActivePose)
        val empty = requireNotNull(catalog.asset("ItemLegs", "Empty"))
        assertTrue(empty.setPose.isEmpty())
        assertEquals(setOf("LegsClosed"), empty.allowActivePose)
        val overridden = requireNotNull(catalog.asset("ItemLegs", "Override"))
        assertEquals(setOf("BackCuffs"), overridden.setPose)
        assertEquals(setOf("BackCuffs"), overridden.allowActivePose)
        val none = requireNotNull(catalog.asset("ItemArms", "None"))
        assertTrue(none.setPose.isEmpty())
        assertTrue(none.allowActivePose.isEmpty())
    }

    @Test
    fun derivesNativeFullBodyPoseCombinationsUsingCatalogCategories() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(assets = """
                { Group: "ItemArms", Asset: [
                    { Name: "Upper", SetPose: ["BackElbowTouch"] },
                    { Name: "Together", SetPose: ["Kneel"], AllowActivePose: ["BackElbowTouch"] },
                    { Name: "OtherUpper", SetPose: ["Kneel"], AllowActivePose: ["BackCuffs"] },
                    { Name: "OtherLower", SetPose: ["BackElbowTouch"], AllowActivePose: ["LegsClosed"] },
                    { Name: "Suspended", SetPose: ["Suspension"] },
                    { Name: "CustomUpper", SetPose: ["Kneel"], AllowActivePose: ["FutureUpper"] },
                ] },
            """.trimIndent()) + """
                var PoseFemale3DCG = [{ Name: "FutureUpper", Category: "BodyUpper" }];
            """.trimIndent(),
        )

        assertEquals(setOf("BackElbowTouch", "Hogtied"), catalog.asset("ItemArms", "Upper")?.allowActivePose)
        assertEquals(setOf("Kneel", "BackElbowTouch", "Hogtied"), catalog.asset("ItemArms", "Together")?.allowActivePose)
        assertEquals(setOf("Kneel", "BackCuffs"), catalog.asset("ItemArms", "OtherUpper")?.allowActivePose)
        assertEquals(setOf("BackElbowTouch", "LegsClosed"), catalog.asset("ItemArms", "OtherLower")?.allowActivePose)
        assertEquals(setOf("Suspension"), catalog.asset("ItemArms", "Suspended")?.allowActivePose)
        assertEquals(setOf("Kneel", "FutureUpper"), catalog.asset("ItemArms", "CustomUpper")?.allowActivePose)
    }

    @Test
    fun parsesActivityTargetsAndPrerequisites() {
        val catalog = parser.parse(
            version = "r129",
            female3dJs = female3dJs(
                activities = """
                    {
                        Name: "ShockItem",
                        Target: ["ItemNeck"],
                        TargetSelf: [],
                        Prerequisite: ["Needs-ShockItem"],
                        MakeSound: true,
                        StimulationAction: "Shock",
                    },
                """.trimIndent(),
            ),
        )

        val activity = catalog.activity("ShockItem")!!
        assertEquals(setOf("ItemNeck"), activity.targetGroups)
        assertEquals(setOf("Needs-ShockItem"), activity.prerequisites)
        assertTrue(activity.makeSound)
        assertEquals("Shock", activity.stimulationAction)
    }

    @Test
    fun parsesGroupsAndAssetRuleFields() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(
                assets = """
                    {
                        Group: "ItemNeckAccessories",
                        Category: "Accessories",
                        Clothing: false,
                        AllowNone: false,
                        Asset: [
                            {
                                Name: "CollarShockUnit",
                                Effect: [E.TriggerShock],
                                Block: ["ItemMouth"],
                                AllowActivity: ["ShockItem"],
                                AllowActivityOn: ["ItemNeck"],
                                Prerequisite: ["Collared"],
                                OwnerOnly: true,
                                Enable: false,
                            },
                        ],
                    },
                """.trimIndent(),
            ),
        )

        val group = catalog.group("ItemNeckAccessories")!!
        assertFalse(group.allowNone)
        assertEquals("Accessories", group.category)

        val asset = catalog.asset("ItemNeckAccessories", "CollarShockUnit")!!
        assertEquals(setOf("TriggerShock"), asset.effects)
        assertEquals(setOf("ItemMouth"), asset.blocks)
        assertEquals(setOf("ShockItem"), asset.allowActivity)
        assertEquals(setOf("ItemNeck"), asset.allowActivityOn)
        assertEquals(setOf("Collared"), asset.prerequisites)
        assertTrue(asset.ownerOnly)
        assertFalse(asset.enable)
    }

    @Test
    fun classifiesNativeItemClothingAndAppearanceGroups() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(
                assets = """
                    { Group: "ItemMouth", Category: "Item", Asset: [{ Name: "Gag" }] },
                    { Group: "Cloth", Clothing: true, Asset: [{ Name: "Dress" }] },
                    { Group: "BodyUpper", Category: "Appearance", Asset: [{ Name: "Normal" }] },
                """.trimIndent(),
            ),
        )

        assertEquals(BcAppearanceGroupKind.Item, catalog.group("ItemMouth")?.kind)
        assertEquals(BcAppearanceGroupKind.Clothing, catalog.group("Cloth")?.kind)
        assertEquals(BcAppearanceGroupKind.Appearance, catalog.group("BodyUpper")?.kind)
    }

    @Test
    fun parsesSingleAndMirroredInteractionZones() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(
                assets = """
                    {
                        Group: "ItemHead",
                        Zone: [[175, 0, 150, 65]],
                        Asset: [{ Name: "Blindfold" }],
                    },
                    {
                        Group: "ItemArms",
                        Zone: [
                            [10, 200, 90, 200],
                            [400, 200, 90, 200],
                        ],
                        Asset: [{ Name: "Rope" }],
                    },
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(BcInteractionZoneRect(175, 0, 150, 65)),
            catalog.group("ItemHead")?.zones,
        )
        assertEquals(
            listOf(
                BcInteractionZoneRect(10, 200, 90, 200),
                BcInteractionZoneRect(400, 200, 90, 200),
            ),
            catalog.group("ItemArms")?.zones,
        )
    }

    @Test
    fun mergesExtendedItemAllowActivityHints() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(
                assets = """
                    {
                        Group: "ItemHandheld",
                        Asset: [
                            { Name: "Remote" },
                        ],
                    },
                """.trimIndent(),
            ),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemHandheld: {
                        Remote: {
                            Options: [
                                { Property: { AllowActivity: ["ShockItem"], AllowActivityOn: ["ItemNeck"] } },
                            ],
                        },
                    },
                };
            """.trimIndent(),
        )

        val asset = catalog.asset("ItemHandheld", "Remote")!!
        assertEquals(setOf("ShockItem"), asset.allowActivity)
        assertEquals(setOf("ItemNeck"), asset.allowActivityOn)
    }


    @Test
    fun parsesTypedExtendedItemOptions() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(
                assets = """
                    {
                        Group: "ItemArms",
                        Asset: [
                            { Name: "Ribbons" },
                        ],
                    },
                """.trimIndent(),
            ),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemArms: {
                        Ribbons: {
                            Archetype: ExtendedArchetype.TYPED,
                            Options: [
                                { Name: "Loose" },
                                { Name: "Tight", Property: { Effect: [E.BlockHands], Difficulty: 4 } },
                            ],
                        },
                    },
                };
            """.trimIndent(),
        )

        val extended = catalog.extendedItem("ItemArms", "Ribbons")!!
        assertEquals(BcExtendedItemArchetype.Typed, extended.archetype)
        assertTrue(extended.isConfigurable)
        assertEquals("Loose", extended.typedOptions[0].name)
        assertEquals("""{"TypeRecord":{"typed":1},"Effect":["BlockHands"],"Difficulty":4}""", extended.typedOptions[1].propertyJson)
        assertEquals(4, extended.typedOptions[1].difficulty)
    }

    @Test
    fun parsesModularExtendedItemOptions() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(
                assets = """
                    {
                        Group: "ItemMouth",
                        Asset: [
                            { Name: "PanelGag" },
                        ],
                    },
                """.trimIndent(),
            ),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemMouth: {
                        PanelGag: {
                            Archetype: ExtendedArchetype.MODULAR,
                            Modules: [
                                {
                                    Name: "Gag",
                                    Key: "g",
                                    Options: [
                                        {},
                                        { Property: { Effect: [E.GagMedium] } },
                                    ],
                                },
                                {
                                    Name: "Timer",
                                    Key: "t",
                                    Options: [
                                        {},
                                        { Property: { Difficulty: 2 } },
                                    ],
                                },
                            ],
                        },
                    },
                };
            """.trimIndent(),
        )

        val extended = catalog.extendedItem("ItemMouth", "PanelGag")!!
        assertEquals(BcExtendedItemArchetype.Modular, extended.archetype)
        assertEquals(2, extended.modules.size)
        assertEquals("Gag", extended.modules[0].name)
        assertEquals(mapOf("g" to 1, "t" to 0), BcItemPropertyJson.readTypeRecord(extended.modules[0].options[1].propertyJson))
        assertEquals(setOf("GagMedium"), extended.modules[0].options[1].propertyJson.readBcPropertyTokens("Effect"))
        assertEquals(mapOf("g" to 0, "t" to 1), BcItemPropertyJson.readTypeRecord(extended.modules[1].options[1].propertyJson))
        assertEquals(2, extended.modules[1].options[1].propertyJson.readBcIntProperty("Difficulty"))
    }

    @Test
    fun modularPropertiesComposeAssetBaselineAndEverySelectedModuleWithNativeFieldRules() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(assets = """
                { Group: "ItemArms", Effect: [E.Slow], Asset: [{ Name: "Harness", Block: ["ItemHands"], Hide: ["Gloves"] }] },
            """.trimIndent()),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemArms: { Harness: {
                        Archetype: ExtendedArchetype.MODULAR,
                        BaselineProperty: { Difficulty: 1, HeightModifier: 2, Effect: [E.Slow], SetPose: ["Kneel"], Opacity: 0.3 },
                        Modules: [
                            { Name: "Left", Key: "l", Options: [ {}, { Property: { Difficulty: 2, HeightModifier: 3, Effect: [E.BlockHands], SetPose: ["Kneel", "BackBoxTie"], ShockLevel: 4, Opacity: 0.5 } } ] },
                            { Name: "Right", Key: "r", Options: [ {}, { Property: { Difficulty: 3, HeightModifier: 4, Effect: [E.Slow, E.MapSwim], AllowActivePose: ["Kneel"], Opacity: 0.7 } } ] },
                        ],
                    } },
                };
            """.trimIndent(),
        )
        val rule = requireNotNull(catalog.extendedItem("ItemArms", "Harness"))
        val current = """{"TypeRecord":{"l":1,"r":1},"ShockLevel":4,"LockedBy":"OwnerPadlock","Custom":"keep"}"""
        val sameLeft = rule.modificationOptionsPage(current).options.first { it.label == "Left: l1" }
        assertEquals(setOf("Slow", "BlockHands", "MapSwim", "Lock"), sameLeft.propertyJson.readBcPropertyTokens("Effect"))
        assertEquals(setOf("ItemHands"), sameLeft.propertyJson.readBcPropertyTokens("Block"))
        assertEquals(setOf("Gloves"), sameLeft.propertyJson.readBcPropertyTokens("Hide"))
        assertEquals(setOf("Kneel", "BackBoxTie"), sameLeft.propertyJson.readBcPropertyTokens("SetPose"))
        assertEquals(setOf("Kneel"), sameLeft.propertyJson.readBcPropertyTokens("AllowActivePose"))
        assertEquals(6, sameLeft.difficulty)
        assertEquals(6, sameLeft.propertyJson.readBcIntProperty("Difficulty"))
        assertEquals(9, sameLeft.propertyJson.readBcIntProperty("HeightModifier"))
        assertEquals("0.7", BcItemPropertyJson.readPropertyValue(sameLeft.propertyJson, "Opacity"))

        val clearedLeft = rule.modificationOptionsPage(current).options.first { it.label == "Left: l0" }
        assertEquals(null, clearedLeft.propertyJson.readBcIntProperty("ShockLevel"))
        assertEquals(4, clearedLeft.difficulty)
        assertEquals(6, clearedLeft.propertyJson.readBcIntProperty("HeightModifier"))
        assertEquals("OwnerPadlock", clearedLeft.propertyJson.readBcStringProperty("LockedBy"))
        assertEquals("keep", clearedLeft.propertyJson.readBcStringProperty("Custom"))
    }

    @Test
    fun modularAssetInitializationIgnoresNonArrayBaseFields() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(assets = """
                { Group: "ItemArms", Asset: [{ Name: "Harness", Effect: E.Slow, Block: false, Hide: "Gloves", AllowActivity: true, Attribute: "Custom" }] },
            """.trimIndent()),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemArms: { Harness: {
                        Archetype: ExtendedArchetype.MODULAR,
                        Modules: [{ Name: "Mode", Key: "m", Options: [{}, { Property: { Effect: [E.BlockHands] } }] }],
                    } },
                };
            """.trimIndent(),
        )
        val rule = requireNotNull(catalog.extendedItem("ItemArms", "Harness"))
        listOf("Effect", "Block", "Hide", "AllowActivity", "Attribute").forEach { key ->
            assertTrue(rule.defaultPropertyJson.readBcPropertyTokens(key).isEmpty())
        }
        assertEquals(setOf("BlockHands"), rule.modules.single().options[1].propertyJson.readBcPropertyTokens("Effect"))
    }

    @Test
    fun unsupportedComplexAndDynamicModularPropertiesAreNotOffered() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemArms: { Harness: {
                        Archetype: ExtendedArchetype.MODULAR,
                        Modules: [{ Name: "Mode", Key: "m", Options: [
                            {},
                            { Property: { DrawingTop: { Layer: { Kneel: 4 } } } },
                            { Property: { Effect: [computeEffect()] } },
                        ] }],
                    } },
                };
            """.trimIndent(),
        )
        val rule = requireNotNull(catalog.extendedItem("ItemArms", "Harness"))
        assertEquals(BcExtendedItemSupportLevel.Unsupported, rule.modules.single().options[1].supportLevel)
        assertEquals(BcExtendedItemSupportLevel.Unsupported, rule.modules.single().options[2].supportLevel)
        assertEquals(listOf("Mode: m0"), rule.modificationOptionsPage(null).options.map { it.label })
    }

    @Test
    fun anUnsupportedCurrentModuleDoesNotLeaveAnEmptyPageClaimingMoreOptions() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemArms: { Harness: {
                        Archetype: ExtendedArchetype.MODULAR,
                        Modules: [
                            { Name: "Left", Key: "l", Options: [{}, { Property: { Effect: [E.Slow] } }] },
                            { Name: "Right", Key: "r", Options: [{}, { Property: { DrawingTop: { Layer: { Kneel: 4 } } } }] },
                        ],
                    } },
                };
            """.trimIndent(),
        )
        val rule = requireNotNull(catalog.extendedItem("ItemArms", "Harness"))
        val current = """{"TypeRecord":{"l":0,"r":1}}"""
        val page = rule.modificationOptionsPage(current, limit = 1)
        assertEquals(listOf("Right: r0"), page.options.map { it.label })
        assertEquals(1, page.totalKnownOptions)
        assertFalse(page.hasMore)
        val exhausted = rule.modificationOptionsPage(current, offset = 1, limit = 1)
        assertTrue(exhausted.options.isEmpty())
        assertFalse(exhausted.hasMore)
    }

    @Test
    fun anOutdatedCurrentModuleIndexUsesTheNativeDefaultAndKeepsOtherTypeRecords() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemArms: { Harness: {
                        Archetype: ExtendedArchetype.MODULAR,
                        Modules: [
                            { Name: "Left", Key: "l", Options: [{}, { Property: { Effect: [E.Slow] } }] },
                            { Name: "Right", Key: "r", Options: [{}, { Property: { Effect: [E.MapSwim] } }] },
                        ],
                    } },
                };
            """.trimIndent(),
        )
        val rule = requireNotNull(catalog.extendedItem("ItemArms", "Harness"))
        val option = rule.modificationOptionsPage("""{"TypeRecord":{"l":0,"r":99,"subscreen":2}}""").options.first { it.label == "Left: l1" }
        assertEquals(mapOf("l" to 1, "r" to 0, "subscreen" to 2), BcItemPropertyJson.readTypeRecord(option.propertyJson))
        assertEquals(setOf("Slow"), option.propertyJson.readBcPropertyTokens("Effect"))
    }

    @Test
    fun copyConfigUsesLocalOptionsAndKeepsOverridesThroughAChain() {
        val extendedJs = requireNotNull(javaClass.classLoader?.getResource("bc-fixtures/Female3DCGExtended-R129.js")).readText()
        val catalog = parser.parse("R129", female3dJs(), extendedJs)

        assertEquals(listOf("None", "Wrist"), catalog.extendedItem("ItemArms", "SteelCuffs")!!.typedOptions.map { it.name })
        assertEquals(listOf("None", "Wrist"), catalog.extendedItem("ItemArms", "HighStyleSteelCuffs")!!.typedOptions.map { it.name })
        assertEquals(5, catalog.extendedItem("ItemArms", "LeatherCuffs")!!.typedOptions.size)
    }

    @Test
    fun copyConfigReplacesLocalObjectsShallowlyAndRejectsAnArchetypeMismatch() {
        val catalog = parser.parse(
            version = "R129",
            female3dJs = female3dJs(),
            female3dExtendedJs = """
                var AssetFemale3DCGExtended = {
                    ItemArms: {
                        Base: { Archetype: ExtendedArchetype.TYPED, BaselineProperty: { Effect: [E.Slow] }, Options: [{ Name: "None" }] },
                        Local: { Archetype: ExtendedArchetype.TYPED, CopyConfig: { AssetName: "Base" }, BaselineProperty: { Difficulty: 3 } },
                        Wrong: { Archetype: ExtendedArchetype.MODULAR, CopyConfig: { AssetName: "Base" } },
                    },
                };
            """.trimIndent(),
        )
        val local = requireNotNull(catalog.extendedItem("ItemArms", "Local"))
        assertTrue(local.defaultPropertyJson.readBcPropertyTokens("Effect").isEmpty())
        assertEquals(3, local.defaultPropertyJson.readBcIntProperty("Difficulty"))
        assertEquals(BcExtendedItemSupportLevel.Unsupported, catalog.extendedItem("ItemArms", "Wrong")!!.supportLevel)
    }

    private fun female3dJs(
        assets: String = """
            {
                Group: "ItemArms",
                Asset: [{ Name: "HempRope" }],
            },
        """.trimIndent(),
        activities: String = """
            {
                Name: "Spank",
                Target: ["ItemArms"],
                TargetSelf: ["ItemArms"],
            },
        """.trimIndent(),
    ): String = """
        var AssetFemale3DCG = [
            $assets
        ];

        var ActivityFemale3DCG = [
            $activities
        ];
    """.trimIndent()
}
