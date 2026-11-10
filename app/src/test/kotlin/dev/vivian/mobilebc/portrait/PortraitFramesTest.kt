package dev.vivian.mobilebc.portrait

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortraitFramesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = """{"memberNumber":42,"assetFamily":"Female3DCG","activePose":["BaseUpper"],"appearance":[{"group":"Cloth","name":"Dress","color":["#123456"],"property":{"Opacity":0.5,"rotation":90,"TypeRecord":{"s":0}}}]}"""
    private fun key(payload: String = this.payload, owner: Int = 1, version: String = "renderer-v1") = portraitFrameKey(owner, version, payload)
    private fun png() = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(500, 1000, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    @Test fun persistedFrameSurvivesStoreRecreationAndRemainsAnExactPng() {
        val directory = temporary.newFolder("persistent-frames")
        val bytes = png()
        PortraitFrames(directory).save(key(), bytes)
        assertArrayEquals(bytes, PortraitFrames(directory).read(key()))
        assertNull(PortraitFrames(directory).read(key(owner = 2)))
        assertNull(PortraitFrames(directory).read(key(version = "renderer-v2")))
    }

    @Test fun geometryIsBoundToTheSameAppearanceKeyAndSurvivesRecreation() {
        val directory = temporary.newFolder()
        val store = PortraitFrames(directory)
        val geometry = PortraitGeometry(25.0, 450.0, 0.0, 1100.0, -10.0, 1010.0, false)
        store.save(key(), png())
        assertNull(store.readGeometry(key()))
        store.saveGeometry(key(), geometry)
        assertEquals(geometry, PortraitFrames(directory).readGeometry(key()))
        assertNull(store.readGeometry(key(owner = 2)))
        assertNull(store.readGeometry(key(version = "other")))
    }

    @Test fun allDrawingChangesSelectDifferentFramesWhileJsonKeyOrderDoesNot() {
        val original = key()
        val data = JSONObject(payload)
        val reordered = JSONObject().put("appearance", data.get("appearance"))
            .put("activePose", data.get("activePose")).put("assetFamily", data.get("assetFamily"))
            .put("memberNumber", 42)
        assertEquals(original, key(reordered.toString()))
        for ((old, next) in listOf("Dress" to "Shirt", "#123456" to "#abcdef", "BaseUpper" to "Hogtied",
            "0.5" to "0.2", "90" to "180", "\"s\":0" to "\"s\":1")) {
            assertNotEquals("Change ignored: $old", original, key(payload.replace(old, next)))
        }
        assertNotEquals(original, key(owner = 2))
        assertNotEquals(original, key(version = "new-echo-or-renderer"))
    }

    @Test fun corruptedFrameIsAMissAndAtomicReplacementCanRecoverIt() {
        val directory = temporary.newFolder()
        val store = PortraitFrames(directory)
        val bytes = png()
        store.save(key(), bytes)
        val file = directory.listFiles()!!.single()
        val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        file.writeBytes(corrupted)
        assertNull(store.read(key()))
        store.save(key(), bytes)
        assertArrayEquals(bytes, store.read(key()))
        assertTrue(directory.listFiles()!!.none { it.name.startsWith("pending-") })
    }

    @Test fun boundedStorageEvictsOldFramesAndCannotWriteToArbitraryPaths() {
        val directory = temporary.newFolder()
        val bytes = png()
        val store = PortraitFrames(directory, (bytes.size + 64L) * 2)
        store.save(key(), bytes)
        directory.listFiles()!!.single().setLastModified(1)
        store.save(key(owner = 2), bytes)
        store.save(key(owner = 3), bytes)
        assertNull(store.read(key()))
        assertArrayEquals(bytes, store.read(key(owner = 3)))
        assertThrows(IllegalArgumentException::class.java) { store.read("../outside") }
        assertThrows(IllegalArgumentException::class.java) { store.save(key(), byteArrayOf(1, 2)) }
    }
}
