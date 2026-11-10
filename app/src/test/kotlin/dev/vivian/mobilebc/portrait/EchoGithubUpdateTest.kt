package dev.vivian.mobilebc.portrait

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EchoGithubUpdateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root = listOf(File("src/main/assets/portrait"), File("app/src/main/assets/portrait")).first { it.isDirectory }
    // Keep update/rollback scenarios independent of the shipped release sequence.
    private val bundled = JSONObject(File(root, "echo/pack.json").readText()).put("sequence", 1).toString()
    private val indexText = File(root, "echo/source-index.json").readText()
    private val index = JSONObject(indexText)
    private val supplement = File(root, "supplement/manifest.json").readText()
    private val active = EchoDataPack.decode(bundled, supplement)
    private val revision = "a".repeat(40)
    private val newPath = "src/components/衣服和装饰/饰品/帽子/LocalTest.js"
    private val newImage = "resources/Assets/Female3DCG/Hat/LocalTest.png"
    private val source = """
        import { AssetManager } from "@local/AssetManager";
        import { luziSuffixFixups } from "@local/lib/fixups";
        const asset = { Name: "LocalTest", Left: 100 + 20, Layer: [{Name: ""}], };
        const translation = {CN: "本地新帽子", EN: "Local hat"};
        export default function () {
            AssetManager.addAssetWithConfig("Hat", asset, {translation});
            luziSuffixFixups("Hat", asset.Name);
        }
    """.trimIndent()

    private fun hashes(): MutableMap<String, String> = buildMap {
        val components = index.getJSONObject("components")
        components.keys().forEach { put(it, components.getJSONObject(it).getString("sha")) }
        val dependencies = index.getJSONObject("dependencies")
        dependencies.keys().forEach { put(it, dependencies.getString(it)) }
        active.imageHashes.forEach { (path, hash) -> put("resources/$path", hash) }
    }.toMutableMap()

    @Test fun unchangedReviewedComponentsReproduceBundledAssetsWithoutReadingJavascript() {
        val hashes = hashes()
        val result = JSONObject(EchoGithubCompiler(bundled, indexText).compile(active.revision, 2, hashes, hashes.keys - "utils") {
            error("Unchanged components must not be downloaded")
        })
        val decoded = EchoDataPack.decode(result.toString(), supplement)
        assertEquals(active.imageHashes, decoded.imageHashes)
        assertTrue(decoded.imageHashes.containsKey("Assets/Female3DCG/BodyUpper/Small_White.png"))
        assertTrue(decoded.imageHashes.containsKey("Assets/Female3DCG/BodyLower/Small_White.png"))
        assertTrue(decoded.imageHashes.containsKey("Assets/Female3DCG/ArmsLeft/BackBoxTie/Small_White.png"))
        fun records(catalog: JSONObject): Map<String, JSONObject> {
            val records = catalog.getJSONArray("assets")
            return (0 until records.length()).associate { i ->
                val item = records.getJSONObject(i)
                (item.getString("group") + "/" + item.getJSONObject("definition").getString("Name")) to item
            }
        }
        val original = records(JSONObject(active.catalog))
        val regenerated = records(result.getJSONObject("catalog"))
        assertEquals(original.keys, regenerated.keys)
        original.forEach { (key, value) -> assertTrue(key, sameJson(value, regenerated[key])) }
        // Browser regression consumes the actual locally compiled package, with real image URLs.
        File("build/outputs/echo-local-probe/pack.json").apply {
            checkNotNull(parentFile).mkdirs()
            writeText(result.toString())
        }
    }

    @Test fun changedCollarActivityCodeCannotBeSilentlyImportedOrOmitted() {
        val path = "src/components/束缚与玩具/装饰物/伊丽莎白圈.js"
        assertTrue(index.getJSONArray("required").strings().contains(path))
        val hashes = hashes().apply { put(path, "b".repeat(40)) }
        assertThrows(Exception::class.java) {
            EchoGithubCompiler(bundled, indexText).compile(revision, 2, hashes, hashes.keys - "utils") { requested ->
                assertEquals(path, requested)
                """import { HookManager } from "@sugarch/bc-mod-hook-manager"; export default function () {}"""
            }
        }
    }

    @Test fun mappedSharedImagesUpdateWithoutCollectingUnreferencedIcons() {
        val shared = "resources/Icons/Luzi_GlassDrink_牛奶.png"
        val unused = "resources/Icons/Unused.png"
        val hashes = hashes().apply { put(shared, "b".repeat(40)); put(unused, "c".repeat(40)) }
        val result = EchoGithubCompiler(bundled, indexText).compile(revision, 2, hashes, hashes.keys - "utils") {
            error("Unchanged components must not be downloaded")
        }
        val decoded = EchoDataPack.decode(result, supplement)
        assertEquals("b".repeat(40), decoded.imageHashes[shared.removePrefix("resources/")])
        assertFalse(decoded.imageHashes.containsKey(unused.removePrefix("resources/")))
        assertEquals("Icons/Luzi_GlassDrink_牛奶.png", JSONObject(decoded.catalog).getJSONObject("imageMappings")
            .getString("Assets/Female3DCG/ItemHandheld/杯饮_牛奶.png"))
        hashes.remove(shared)
        assertThrows(Exception::class.java) {
            EchoGithubCompiler(bundled, indexText).compile(revision, 3, hashes, hashes.keys - "utils") { error("Unexpected source read") }
        }
    }

    @Test fun newStaticDefinitionsAreExtractedAndUnsupportedDefinitionsAreReported() {
        val hashes = hashes().apply { put(newPath, "b".repeat(40)); put(newImage, "c".repeat(40)) }
        val compiler = EchoGithubCompiler(bundled, indexText)
        val result = JSONObject(compiler.compile(revision, 2, hashes, hashes.keys - "utils") { source })
        assertTrue(result.getJSONObject("names").getJSONArray("assets").toString().contains("本地新帽子"))
        assertTrue(result.getJSONObject("images").getJSONObject("images").has(newImage.removePrefix("resources/")))
        val rejected = JSONObject(compiler.compile(revision, 2, hashes, hashes.keys - "utils") { source.replace("100 + 20", "fetch('https://bad')") })
        assertTrue(rejected.getJSONObject("extractionReport").getJSONObject("omitted").has(newPath))
        assertFalse(rejected.getJSONObject("catalog").getJSONArray("assets").toString().contains("LocalTest"))
    }

    @Test fun staticItemsOutsideClothingAreDiscovered() {
        val path = "src/components/束缚与玩具/手持物/LocalTest.js"
        val hashes = hashes().apply { put(path, "b".repeat(40)); put(newImage, "c".repeat(40)) }
        val result = JSONObject(EchoGithubCompiler(bundled, indexText).compile(revision, 2, hashes, hashes.keys - "utils") { source })
        assertTrue(result.getJSONObject("catalog").getJSONArray("sources").strings().contains(path))
        assertTrue(result.getJSONObject("images").getJSONObject("images").has(newImage.removePrefix("resources/")))
    }

    @Test fun missingImagesOmitOnlyTheirAssetsNamesAndAliases() {
        val mixed = source.replace("luziSuffixFixups(\"Hat\", asset.Name);", """
            luziSuffixFixups("Hat", asset.Name);
            AssetManager.addAssetWithConfig("Hat", {Name:"MissingImage"}, {translation:{CN:"缺图"}});
            luziSuffixFixups("Hat", "MissingImage");
        """.trimIndent())
        val hashes = hashes().apply { put(newPath, "b".repeat(40)); put(newImage, "c".repeat(40)) }
        val result = JSONObject(EchoGithubCompiler(bundled, indexText).compile(revision, 2, hashes, hashes.keys - "utils") { mixed })
        val catalog = result.getJSONObject("catalog")
        assertTrue(catalog.getJSONArray("sources").strings().contains(newPath))
        assertTrue(catalog.getJSONArray("assets").objects().any { it.getJSONObject("definition").getString("Name") == "LocalTest" })
        assertFalse(catalog.getJSONArray("assets").objects().any { it.getJSONObject("definition").getString("Name") == "MissingImage" })
        assertFalse(catalog.getJSONArray("aliases").objects().any { it.getString("name") == "MissingImage" })
        assertFalse(result.getJSONObject("names").getJSONArray("assets").objects().any { it.getString("name") == "MissingImage" })
        assertTrue(result.getJSONObject("extractionReport").getJSONObject("omitted").getString(newPath).contains("Hat/MissingImage"))
    }

    @Test fun reinsAreRetainedWithoutInventingSaddleImages() {
        val records = JSONObject(active.catalog).getJSONArray("assets").objects()
        for (group in listOf("ItemTorso", "ItemTorso2")) {
            assertTrue(records.any { it.getString("group") == group && it.getJSONObject("definition").getString("Name") == "缰绳" })
            assertFalse(records.any { it.getString("group") == group && it.getJSONObject("definition").getString("Name") == "鞍" })
        }
    }

    @Test fun requiredAssetImageLossStillAbortsTheUpdate() {
        val hashes = hashes().apply {
            keys.filter { it.startsWith("resources/Assets/Female3DCG/ItemTorso/") && it.substringAfterLast('/').startsWith("缰绳_") }
                .forEach { remove(it) }
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            EchoGithubCompiler(bundled, indexText).compile(revision, 2, hashes, hashes.keys - "utils") {
                error("Unchanged source must not be downloaded")
            }
        }
        assertTrue(failure.message.orEmpty().contains("Required Echo images missing"))
    }

    @Test fun formerlyExcludedNamesAndDirectoriesAcceptNewStaticComponents() {
        for (path in listOf(
            "src/components/套装/落落/LocalTest.js",
            "src/components/套装/世界/LocalTest.js",
            "src/components/衣服和装饰/饰品/LocalTest/手套.js",
        )) {
            val hashes = hashes().apply { put(path, "b".repeat(40)); put(newImage, "c".repeat(40)) }
            val result = JSONObject(EchoGithubCompiler(bundled, indexText).compile(revision, 2, hashes, hashes.keys - "utils") { source })
            assertTrue(result.getJSONObject("catalog").getJSONArray("sources").strings().contains(path))
            assertTrue(result.getJSONObject("images").getJSONObject("images").has(newImage.removePrefix("resources/")))
        }
    }

    @Test fun mixedFoodComponentRetainsAllStaticAssets() {
        val path = "src/components/束缚与玩具/手持物/食物.js"
        val data = index.getJSONObject("components").getJSONObject(path).getJSONObject("data")
        val records = data.getJSONArray("assets").objects()
        assertTrue(records.any { it.getString("group") == "ItemHandheld" && it.getJSONObject("definition").getString("Name") == "奶茶" })
        val restored = setOf("蛋糕卷", "棒棒糖", "烤鱼", "鸡腿")
        assertTrue(records.map { it.getJSONObject("definition").getString("Name") }.containsAll(restored))
        assertTrue(data.getJSONObject("names").getJSONArray("assets").objects().map { it.getString("name") }.containsAll(restored))
        val hashes = hashes().apply { put(path, "f".repeat(40)) }
        assertThrows(IllegalArgumentException::class.java) {
            EchoGithubCompiler(bundled, indexText).compile(revision, 2, hashes, hashes.keys - "utils") {
                error("Changed food definitions require review before parsing")
            }
        }
    }

    @Test fun selectedEyesIncludeMappedExpressionImages() {
        for (group in listOf("左眼_Luzi", "右眼_Luzi")) {
            for (expression in listOf("daydream", "dizzy")) {
                assertTrue(active.imageHashes.containsKey("Assets/Female3DCG/$group/$expression.png"))
            }
        }
        val assets = JSONObject(active.catalog).getJSONArray("assets").objects()
        assertEquals(2, assets.count { it.getJSONObject("definition").getString("Name") == "眼睛3微调" })
        assertTrue(assets.any { it.getJSONObject("definition").getString("Name") == "树叶发饰" })
    }

    @Test fun autoCloseMarkerDoesNotInstallGameplayOrChangePose() {
        val record = JSONObject(active.catalog).getJSONArray("assets").objects().single {
            it.getString("group") == "外观工具" && it.getJSONObject("definition").getString("Name") == "自动闭腿"
        }
        val definition = record.getJSONObject("definition")
        assertFalse(definition.getBoolean("Visible"))
        assertFalse(definition.has("DynamicScriptDraw"))
        assertFalse(definition.has("SetPose"))
        assertFalse(record.has("extended"))
        assertTrue(index.getJSONArray("required").strings().contains("src/components/工具/自动闭腿.js"))
    }

    @Test fun textConfigurationIsDataButCannotCarryDrawingCode() {
        val source = """
            import { AssetManager } from "@local/AssetManager";
            const extended = {Archetype:ExtendedArchetype.TEXT,Font:"Arial",MaxLength:{Text:10},BaselineProperty:{Text:"Label"}};
            export default function () {
                AssetManager.addAssetWithConfig("BodyMarkings", {Name:"TextFixture"}, {extended});
            }
        """.trimIndent()
        val extension = EchoStaticSource(source).extract().getJSONArray("assets").getJSONObject(0).getJSONObject("extended")
        assertEquals("text", extension.getString("Archetype"))
        assertEquals("Label", extension.getJSONObject("BaselineProperty").getString("Text"))
        assertThrows(IllegalArgumentException::class.java) {
            EchoStaticSource(source.replace("Font:\"Arial\"", "ScriptHooks:{AfterDraw:\"unreviewed\"}")).extract()
        }
    }

    @Test fun customRenameIsLocalToItsGroupAndRejectsRicherMigrations() {
        val source = """
            import { AssetManager } from "@local/AssetManager";
            import { customFixup } from "@local/lib/fixups";
            export default function () {
                AssetManager.addAssetWithConfig("ItemTorso", {Name:"Current"}, {});
                customFixup({Old:{Group:"ItemTorso",Name:"Old"},New:{Group:"ItemTorso",Name:"Current"}});
            }
        """.trimIndent()
        val aliases = EchoStaticSource(source).extract().getJSONArray("aliases")
        assertEquals(1, aliases.length())
        assertEquals("ItemTorso", aliases.getJSONObject(0).getString("group"))
        assertEquals("Old", aliases.getJSONObject(0).getString("oldName"))
        assertEquals("Current", aliases.getJSONObject(0).getString("name"))
        for (changed in listOf(
            source.replace("New:{Group:\"ItemTorso\"", "New:{Group:\"ItemTorso2\""),
            source.replace("Name:\"Current\"}})", "Name:\"Current\",Property:{}}})"),
            source.replace("customFixup({Old:", "customFixup({Property:{},Old:"),
        )) assertThrows(Exception::class.java) { EchoStaticSource(changed).extract() }
    }

    @Test fun noarchConfigurationRetainsDataAndRejectsHooks() {
        val source = """
            import { AssetManager } from "@local/AssetManager";
            export default function () {
                AssetManager.addAssetWithConfig("ItemTorso", {Name:"LiquidFixture"}, {
                    extended:{Archetype:ExtendedArchetype.NOARCH,BaselineProperty:{Liquid:0.1,Auto:false}}
                });
            }
        """.trimIndent()
        val extended = EchoStaticSource(source).extract().getJSONArray("assets").getJSONObject(0).getJSONObject("extended")
        assertEquals("noarch", extended.getString("Archetype"))
        assertEquals(0.1, extended.getJSONObject("BaselineProperty").getDouble("Liquid"), 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            EchoStaticSource(source.replace("BaselineProperty:", "ScriptHooks:{AfterDraw:\"unreviewed\"},BaselineProperty:")).extract()
        }
    }

    @Test fun legacyRegistrationPreservesTranslationAndSelectiveMirroring() {
        val source = """
            import { AssetManager } from "@local/AssetManager";
            export default function () {
                AssetManager.addAsset("ItemTorso", {Name:"Legacy"}, undefined, {CN:"旧式注册"}, true);
            }
        """.trimIndent()
        val result = EchoStaticSource(source).extract()
        val records = result.getJSONArray("assets")
        assertEquals(1, records.length())
        assertTrue(records.getJSONObject(0).getBoolean("noMirror"))
        assertFalse(records.getJSONObject(0).has("extended"))
        assertEquals("旧式注册", result.getJSONObject("names").getJSONArray("assets").getJSONObject(0).getJSONObject("translation").getString("CN"))
    }

    @Test fun groupedRegistrationKeepsTranslationsExtensionsAndTorsoMirrors() {
        val source = """
            import { AssetManager } from "@local/AssetManager";
            const assets = {ItemTorso:[{Name:"Grouped",Extended:true}],ItemHandheld:[{Name:"Plain"}]};
            const translations = {CN:{ItemTorso:{Grouped:"躯干"}},EN:{ItemHandheld:{Plain:"Plain"}}};
            const extended = {ItemTorso:{Grouped:{Archetype:"typed",Options:[{Name:"A"},{Name:"B"}]}}};
            export default function () { AssetManager.addGroupedAssets(assets, translations, extended); }
        """.trimIndent()
        val result = EchoStaticSource(source).extract()
        val records = result.getJSONArray("assets").objects()
        assertEquals(setOf("ItemTorso", "ItemTorso2", "ItemHandheld"), records.map { it.getString("group") }.toSet())
        assertEquals(2, records.single { it.getString("group") == "ItemTorso2" }.getJSONObject("extended").getJSONArray("Options").length())
        assertFalse(records.single { it.getString("group") == "ItemHandheld" }.has("extended"))
        val names = result.getJSONObject("names").getJSONArray("assets").objects().associateBy { it.getString("group") }
        assertEquals("躯干", names.getValue("ItemTorso2").getJSONObject("translation").getString("CN"))
        assertEquals("Plain", names.getValue("ItemHandheld").getJSONObject("translation").getString("EN"))
        assertThrows(IllegalArgumentException::class.java) {
            EchoStaticSource(source.replace("Options:[{Name:\"A\"},{Name:\"B\"}]", "ScriptHooks:{BeforeDraw:\"unreviewed\"}")).extract()
        }
    }

    @Test fun groupedWithConfigDoesNotTreatLayerNamesAsExtendedConfig() {
        val result = EchoStaticSource("""
            import { AssetManager } from "@local/AssetManager";
            export default function () {
                AssetManager.addGroupedAssetsWithConfig({Cloth:[{Name:"A"}]}, undefined, {CN:{Cloth:{A:{Front:"正面"}}}});
            }
        """.trimIndent()).extract()
        assertFalse(result.getJSONArray("assets").getJSONObject(0).has("extended"))
    }

    @Test fun reviewedActionCallbacksAreAbsentButBeadDrawingIsBound() {
        val records = JSONObject(active.catalog).getJSONArray("assets").objects()
        for (name in listOf("大号拉珠", "肛鞭")) {
            val matches = records.filter { it.getJSONObject("definition").getString("Name") == name }
            assertEquals(3, matches.size)
            val item = matches.single { it.getString("group") == "ItemButt" }
            assertFalse(item.getJSONObject("extended").has("ScriptHooks"))
            assertFalse(item.getJSONObject("definition").has("DynamicBeforeDraw"))
            if (name == "大号拉珠") assertEquals("echo-large-beads", item.getString("portraitAdapter"))
            else assertFalse(item.has("portraitAdapter"))
        }
    }

    @Test fun fullMasksCoverNativeAndEchoGroupsWithoutMaskingTheirOwnGroup() {
        val catalog = JSONObject(active.catalog)
        val groups = index.getJSONObject("nativeAssets").keys().asSequence().toSet() +
            catalog.getJSONArray("groups").objects().map { it.getString("Group") } +
            catalog.getJSONArray("mirrors").objects().map { it.getString("group") }
        for (record in catalog.getJSONArray("assets").objects().filter {
            it.getJSONObject("definition").getString("Name") in setOf("麻袋", "垃圾桶", "巨型玩偶", "拳击袋", "独角兽玩偶")
        }) {
            val group = record.getString("group")
            val masks = record.getJSONObject("definition").getJSONArray("Layer").objects()
                .flatMap { it.optJSONArray("Alpha")?.objects().orEmpty() }
            assertTrue(masks.isNotEmpty())
            for (mask in masks) assertEquals(groups - group, mask.getJSONArray("Group").strings().toSet())
            assertFalse(record.getJSONObject("extended").has("ScriptHooks"))
        }
        assertTrue(index.getJSONObject("dependencies").has("src/components/功能调整/全身遮罩.js"))
    }

    @Test fun imageFreeLayersAreAcceptedButMissingRealImagesAreRejected() {
        val hashes = hashes().apply { put(newPath, "b".repeat(40)) }
        val compiler = EchoGithubCompiler(bundled, indexText)
        for (hasImage in listOf(true, false)) {
            val source = """
                import { AssetManager } from "@local/AssetManager";
                export default function () {
                    AssetManager.addAsset("Hat", {Name:"LocalTest", Layer:[{Name:"dummy", HasImage:$hasImage}]});
                }
            """.trimIndent()
            val result = JSONObject(compiler.compile(revision, 2, hashes, hashes.keys - "utils") { source })
            assertEquals(hasImage, result.getJSONObject("extractionReport").getJSONObject("omitted").has(newPath))
        }
    }

    @Test fun nativeTorsoMirroringHonorsNoMirror() {
        for (group in listOf("ItemTorso", "ItemTorso2")) for (noMirror in listOf(false, true)) {
            val source = """
                import { AssetManager } from "@local/AssetManager";
                export default function () {
                    AssetManager.addAssetWithConfig("$group", {Name:"TestRope"}, {noMirror:$noMirror});
                }
            """.trimIndent()
            val records = EchoStaticSource(source).extract().getJSONArray("assets").objects()
            assertEquals(if (noMirror) listOf(group) else listOf("ItemTorso", "ItemTorso2"), records.map { it.getString("group") })
            assertTrue(records.all { it.optBoolean("noMirror") == noMirror })
        }
    }

    @Test fun changingRequiredHelpersOrLicenseAbortsUpdate() {
        for (path in listOf("LICENSE", "src/lib/generator/poseMapTool.js", index.getJSONArray("required").getString(0))) {
            val hashes = hashes().apply { put(path, "f".repeat(40)) }
            assertThrows(IllegalArgumentException::class.java) {
                EchoGithubCompiler(bundled, indexText).compile(revision, 2, hashes, hashes.keys - "utils") { source }
            }
        }
    }

    @Test fun removedOrChangedUnsupportedComponentsDoNotRetainStaleDefinitions() {
        val path = "src/components/衣服和装饰/饰品/帽子/女巫帽子.js"
        val hashes = hashes().apply { remove(path) }
        val compiler = EchoGithubCompiler(bundled, indexText)
        val removed = JSONObject(compiler.compile(revision, 2, hashes, hashes.keys - "utils") { error("Unexpected read") })
        assertFalse(removed.getJSONObject("catalog").getJSONArray("assets").toString().contains("女巫帽子"))
        hashes[path] = "f".repeat(40)
        val changed = JSONObject(compiler.compile(revision, 2, hashes, hashes.keys - "utils") { "export default function () { unknownRuntime(); }" })
        assertFalse(changed.getJSONObject("catalog").getJSONArray("assets").toString().contains("女巫帽子"))
        assertTrue(changed.getJSONObject("extractionReport").getJSONObject("omitted").has(path))
    }

    @Test fun githubTransportPinsCommitChecksSourceBytesAndStagesOnlyValidResults() {
        val hashes = hashes().apply { put(newPath, portraitGitBlobHash(source.toByteArray())); put(newImage, "c".repeat(40)) }
        val entries = JSONArray()
        hashes.forEach { (path, hash) -> entries.put(JSONObject().put("path", path).put("sha", hash).put("type", if (path == "utils") "commit" else "blob")) }
        val tree = JSONObject().put("sha", "d".repeat(40)).put("truncated", false).put("tree", entries)
        val store = EchoPackStore(temporary.newFolder(), supplement)
        val calls = mutableListOf<String>()
        var tampered = true
        val download: (String, Int) -> ByteArray = { url, _ ->
            calls += url
            requireEchoDownloadUrl(url)
            when {
                url.endsWith("/commits/main") -> """{"sha":"$revision","commit":{"tree":{"sha":"${"d".repeat(40)}"}}}"""
                url.contains("/compare/") -> """{"status":"ahead","behind_by":0}"""
                url.endsWith("/git/trees/$revision?recursive=1") -> tree.toString()
                else -> { assertTrue(url.contains("/$revision/")); if (tampered) source + " " else source }
            }.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { updateEchoFromGithub(active, store, bundled, indexText, download) }
        assertEquals(active.revision, store.current(active).revision)
        tampered = false
        updateEchoFromGithub(active, store, bundled, indexText, download)
        assertEquals(revision, store.current(active).revision)
        assertEquals(2L, store.current(active).sequence)
        assertTrue(calls.any { it.startsWith("https://raw.githubusercontent.com/") })
    }

    @Test fun staticGrammarRejectsExecutableCodeAndUnsupportedImports() {
        val mutations = listOf(
            source + "\nfetch('https://bad');",
            source.replace("100 + 20", "(() => 1)()"),
            source.replace("@local/AssetManager", "https://bad/module.js"),
            source.replace("Left: 100 + 20", "ScriptDraw: true"),
            source.replace("Left: 100 + 20", "__proto__: {}"),
            source.replace("Left: 100 + 20", "DynamicBeforeDraw: 'hook'"),
        )
        mutations.forEach { assertThrows(Exception::class.java) { EchoStaticSource(it).extract() } }
        val expansion = buildString {
            append("const a0 = {};\n")
            for (i in 1..30) append("const a$i = {left: a${i - 1}, right: a${i - 1}};\n")
            append("export default function () {}")
        }
        assertThrows(IllegalArgumentException::class.java) { EchoStaticSource(expansion).extract() }
    }

    @Test fun realUpstreamStaticFilesMatchReviewedExtractionIncludingTypesAndAliases() {
        val files = mapOf(
            "witch-hat.js" to "src/components/衣服和装饰/饰品/帽子/女巫帽子.js",
            "maid-bra.js" to "src/components/衣服和装饰/衣服/女仆与围裙/女仆胸罩.js",
        )
        files.forEach { (fixture, path) ->
            val source = checkNotNull(javaClass.getResourceAsStream("/echo-static/$fixture")).bufferedReader().use { it.readText() }
            val baseline = index.getJSONObject("components").getJSONObject(path)
            // Normalize checkout line endings before comparing the upstream Git identity.
            assertEquals(baseline.getString("sha"), portraitGitBlobHash(source.replace("\r\n", "\n").toByteArray()))
            val extracted = JSONObject(EchoStaticSource(source).extract().toString())
            assertTrue(fixture, sameJson(baseline.getJSONObject("data"), extracted))
        }
    }

    @Test fun currentRevisionNeedsOnlyOneRequestAndDivergedHistoryIsRejected() {
        val store = EchoPackStore(temporary.newFolder(), supplement)
        var count = 0
        assertNull(updateEchoFromGithub(active, store, bundled, indexText) { url, _ ->
            count++
            assertTrue(url.endsWith("/commits/main"))
            """{"sha":"${active.revision}"}""".toByteArray()
        })
        assertEquals(1, count)
        assertThrows(IllegalArgumentException::class.java) {
            updateEchoFromGithub(active, store, bundled, indexText) { url, _ ->
                (if (url.endsWith("/commits/main")) """{"sha":"$revision"}"""
                else """{"status":"diverged","behind_by":1}""").toByteArray()
            }
        }
        assertEquals(active.revision, store.current(active).revision)
    }
}
