package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcActivityDictionaryTextParserTest {
    @Test
    fun r133InterfaceTranslationUsesKeyInsteadOfEnglishSentence() {
        val parser = BcInterfaceTextParser(
            resourceReader = { _, path ->
                when (path) {
                    BcResourcePaths.Screens.InterfaceCsv -> "AccessBlocked,You cannot use your items"
                    BcResourcePaths.Screens.InterfaceCn -> "AccessBlocked\n你无法使用你的物品"
                    else -> null
                }
            },
            language = "CN",
        )
        val parsed = parser.parse(
            BcTextParseRequest("R133", "AccessBlocked", dictionaryJson = null, memberLookup = emptyMap()),
        )
        assertEquals("你无法使用你的物品", parsed?.text)
    }

    @Test
    fun nativeInterfaceKeepsDictionaryTokensInsideMemberNames() {
        val parser = BcInterfaceTextParser(
            resourceReader = { _, path ->
                if (path == BcResourcePaths.Screens.InterfaceCsv) {
                    "ActionUse,SourceCharacter uses a NextAsset on DestinationCharacter."
                } else {
                    null
                }
            },
            language = "EN",
        )

        val parsed = parser.parse(
            BcTextParseRequest(
                version = "R131",
                key = "ActionUse",
                dictionaryJson = """
                    [
                      {"SourceCharacter":1001},
                      {"TargetCharacter":2002},
                      {"Tag":"NextAsset","Text":"rope"}
                    ]
                """.trimIndent(),
                memberLookup = mapOf(1001 to "NextAsset", 2002 to "Bob"),
            ),
        )

        assertEquals("NextAsset uses a rope on Bob.", parsed?.text)
    }

    @Test
    fun fallbackAndEchoReplaceRepeatedTagsAndAliasesWithoutRescanningMemberNames() {
        val template =
            "SourceCharacter / SourceCharacter / DestinationCharacterName / DestinationCharacter / TargetPronounObject / sourceCharacter."
        val dictionary = """
            [
              {"SourceCharacter":1001},
              {"TargetCharacter":2002},
              {"Tag":"MISSING ACTIVITY DESCRIPTION FOR KEYWORD custom","Text":"$template"}
            ]
        """.trimIndent()
        val members = mapOf(1001 to "PronounObject", 2002 to "Bob")
        val fallback = BcDictionaryFallbackTextParser.parse(
            key = "custom",
            dictionaryJson = dictionary,
            memberLookup = members,
        )
        val echo = BcDictionaryFallbackTextParser.renderTemplate(
            template = template,
            dictionaryJson = dictionary,
            memberLookup = members,
        )

        assertEquals("PronounObject / PronounObject / Bob / Bob / them / sourceCharacter.", fallback?.text)
        assertEquals(fallback?.text, echo)
    }

    @Test
    fun dictionaryTagsAndInsertedValuesRemainLiteral() {
        val rendered = BcDictionaryFallbackTextParser.renderTemplate(
            template = "Asset[0] / Asset[0] / asset[0]",
            dictionaryJson = """[{"Tag":"Asset[0]","Text":"${'$'}5\\NextAsset"}]""",
            memberLookup = emptyMap(),
        )

        assertEquals("${'$'}5\\NextAsset / ${'$'}5\\NextAsset / asset[0]", rendered)
    }

    @Test
    fun blankVersionDoesNotReadResourcesFromTheDefaultVersion() {
        val requestedVersions = mutableListOf<String>()
        val parser = BcActivityDictionaryTextParser(
            resourceReader = { version, _ ->
                requestedVersions += version
                "ChatOther-ItemArms-Spank,Alice spanks Bob."
            },
            language = "EN",
        )

        val parsed = parser.parse(
            BcTextParseRequest(
                version = "   ",
                key = "ChatOther-ItemArms-Spank",
                dictionaryJson = null,
                memberLookup = emptyMap(),
            ),
        )

        assertNull(parsed)
        assertEquals(emptyList<String>(), requestedVersions)
    }

    @Test
    fun missingBaseResourceIsRetriedAfterTheVersionSnapshotIsPublished() {
        val resources = mutableMapOf<String, String>()
        val parser = BcActivityDictionaryTextParser(
            resourceReader = { _, path -> resources[path] },
            language = "EN",
        )
        val request = BcTextParseRequest(
            version = "R131",
            key = "ChatOther-ItemArms-Spank",
            dictionaryJson = null,
            memberLookup = emptyMap(),
        )

        assertNull(parser.parse(request))

        resources[BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv] =
            "ChatOther-ItemArms-Spank,Alice spanks Bob."

        assertEquals("Alice spanks Bob.", parser.parse(request)?.text)
    }

    @Test
    fun missingTranslationFallbackIsNotRetainedAfterTranslationAppears() {
        val resources = mutableMapOf(
            BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv to
                "ChatOther-ItemArms-Spank,Alice spanks Bob.",
        )
        val parser = BcActivityDictionaryTextParser(
            resourceReader = { _, path -> resources[path] },
            language = "CN",
        )
        val request = BcTextParseRequest(
            version = "R131",
            key = "ChatOther-ItemArms-Spank",
            dictionaryJson = null,
            memberLookup = emptyMap(),
        )

        assertEquals("Alice spanks Bob.", parser.parse(request)?.text)

        resources[BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCn] = """
            Alice spanks Bob.
            Alice 拍了 Bob。
        """.trimIndent()

        assertEquals("Alice 拍了 Bob。", parser.parse(request)?.text)
    }

    @Test
    fun parsesActivityTemplateFromCsvAndTranslation() {
        val parser = BcActivityDictionaryTextParser(
            resourceReader = { _, path ->
                when (path) {
                    BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv ->
                        "ChatOther-ItemArms-Spank,SourceCharacter spanks TargetCharacter's arm."
                    BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCn ->
                        """
                        SourceCharacter spanks TargetCharacter's arm.
                        SourceCharacter bonks TargetCharacter's arm.
                        """.trimIndent()
                    else -> null
                }
            },
        )

        val parsed = parser.parse(
            BcTextParseRequest(
                version = "R129",
                key = "ChatOther-ItemArms-Spank",
                dictionaryJson = """[{ "SourceCharacter": 1001 }, { "TargetCharacter": 2002 }]""",
                memberLookup = mapOf(1001 to "Alice", 2002 to "Bob"),
            ),
        )

        assertEquals("Alice bonks Bob's arm.", parsed?.text)
    }

    @Test
    fun parsesMissingDictionaryFallbackText() {
        val parsed = BcDictionaryFallbackTextParser.parse(
            key = "ChatOther-ItemHood-custom",
            dictionaryJson = """
                [
                  { "SourceCharacter": 187592 },
                  { "TargetCharacter": 187577 },
                  { "Tag": "MISSING ACTIVITY DESCRIPTION FOR KEYWORD ChatOther-ItemHood-custom", "Text": "SourceCharacter steps on TargetCharacter's hood" }
                ]
            """.trimIndent(),
            memberLookup = mapOf(187592 to "Luzi", 187577 to "Vivian"),
        )

        assertEquals("Luzi steps on Vivian's hood", parsed?.text)
    }

    @Test
    fun parsesInterfaceTemplateFromCsvAndTranslation() {
        val parser = BcInterfaceTextParser(
            resourceReader = { _, path ->
                when (path) {
                    BcResourcePaths.Screens.InterfaceCsv ->
                        "ActionUse,SourceCharacter uses a NextAsset on DestinationCharacter FocusAssetGroup."
                    BcResourcePaths.Screens.InterfaceCn ->
                        """
                        SourceCharacter uses a NextAsset on DestinationCharacter FocusAssetGroup.
                        SourceCharacter puts NextAsset on DestinationCharacter.
                        """.trimIndent()
                    else -> null
                }
            },
        )

        val parsed = parser.parse(
            BcTextParseRequest(
                version = "R129",
                key = "ActionUse",
                dictionaryJson = """
                    [
                      { "SourceCharacter": 1001 },
                      { "TargetCharacter": 2002 },
                      { "Tag": "NextAsset", "Text": "rope" }
                    ]
                """.trimIndent(),
                memberLookup = mapOf(1001 to "Alice", 2002 to "Bob"),
            ),
        )

        assertEquals("Alice puts rope on Bob.", parsed?.text)
    }

    @Test
    fun r131ServerUpdateRoomResolvesLocalizedTextLookupEntries() {
        val englishTemplate =
            "SourceCharacter updated the room. Name: ChatRoomName. Limit: ChatRoomLimit. ChatRoomPrivacy. ChatRoomLocked."
        val chineseTemplate =
            "SourceCharacter更新了房间信息. 名称: ChatRoomName. 人数上限: ChatRoomLimit. ChatRoomPrivacy. ChatRoomLocked."
        val parser = BcInterfaceTextParser(
            resourceReader = { version, path ->
                assertEquals("R131", version)
                when (path) {
                    BcResourcePaths.Screens.InterfaceCsv -> """
                        ServerUpdateRoom,$englishTemplate
                        Private,Private
                        Unlocked,Unlocked
                    """.trimIndent()
                    BcResourcePaths.Screens.InterfaceCn -> """
                        $englishTemplate
                        $chineseTemplate
                        Private
                        私人
                        Unlocked
                        解锁
                    """.trimIndent()
                    else -> null
                }
            },
            language = "CN",
        )

        val parsed = parser.parse(
            BcTextParseRequest(
                version = "R131",
                key = "ServerUpdateRoom",
                dictionaryJson = """
                    [
                      {"Tag":"SourceCharacter","Text":"Feylua","MemberNumber":225139},
                      {"Tag":"ChatRoomName","Text":"Fairy house"},
                      {"Tag":"ChatRoomLimit","Text":"20"},
                      {"Tag":"ChatRoomPrivacy","TextToLookUp":"Private"},
                      {"Tag":"ChatRoomLocked","TextToLookUp":"Unlocked"}
                    ]
                """.trimIndent(),
                memberLookup = mapOf(225139 to "菲露亚"),
            ),
        )

        assertEquals(
            "Feylua更新了房间信息. 名称: Fairy house. 人数上限: 20. 私人. 解锁.",
            parsed?.text,
        )
    }

    @Test
    fun parsesInterfaceActionWithTaggedDestinationText() {
        val parser = BcInterfaceTextParser(
            resourceReader = { _, path ->
                when (path) {
                    BcResourcePaths.Screens.InterfaceCsv ->
                        "VibeModeActionIncreaseTo0,SourceCharacter changes AssetName mode on DestinationCharacter."
                    BcResourcePaths.Screens.InterfaceCn ->
                        """
                        SourceCharacter changes AssetName mode on DestinationCharacter.
                        SourceCharacter sets DestinationCharacter's AssetName mode to 0.
                        """.trimIndent()
                    else -> null
                }
            },
        )

        val parsed = parser.parse(
            BcTextParseRequest(
                version = "R129",
                key = "VibeModeActionIncreaseTo0",
                dictionaryJson = """
                    [
                      { "SourceCharacter": 235841 },
                      { "Tag": "DestinationCharacter", "MemberNumber": 235841, "Text": "Violet" },
                      { "Tag": "AssetName", "AssetName": "VibratingEgg", "GroupName": "ItemVulva" },
                      { "Tag": "fbc_nonce", "Text": 49 }
                    ]
                """.trimIndent(),
                memberLookup = emptyMap(),
            ),
        )

        assertEquals("#235841 sets Violet's vibrating egg mode to 0.", parsed?.text)
    }

    @Test
    fun parsesInterfaceActionUsePacketWithTaggedFocusGroup() {
        val parser = BcInterfaceTextParser(
            resourceReader = { _, path ->
                when (path) {
                    BcResourcePaths.Screens.InterfaceCsv ->
                        "ActionUse,SourceCharacter uses a NextAsset on DestinationCharacter FocusAssetGroup."
                    else -> null
                }
            },
            language = "EN",
        )

        val parsed = parser.parse(
            BcTextParseRequest(
                version = "R129",
                key = "ActionUse",
                dictionaryJson = """
                    [
                      { "SourceCharacter": 235841 },
                      { "Tag": "DestinationCharacter", "MemberNumber": 187577, "Text": "Vivian" },
                      { "TargetCharacter": 187577 },
                      { "Tag": "NextAsset", "AssetName": "VibratingEgg", "GroupName": "ItemVulva" },
                      { "Tag": "FocusAssetGroup", "FocusGroupName": "ItemVulva" },
                      { "Tag": "fbc_nonce", "Text": 82 }
                    ]
                """.trimIndent(),
                memberLookup = emptyMap(),
            ),
        )

        assertEquals("#235841 uses a vibrating egg on Vivian vulva.", parsed?.text)
    }

    @Test
    fun parsesAssetStringsActionWithTaggedDestinationText() {
        val parser = BcAssetStringsTextParser(
            resourceReader = { _, path ->
                when (path) {
                    BcResourcePaths.Assets.Female3DCG.AssetStringsCsv ->
                        "VibeModeActionIncreaseTo0,DestinationCharacterName AssetName starts teasing TargetPronounObject lightly."
                    BcResourcePaths.Assets.Female3DCG.AssetStringsCn ->
                        """
                        DestinationCharacterName AssetName starts teasing TargetPronounObject lightly.
                        DestinationCharacterName's AssetName starts teasing TargetPronounObject lightly.
                        """.trimIndent()
                    else -> null
                }
            },
        )

        val parsed = parser.parse(
            BcTextParseRequest(
                version = "R129",
                key = "VibeModeActionIncreaseTo0",
                dictionaryJson = """
                    [
                      { "SourceCharacter": 235841 },
                      { "Tag": "DestinationCharacter", "MemberNumber": 235841, "Text": "Violet" },
                      { "Tag": "AssetName", "AssetName": "VibratingEgg", "GroupName": "ItemVulva" },
                      { "Tag": "fbc_nonce", "Text": 49 }
                    ]
                """.trimIndent(),
                memberLookup = emptyMap(),
            ),
        )

        assertEquals("Violet's vibrating egg starts teasing them lightly.", parsed?.text)
    }

    @Test
    fun localizesAssetGroupAndCraftNameFromFemale3dDictionary() {
        val resources = mapOf(
            BcResourcePaths.Screens.InterfaceCsv to
                "ActionUse,SourceCharacter uses a NextAsset on DestinationCharacter FocusAssetGroup.",
            BcResourcePaths.Screens.InterfaceCn to """
                SourceCharacter uses a NextAsset on DestinationCharacter FocusAssetGroup.
                SourceCharacter 把 NextAsset 放在 DestinationCharacter 的 FocusAssetGroup。
            """.trimIndent(),
            BcResourcePaths.Assets.Female3DCG.Female3DCGCsv to """
                ItemVulva,,Vulva
                ItemVulva,VibratingEgg,Vibrating Egg
            """.trimIndent(),
            BcResourcePaths.Assets.Female3DCG.Female3DCGCn to """
                Vulva
                私处
                Vibrating Egg
                跳蛋
            """.trimIndent(),
        )
        val reader: (String, String) -> String? = { _, path -> resources[path] }
        val resolver = BcCachedDictionaryValueResolver(reader) { "CN" }
        val parser = BcInterfaceTextParser(
            resourceReader = reader,
            dictionaryValueResolver = resolver,
        )

        val parsed = parser.parse(
            BcTextParseRequest(
                version = "R129",
                key = "ActionUse",
                dictionaryJson = """
                    [
                      { "SourceCharacter": 1001 },
                      { "TargetCharacter": 2002 },
                      {
                        "Tag": "NextAsset",
                        "AssetName": "VibratingEgg",
                        "GroupName": "ItemVulva",
                        "CraftName": "定制款"
                      },
                      { "Tag": "FocusAssetGroup", "FocusGroupName": "ItemVulva" }
                    ]
                """.trimIndent(),
                memberLookup = mapOf(1001 to "Alice", 2002 to "Bob"),
            ),
        )

        assertEquals("Alice 把 定制款 (跳蛋) 放在 Bob 的 私处。", parsed?.text)
    }

    @Test
    fun fallbackAndEchoTemplatesUseTheSameLocalizedNames() {
        val resources = mapOf(
            BcResourcePaths.Assets.Female3DCG.Female3DCGCsv to """
                ItemArms,,Arms
                ItemArms,HempRope,Hemp Rope
            """.trimIndent(),
            BcResourcePaths.Assets.Female3DCG.Female3DCGCn to """
                Arms
                手臂
                Hemp Rope
                麻绳
            """.trimIndent(),
        )
        val resolver = BcCachedDictionaryValueResolver(
            resourceReader = { _, path -> resources[path] },
            languageProvider = { "CN" },
        )
        val dictionary = """
            [
              { "SourceCharacter": 1001 },
              { "TargetCharacter": 2002 },
              { "Tag": "ActivityAsset", "AssetName": "HempRope", "GroupName": "ItemArms" },
              { "Tag": "FocusAssetGroup", "FocusGroupName": "ItemArms" },
              {
                "Tag": "MISSING ACTIVITY DESCRIPTION FOR KEYWORD custom",
                "Text": "SourceCharacter uses ActivityAsset on TargetCharacter's FocusAssetGroup."
              }
            ]
        """.trimIndent()

        val fallback = BcDictionaryFallbackTextParser.parse(
            key = "custom",
            dictionaryJson = dictionary,
            memberLookup = mapOf(1001 to "Alice", 2002 to "Bob"),
            version = "R129",
            dictionaryValueResolver = resolver,
        )
        val echo = BcDictionaryFallbackTextParser.renderTemplate(
            template = "SourceCharacter uses ActivityAsset on TargetCharacter's FocusAssetGroup.",
            dictionaryJson = dictionary,
            memberLookup = mapOf(1001 to "Alice", 2002 to "Bob"),
            version = "R129",
            dictionaryValueResolver = resolver,
        )

        assertEquals("Alice uses 麻绳 on Bob's 手臂.", fallback?.text)
        assertEquals(fallback?.text, echo)
    }

    @Test
    fun unknownNamesAreReadableWithoutDamagingEchoUnicodeNames() {
        val rendered = BcDictionaryFallbackTextParser.renderTemplate(
            template = "AssetName / OtherAsset / FocusAssetGroup",
            dictionaryJson = """
                [
                  { "Tag": "AssetName", "AssetName": "MagicWand", "GroupName": "ItemHands" },
                  { "Tag": "OtherAsset", "AssetName": "贴贴", "GroupName": "ItemMisc" },
                  { "Tag": "FocusAssetGroup", "FocusGroupName": "ItemUpperBody" }
                ]
            """.trimIndent(),
            memberLookup = emptyMap(),
        )

        assertEquals("magic wand / 贴贴 / upper body", rendered)
    }

    @Test
    fun craftedNamesAreLiteralStringsAndNullDoesNotBecomeADisplayName() {
        fun render(craftValue: String): String? = BcDictionaryFallbackTextParser.renderTemplate(
            template = "SourceCharacter uses AssetName.",
            dictionaryJson = """[{"SourceCharacter":1001},{"Tag":"AssetName","AssetName":"MagicWand","GroupName":"ItemHands","CraftName":$craftValue}]""",
            memberLookup = mapOf(1001 to "Alice"),
        )
        assertEquals("Alice uses magic wand.", render("null"))
        assertEquals("Alice uses magic wand.", render("123"))
        assertEquals("Alice uses 定制 SourceCharacter $2 🔒 (magic wand).", render("\"定制 SourceCharacter $2 🔒\""))
    }

    @Test
    fun resolvesOfficialEnglishDescriptionsInsteadOfHumanizingProtocolNames() {
        val resolver = BcCachedDictionaryValueResolver(
            resourceReader = { _, path ->
                if (path == BcResourcePaths.Assets.Female3DCG.Female3DCGCsv) {
                    """
                        ItemArms,,Arms
                        ItemArms,BitchSuit,Pet Suit
                    """.trimIndent()
                } else {
                    null
                }
            },
            languageProvider = { "EN" },
        )

        assertEquals("Arms", resolver.groupDisplayName("R129", "ItemArms"))
        assertEquals("Pet Suit", resolver.assetDisplayName("R129", "ItemArms", "BitchSuit"))
    }

    @Test
    fun repairsLegacyMojibakeInMirroredChineseBcTranslations() {
        val resolver = BcCachedDictionaryValueResolver(
            resourceReader = { _, path ->
                when (path) {
                    BcResourcePaths.Assets.Female3DCG.Female3DCGCsv ->
                        "ItemVulva,VibratingEgg,Vibrating Egg"
                    BcResourcePaths.Assets.Female3DCG.Female3DCGCn -> """
                        Vibrating Egg
                        è·³è›‹
                    """.trimIndent()
                    else -> null
                }
            },
            languageProvider = { "CN" },
        )

        assertEquals("跳蛋", resolver.assetDisplayName("R129", "ItemVulva", "VibratingEgg"))
    }
}
