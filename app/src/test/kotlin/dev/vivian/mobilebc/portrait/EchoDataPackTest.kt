package dev.vivian.mobilebc.portrait

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EchoDataPackTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root = listOf(File("src/main/assets/portrait"), File("app/src/main/assets/portrait")).first { it.isDirectory }
    // Keep update/rollback scenarios independent of the shipped release sequence.
    private val bundledText = JSONObject(File(root, "echo/pack.json").readText()).put("sequence", 1).toString()
    private val supplement = File(root, "supplement/manifest.json").readText()
    private fun decode(text: String = bundledText) = EchoDataPack.decode(text, supplement)
    private fun updated(sequence: Int = 2): JSONObject = JSONObject(bundledText).put("sequence", sequence)
    private fun rejects(block: () -> Unit) {
        assertThrows(Exception::class.java, block)
    }

    @Test fun bundledPackageMatchesItsSourceFiles() {
        val pack = decode()
        assertEquals(EchoPortraitRevision, pack.revision)
        assertTrue(sameJson(JSONObject(pack.catalog), JSONObject(File(root, "echo/catalog.json").readText())))
        val images = JSONObject(File(root, "echo/images.json").readText()).getJSONObject("images")
        assertEquals(images.keys().asSequence().associateWith(images::getString), pack.imageHashes)
    }

    @Test fun newRevisionCanReuseOnlyUnchangedPackagedBehavior() {
        val next = updated()
        val revision = "a".repeat(40)
        listOf("catalog", "images", "names").forEach { next.getJSONObject(it).put("revision", revision) }
        next.getJSONObject("supplement").put("echoRevision", revision)
        assertEquals(revision, decode(next.toString()).revision)
        next.getJSONObject("supplement").getJSONArray("adapters").getJSONObject(0).put("sourceHash", "b".repeat(40))
        rejects { decode(next.toString()) }
    }

    @Test fun rulerRetainsTextDataButCannotImportDrawingHooks() {
        val next = updated()
        val ruler = next.getJSONObject("catalog").getJSONArray("assets").objects()
            .single { it.getJSONObject("definition").getString("Name") == "刻度尺" }
        val extension = ruler.getJSONObject("extended")
        assertEquals("text", extension.getString("Archetype"))
        assertEquals(setOf("Text", "Text2", "Text3", "Text4"), extension.getJSONObject("MaxLength").keys().asSequence().toSet())
        decode(next.toString())
        extension.put("ScriptHooks", JSONObject().put("AfterDraw", "unreviewed"))
        rejects { decode(next.toString()) }
    }

    @Test fun rejectsIncompatibleBehaviorMixedDataAndUnsafeImages() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { it.put("bcVersion", "R999") },
            { it.put("capability", "runtime-scripts-v2") },
            { it.getJSONObject("names").put("revision", "b".repeat(40)) },
            { it.getJSONObject("catalog").getJSONArray("assets").getJSONObject(0).put("portraitAdapter", "new-hook") },
            { it.getJSONObject("catalog").put("ScriptHooks", JSONObject()) },
            { it.getJSONObject("catalog").put("__proto__", JSONObject()) },
            { it.getJSONObject("catalog").getJSONArray("assets").getJSONObject(0)
                .put("extended", JSONObject().put("Archetype", "custom-script")) },
            { it.getJSONObject("images").getJSONObject("images").put("Assets/Female3DCG/../x.png", "a".repeat(40)) },
        )
        changes.forEach { change -> rejects { decode(updated().also(change).toString()) } }
    }

    @Test fun liquidBagRetainsNoarchDataWithoutExecutableHooks() {
        val next = updated()
        val records = next.getJSONObject("catalog").getJSONArray("assets").objects()
            .filter { it.getJSONObject("definition").getString("Name") == "尿袋" }
        assertEquals(setOf("ItemTorso", "ItemTorso2"), records.map { it.getString("group") }.toSet())
        val extended = records.first().getJSONObject("extended")
        assertEquals("noarch", extended.getString("Archetype"))
        assertEquals(0.1, extended.getJSONObject("BaselineProperty").getDouble("Liquid"), 0.0)
        decode(next.toString())
        extended.put("ScriptHooks", JSONObject().put("ScriptDraw", "unreviewed"))
        rejects { decode(next.toString()) }
    }

    @Test fun installsAtomicallyRetainsActiveSnapshotAndRecoversPrevious() {
        val directory = temporary.newFolder()
        val store = EchoPackStore(directory, supplement)
        val active = store.current(decode())
        store.install(updated().toString(), 2, active)
        assertEquals(1L, active.sequence)
        assertEquals(2L, store.current(decode()).sequence)
        store.install(updated(3).toString(), 3, active)
        File(directory, "current.json").writeText("truncated")
        assertEquals(2L, store.current(decode()).sequence)
        File(directory, "pending.json").writeText(updated(4).toString())
        assertEquals(2L, store.current(decode()).sequence)
        rejects { store.install(updated(2).toString(), 2, active) }
        rejects { store.install(updated(4).toString(), 5, active) }
        assertEquals(2L, store.current(decode()).sequence)
    }

    @Test fun revisionPathsNeverMixWithAnotherSnapshot() {
        val pack = decode()
        val image = pack.imageHashes.keys.first()
        val revision = "a".repeat(40)
        assertEquals(image, echoPortraitResourcePath("$PortraitOrigin/echo/$revision/$image", pack.imageHashes.keys, revision))
        assertNull(echoPortraitResourcePath("$PortraitOrigin/echo/${pack.revision}/$image", pack.imageHashes.keys, revision))
    }

    @Test fun updateSourcesRequireHttpsWithoutCredentialsOrQueries() {
        requireUpdateUrl("https://raw.githubusercontent.com/owner/repo/main/latest.json")
        listOf("http://example.com/pack", "https://user@example.com/pack", "https://example.com/pack?secret=1",
            "file:///pack", "https://example.com:8443/pack").forEach { rejects { requireUpdateUrl(it) } }
    }

    @Test fun networkUpdateChecksDigestHostAndReleaseSequenceBeforeInstallation() {
        val store = EchoPackStore(temporary.newFolder(), supplement)
        val active = decode()
        val source = "https://example.com/latest.json"
        val bytes = updated().toString().toByteArray()
        val manifest = JSONObject().put("format", 1).put("sequence", 2)
            .put("url", "https://example.com/pack.json").put("sha256", "incorrect")
        val download: (String, Int) -> ByteArray = { url, _ ->
            if (url == source) manifest.toString().toByteArray() else bytes
        }
        rejects { updateEchoPack(source, active, store, download) }
        assertEquals(1L, store.current(active).sequence)
        manifest.put("sha256", portraitSha256(bytes)).put("url", "https://other.example/pack.json")
        rejects { updateEchoPack(source, active, store, download) }
        manifest.put("url", "https://example.com/pack.json")
        updateEchoPack(source, active, store, download)
        assertEquals(2L, store.current(active).sequence)
        assertEquals(1L, active.sequence)
    }
}
