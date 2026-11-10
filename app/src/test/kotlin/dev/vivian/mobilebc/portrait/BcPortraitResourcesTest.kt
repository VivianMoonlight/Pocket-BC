package dev.vivian.mobilebc.portrait

import org.junit.Assert.*
import org.junit.Test

class BcPortraitResourcesTest {
    @Test fun echoImageFallbackChecksSourcesAgainstThePinnedBlob() {
        val bytes = "verified image".toByteArray()
        val calls = mutableListOf<String>()
        val path = "Assets/Female3DCG/Hat/Test.png"
        val hash = portraitGitBlobHash(bytes)
        for (first in listOf(null, "wrong image".toByteArray())) {
            calls.clear()
            assertArrayEquals(bytes, fetchEchoPortraitImage(path, EchoPortraitRevision, hash) { url ->
                calls += url
                if (calls.size == 1) first else bytes
            })
            assertEquals(2, calls.size)
            assertTrue(calls.last().contains("/$EchoPortraitRevision/resources/$path"))
        }
        assertNull(fetchEchoPortraitImage(path, EchoPortraitRevision, hash) { "wrong".toByteArray() })
        calls.clear()
        assertArrayEquals(bytes, fetchEchoPortraitImage(path, EchoPortraitRevision, hash) { url -> calls += url; bytes })
        assertEquals(1, calls.size)
    }
    @Test fun githubDownloadRecoversWhenCdnAndRawAreUnavailableOrInvalid() {
        val bytes = "verified image".toByteArray()
        val path = "Assets/Female3DCG/ItemMisc/玩偶_Saki.png"
        for (failure in listOf(null, "invalid response".toByteArray())) {
            val calls = mutableListOf<String>()
            assertArrayEquals(bytes, fetchEchoPortraitImage(path, EchoPortraitRevision, portraitGitBlobHash(bytes)) { url ->
                calls += url
                if (url.startsWith("https://github.com/")) bytes else failure
            })
            assertEquals(3, calls.size)
            assertEquals("https://github.com/$EchoGithubRepository/raw/$EchoPortraitRevision/resources/$path", calls.last())
        }
        assertNull(fetchEchoPortraitImage(path, EchoPortraitRevision, portraitGitBlobHash(bytes)) { "invalid response".toByteArray() })
    }
    @Test fun alternateCdnRecoversOnlyWithThePinnedBlob() {
        val bytes = "verified image".toByteArray()
        val path = "Assets/Female3DCG/ItemArms/AllFours/宠物服上_Small_手臂.png"
        for (failure in listOf(null, "invalid response".toByteArray())) {
            val calls = mutableListOf<String>()
            assertArrayEquals(bytes, fetchEchoPortraitImage(path, EchoPortraitRevision, portraitGitBlobHash(bytes)) { url ->
                calls += url
                if (url.startsWith("https://gcore.jsdelivr.net/")) bytes else failure
            })
            assertEquals(4, calls.size)
            assertEquals("https://gcore.jsdelivr.net/gh/$EchoGithubRepository@$EchoPortraitRevision/resources/$path", calls.last())
        }
        assertNull(fetchEchoPortraitImage(path, EchoPortraitRevision, portraitGitBlobHash(bytes)) { "wrong".toByteArray() })
    }
    private val root = "$PortraitOrigin/R133/BondageClub/"
    private val scripts = setOf("Scripts/Common.js", "Assets/Female3DCG.js")

    @Test
    fun acceptsOnlySelectedScriptsAndVersionedImages() {
        assertEquals("Scripts/Common.js", portraitResourcePath(root + "Scripts/Common.js", scripts))
        assertEquals("Assets/Female3DCG/Eyes/Eyes1.png", portraitResourcePath(root + "Assets/Female3DCG/Eyes/Eyes1.png", scripts))
        assertNull(portraitResourcePath(root + "Scripts/Server.js", scripts))
        assertNull(portraitResourcePath(root + "Scripts/Game.js", scripts))
        assertNull(portraitResourcePath(root + "index.html", scripts))
        assertNull(portraitResourcePath(root + "socket.io/", scripts))
    }

    @Test
    fun refusesAlternateHostsVersionsEncodedPathsAndTraversal() {
        val candidates = listOf(
            "https://example.com/R133/BondageClub/Scripts/Common.js",
            "http://portrait.mobilebc.invalid/R133/BondageClub/Scripts/Common.js",
            "$PortraitOrigin/R129/BondageClub/Scripts/Common.js",
            "https://user@portrait.mobilebc.invalid/R133/BondageClub/Scripts/Common.js",
            "https://portrait.mobilebc.invalid:443/R133/BondageClub/Scripts/Common.js",
            root + "Assets/Female3DCG/../../secret.png",
            root + "Assets/Female3DCG/%2e%2e/secret.png",
            root + "Assets/Female3DCG/%252e%252e/secret.png",
            root + "Scripts/Common.js?account=42",
            root + "Scripts/Common.js#fragment",
        )
        candidates.forEach { assertNull(it, portraitResourcePath(it, scripts)) }
    }

    @Test
    fun hashesScriptBytesDeterministically() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", portraitSha256("abc".toByteArray()))
    }

    @Test
    fun echoImagesAcceptEncodedChineseNamesOnlyFromPinnedCatalog() {
        val image = "Assets/Female3DCG/Hat/女巫帽子_帽尖.png"
        val images = setOf(image)
        val root = "$PortraitOrigin/echo/$EchoPortraitRevision/"
        val encoded = java.net.URI(null, null, "/echo/$EchoPortraitRevision/$image", null).toASCIIString()
        assertEquals(image, echoPortraitResourcePath(PortraitOrigin + encoded, images))
        assertEquals(image, echoPortraitResourcePath(root + image, images))
        listOf(
            root + "Assets/Female3DCG/Hat/未收录.png",
            root + image + "?account=42", root + image + "#fragment",
            root.replace(EchoPortraitRevision, "main") + image,
            (PortraitOrigin + encoded).replace("%E5", "%25E5"),
            root + "Assets/Female3DCG/Hat/../Hat/女巫帽子_帽尖.png",
            root + "Assets/Female3DCG/Hat/%2e%2e/Hat/女巫帽子_帽尖.png",
            root.replace("https:", "http:") + image,
            root.replace("portrait.mobilebc.invalid", "example.com") + image,
            root.replace("portrait.mobilebc.invalid", "user@portrait.mobilebc.invalid") + image,
            root.replace("portrait.mobilebc.invalid", "portrait.mobilebc.invalid:443") + image,
            root + "src/main.js",
        ).forEach { assertNull(it, echoPortraitResourcePath(it, images)) }
    }

    @Test
    fun verifiesEchoImageBytesAgainstGitTreeBlobIdentity() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", portraitGitBlobHash(byteArrayOf()))
        assertEquals("f2ba8f84ab5c1bce84a7b441cb1959cfc7093b7f", portraitGitBlobHash("abc".toByteArray()))
        assertNotEquals(portraitGitBlobHash("abc".toByteArray()), portraitGitBlobHash("abd".toByteArray()))
    }

    @Test fun sharedEchoImagesRequireExactVersionAndCatalogEntry() {
        val image = "Icons/Luzi_GlassDrink_牛奶.png"
        val root = "$PortraitOrigin/echo/$EchoPortraitRevision/"
        val encoded = java.net.URI(null, null, "/echo/$EchoPortraitRevision/$image", null).toASCIIString()
        assertEquals(image, echoPortraitResourcePath(PortraitOrigin + encoded, setOf(image)))
        assertNull(echoPortraitResourcePath(root + image, emptySet()))
        assertNull(echoPortraitResourcePath(root.replace(EchoPortraitRevision, "main") + image, setOf(image)))
        for (path in listOf("Icons/../secret.png", "Icons/x.png?x", "Icons/x.js", "Icons/%2e%2e/x.png", "Other/x.png")) {
            assertFalse(path, validImagePath(path))
            assertNull(echoPortraitResourcePath(root + path, setOf(path)))
        }
        assertNull(portraitResourcePath("$PortraitOrigin/R133/BondageClub/$image", scripts))
    }
}
