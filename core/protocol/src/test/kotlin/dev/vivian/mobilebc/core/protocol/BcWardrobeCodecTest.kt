package dev.vivian.mobilebc.core.protocol

import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.auth.LoginResult
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.vivian.mobilebc.core.model.BcWardrobeEncodingRejectionReason
import dev.vivian.mobilebc.core.model.BcWardrobeEncodingResult
import dev.vivian.mobilebc.core.model.BcWardrobeInventoryItem
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobeRenameRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSaveRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcWardrobeCodecTest {
    @Test
    fun decodesTheWardrobeProducedByNativeR129CharacterCompressWardrobe() {
        val native = fixture.getAsJsonObject("savedAppearance")
        val compressed = fromCodeUnits(native.getAsJsonArray("compressedCodeUnits"))
        val state = state(JsonPrimitive(compressed).toString())

        assertTrue(state.wardrobeAvailable)
        assertEquals(24, state.slots.size)
        assertEquals(BcWardrobeSlotStatus.Saved, state.slots[0].status)
        assertEquals(
            native.getAsJsonArray("decompressedWardrobe")[0].asJsonArray.map(::bundleItem),
            state.slots[0].appearance,
        )
        assertTrue(state.slots.drop(1).all { it.status == BcWardrobeSlotStatus.Empty })
        assertEquals(native.getAsJsonArray("compactWardrobe"), compact(JsonPrimitive(compressed).toString()))
    }

    @Test
    fun savingMatchesNativeWardrobeAssetBundleAndCharacterCompressWardrobeCodeUnits() {
        val native = fixture.getAsJsonObject("savedAppearance")
        val compressed = fromCodeUnits(native.getAsJsonArray("compressedCodeUnits"))
        val before = state(JsonPrimitive(compressed).toString(), "[\"Alice\"]")
        val appearance = native.getAsJsonArray("sourceAppearance").map { source ->
            val item = source.asJsonObject
            val asset = item.getAsJsonObject("Asset")
            BcWardrobeItem(
                group = asset.getAsJsonObject("Group")["Name"].asString,
                name = asset["Name"].asString,
                colorJson = item["Color"]?.toString(),
                propertyJson = item["Property"]?.toString(),
            )
        }

        val encoded = encoded(BcWardrobeCodec.prepareSave(before, saveRequest(0), appearance))

        assertEquals(native.getAsJsonArray("compressedCodeUnits").map { it.asInt }, json(encoded).asString.map(Char::code))
        assertEquals(native.getAsJsonArray("compactWardrobe"), compact(encoded))
        val saved = compact(encoded)[0].asJsonArray
        assertEquals(json("[\"#FE80AE\",\"Default\",\"#abcdef\"]"), saved[0].asJsonArray[2])
        assertEquals(json("{\"TypeRecord\":{\"button\":1},\"Future\":{\"label\":\"星空💫\",\"values\":[false,null,{\"Color\":\"#123456\"}]}}"), saved[0].asJsonArray[3])
        assertTrue(saved[1].asJsonArray[3].isJsonNull)
        assertTrue(saved[2].asJsonArray[3].isJsonNull)
        assertEquals(4, saved[0].asJsonArray.size())
        assertEquals("[\"Alice\"]", before.rawNamesJson)
        assertTrue(appearance[0].propertyJson!!.contains("Expression"))
    }

    @Test
    fun legacyMetadataNativeCompressionDiscardsMustBePreservedUntilTheSlotIsReplaced() {
        val native = fixture.getAsJsonObject("legacyWithMetadata")
        val legacy = native.getAsJsonArray("legacyWardrobe").toString()
        val decoded = state(legacy)
        val nativeCompressed = fromCodeUnits(native.getAsJsonArray("compressedCodeUnits"))
        val nativeDecoded = state(JsonPrimitive(nativeCompressed).toString())

        assertEquals(BcWardrobeSlotStatus.Saved, nativeDecoded.slots[0].status)
        assertEquals(
            native.getAsJsonArray("decompressedWardrobe")[0].asJsonArray.map(::bundleItem),
            nativeDecoded.slots[0].appearance,
        )
        assertEquals(native.getAsJsonArray("compactWardrobe"), compact(JsonPrimitive(nativeCompressed).toString()))
        assertEquals(BcWardrobeSlotStatus.Unsupported, decoded.slots[0].status)
        assertNull(decoded.slots[0].appearance)
        assertEquals(native.getAsJsonArray("legacyWardrobe")[0].toString(), decoded.slots[0].rawSlotJson)
        assertRejected(BcWardrobeEncodingRejectionReason.UnsupportedWardrobeData, BcWardrobeCodec.prepareSave(decoded, saveRequest(1), emptyList()))
        val replacement = native.getAsJsonArray("decompressedWardrobe")[0].asJsonArray.map(::bundleItem)
        val repaired = compact(encoded(BcWardrobeCodec.prepareSave(decoded, saveRequest(0), replacement)))
        assertEquals(native.getAsJsonArray("compactWardrobe")[0], repaired[0])
        assertEquals(24, repaired.size())
        assertEquals(legacy, decoded.rawWardrobeJson)
    }

    @Test
    fun missingNullAndValidCompressedEmptyWardrobesExposeTwentyFourEmptySlots() {
        listOf(null, "null", "[]", compressed("[]")).forEach { raw ->
            val decoded = state(raw, fallbackName = "Alice")

            assertTrue("raw=$raw", decoded.wardrobeAvailable)
            assertTrue(decoded.namesAvailable)
            assertEquals(raw, decoded.rawWardrobeJson)
            assertEquals((0 until 24).toList(), decoded.slots.map { it.index })
            assertTrue(decoded.slots.all { it.status == BcWardrobeSlotStatus.Empty && it.appearance == emptyList<BcWardrobeItem>() })
            assertTrue(decoded.slots.all { it.name == "Alice" })
            assertEquals(24, compact(encoded(BcWardrobeCodec.prepareSave(decoded, saveRequest(23), emptyList()))).size())
        }
    }

    @Test
    fun shortLegacyWardrobesKeepNullEmptyAndSavedSlotPositions() {
        val decoded = state("[null,[],[{\"Name\":\"Shirt\",\"Group\":\"Cloth\"}]]", "[\"First\",null,3]", "Alice")

        assertEquals(BcWardrobeSlotStatus.Empty, decoded.slots[0].status)
        assertEquals("null", decoded.slots[0].rawSlotJson)
        assertEquals(BcWardrobeSlotStatus.Empty, decoded.slots[1].status)
        assertEquals("[]", decoded.slots[1].rawSlotJson)
        assertEquals(listOf(BcWardrobeItem("Cloth", "Shirt")), decoded.slots[2].appearance)
        assertEquals(listOf("First", "Alice", "Alice", "Alice"), decoded.slots.take(4).map { it.name })
        assertNull(decoded.slots[23].rawSlotJson)
    }

    @Test
    fun corruptWardrobesRemainUnavailableWithTheirRawValuePreserved() {
        listOf("{", "{}", "true", "42", "\"\"", "\"corrupt\"", compressed("true"), compressed("{broken")).forEach { raw ->
            val decoded = state(raw)

            assertFalse("raw=$raw", decoded.wardrobeAvailable)
            assertEquals(raw, decoded.rawWardrobeJson)
            assertTrue(decoded.slots.all { it.status == BcWardrobeSlotStatus.Unsupported && it.appearance == null })
            assertRejected(BcWardrobeEncodingRejectionReason.WardrobeUnavailable, BcWardrobeCodec.prepareSave(decoded, saveRequest(0), emptyList()))
        }
    }

    @Test
    fun lenientJsonSyntaxCannotTurnRawOrDecompressedWardrobeDataIntoAnEmptyWardrobe() {
        listOf(
            "", " \n\t", "/* comment */[]", "[// comment\n]", "['outfit']", "[unquoted]",
            "[null,]", "[] null", "[] []",
        ).forEach { plain ->
            listOf(plain, compressed(plain)).forEach { raw ->
                val decoded = state(raw)

                assertFalse("plain=$plain, raw=$raw", decoded.wardrobeAvailable)
                assertEquals(raw, decoded.rawWardrobeJson)
                assertTrue(decoded.slots.all { it.status == BcWardrobeSlotStatus.Unsupported && it.appearance == null })
                assertRejected(BcWardrobeEncodingRejectionReason.WardrobeUnavailable, BcWardrobeCodec.prepareSave(decoded, saveRequest(0), emptyList()))
            }
        }
    }

    @Test
    fun savingPreservesUntouchedCompactTupleExtensionsUnknownSlotsAndExtraSlots() {
        val original = JsonArray().apply {
            add(json("[[\"Shirt\",\"Cloth\",[\"#123456\",\"Default\"],{\"Future\":{\"text\":\"保留💫\",\"values\":[null,true]}},{\"FutureColumn\":[1,2]}]]"))
            add(json("{\"FutureSlot\":[null,{\"keep\":true}]}"))
            repeat(22) { add(JsonArray()) }
            add(json("[[\"Extra\",\"Cloth\",null,{\"Future\":\"slot 25\"},\"extra tuple field\"]]"))
            add(json("{\"ExtraSlot\":26}"))
        }
        val raw = compressed(original.toString())
        val decoded = state(raw)

        assertEquals(24, decoded.slots.size)
        assertEquals(BcWardrobeSlotStatus.Unsupported, decoded.slots[0].status)
        assertNull(decoded.slots[0].appearance)
        assertEquals(original[0].toString(), decoded.slots[0].rawSlotJson)
        assertEquals(BcWardrobeSlotStatus.Unsupported, decoded.slots[1].status)
        val output = compact(encoded(BcWardrobeCodec.prepareSave(decoded, saveRequest(2), listOf(BcWardrobeItem("Cloth", "Dress")))))
        assertEquals(26, output.size())
        (0 until original.size()).filter { it != 2 }.forEach { index -> assertEquals("slot=$index", original[index], output[index]) }
        assertEquals(json("[[\"Dress\",\"Cloth\",null,null]]"), output[2])
        assertEquals(raw, decoded.rawWardrobeJson)
    }

    @Test
    fun unsupportedCompactNullSlotIsPreservedUntilThatSlotIsExplicitlyReplaced() {
        val raw = compressed("[null,[]]")
        val decoded = state(raw)

        assertTrue(decoded.wardrobeAvailable)
        assertEquals(BcWardrobeSlotStatus.Unsupported, decoded.slots[0].status)
        assertNull(decoded.slots[0].appearance)
        assertEquals("null", decoded.slots[0].rawSlotJson)
        val untouched = compact(encoded(BcWardrobeCodec.prepareSave(decoded, saveRequest(1), emptyList())))
        assertTrue(untouched[0].isJsonNull)
        val repaired = compact(encoded(BcWardrobeCodec.prepareSave(decoded, saveRequest(0), emptyList())))
        assertEquals(json("[]"), repaired[0])
        assertEquals(raw, decoded.rawWardrobeJson)
    }

    @Test
    fun untouchedLegacyItemMetadataBlocksSaveButReplacingItsSlotRepairsIt() {
        listOf("\"Future\":{\"keep\":true}", "\"Craft\":{\"Name\":\"Personal shirt\"}", "\"Difficulty\":12").forEach { metadata ->
            val raw = "[[{\"Name\":\"Shirt\",\"Group\":\"Cloth\",$metadata}],[]]"
            val decoded = state(raw)

            assertEquals(BcWardrobeSlotStatus.Unsupported, decoded.slots[0].status)
            assertNull(decoded.slots[0].appearance)
            assertEquals(json(raw).asJsonArray[0].toString(), decoded.slots[0].rawSlotJson)
            assertRejected(BcWardrobeEncodingRejectionReason.UnsupportedWardrobeData, BcWardrobeCodec.prepareSave(decoded, saveRequest(1), emptyList()))
            val repaired = compact(encoded(BcWardrobeCodec.prepareSave(decoded, saveRequest(0), listOf(BcWardrobeItem("Cloth", "Dress")))))
            assertEquals(json("[[\"Dress\",\"Cloth\",null,null]]"), repaired[0])
            assertEquals(raw, decoded.rawWardrobeJson)
        }
    }

    @Test
    fun replacingAnUnsupportedLegacySlotDoesNotRequireDecodingItsOldAppearance() {
        val decoded = state("[\"unsupported slot\",[]]")

        assertTrue(decoded.wardrobeAvailable)
        assertEquals(BcWardrobeSlotStatus.Unsupported, decoded.slots[0].status)
        val output = compact(encoded(BcWardrobeCodec.prepareSave(decoded, saveRequest(0), emptyList())))
        assertEquals(json("[]"), output[0])
        assertEquals(24, output.size())
    }

    @Test
    fun malformedItemDataCannotBeSaved() {
        val invalid = listOf(
            BcWardrobeItem("", "Shirt"),
            BcWardrobeItem("Cloth", "Shirt", colorJson = "{"),
            BcWardrobeItem("Cloth", "Shirt", colorJson = "{}"),
            BcWardrobeItem("Cloth", "Shirt", colorJson = "42"),
            BcWardrobeItem("Cloth", "Shirt", colorJson = "[\"Default\",null]"),
            BcWardrobeItem("Cloth", "Shirt", colorJson = "[\"Default\",true]"),
            BcWardrobeItem("Cloth", "Shirt", propertyJson = "{"),
            BcWardrobeItem("Cloth", "Shirt", propertyJson = "[]"),
            BcWardrobeItem("Cloth", "Shirt", propertyJson = "\"property\""),
            BcWardrobeItem("Cloth", "Shirt", propertyJson = "false"),
        )
        val decoded = state()

        invalid.forEach { item ->
            assertRejected(BcWardrobeEncodingRejectionReason.UnsafeItemData, BcWardrobeCodec.prepareSave(decoded, saveRequest(0), listOf(item)))
        }
        assertEquals("[]", decoded.rawWardrobeJson)
    }

    @Test
    fun nativeDefaultLimbAssetsWithEmptyNamesCanBeSavedAndReadBack() {
        val appearance = listOf("ArmsLeft", "ArmsRight", "HandsLeft", "HandsRight").map { group ->
            BcWardrobeItem(group, "", colorJson = "[\"Default\"]")
        }
        val saved = encoded(BcWardrobeCodec.prepareSave(state(), saveRequest(0), appearance))
        val readBack = state(saved)

        assertEquals(BcWardrobeSlotStatus.Saved, readBack.slots[0].status)
        assertEquals(appearance, readBack.slots[0].appearance)
        assertEquals(
            json("[[\"\",\"ArmsLeft\",[\"Default\"],null],[\"\",\"ArmsRight\",[\"Default\"],null],[\"\",\"HandsLeft\",[\"Default\"],null],[\"\",\"HandsRight\",[\"Default\"],null]]"),
            compact(saved)[0],
        )
    }

    @Test
    fun lenientColorAndPropertyJsonSnippetsCannotBeSaved() {
        val decoded = state()
        listOf("", "  ", "'Default'", "Default", "/* comment */\"Default\"", "[\"Default\",]", "\"Default\" null").forEach { color ->
            assertRejected(
                BcWardrobeEncodingRejectionReason.UnsafeItemData,
                BcWardrobeCodec.prepareSave(decoded, saveRequest(0), listOf(BcWardrobeItem("Cloth", "Shirt", colorJson = color))),
            )
        }
        listOf("", " \n\t", "{Expression:\"Closed\"}", "{'Expression':'Closed'}", "/* comment */{}", "{} null", "{} {}").forEach { property ->
            assertRejected(
                BcWardrobeEncodingRejectionReason.UnsafeItemData,
                BcWardrobeCodec.prepareSave(decoded, saveRequest(0), listOf(BcWardrobeItem("Cloth", "Shirt", propertyJson = property))),
            )
        }
        assertEquals("[]", decoded.rawWardrobeJson)
    }

    @Test
    fun unsupportedItemShapesDoNotBecomeAnEmptySavedOutfit() {
        listOf(
            "[[null]]",
            "[[{\"Name\":42,\"Group\":\"Cloth\"}]]",
            "[[{\"Name\":\"Shirt\",\"Group\":\"Cloth\",\"Color\":true}]]",
            "[[{\"Name\":\"Shirt\",\"Group\":\"Cloth\",\"Property\":[]}]]",
            compressed("[[[\"Shirt\",\"Cloth\",[\"Default\",42],null]]]"),
            compressed("[[[\"Shirt\",\"Cloth\",null,\"invalid\"]]]"),
            compressed("[[[\"Shirt\",\"Cloth\",null,null,null]]]"),
        ).forEach { raw ->
            val decoded = state(raw)

            assertTrue(decoded.wardrobeAvailable)
            assertEquals(BcWardrobeSlotStatus.Unsupported, decoded.slots[0].status)
            assertNull(decoded.slots[0].appearance)
            assertTrue(decoded.slots.drop(1).all { it.status == BcWardrobeSlotStatus.Empty })
        }
    }

    @Test
    fun saveAndRenameRejectOutOfRangeSlotsAndStaleRevisions() {
        val decoded = state()
        listOf(-1, 24, Int.MAX_VALUE).forEach { slot ->
            assertRejected(BcWardrobeEncodingRejectionReason.InvalidSlot, BcWardrobeCodec.prepareSave(decoded, saveRequest(slot), emptyList()))
            assertRejected(BcWardrobeEncodingRejectionReason.InvalidSlot, BcWardrobeCodec.prepareRename(decoded, renameRequest(slot, "Valid"), "Alice"))
        }
        listOf(0L, 41L, 43L, Long.MAX_VALUE).forEach { revision ->
            assertRejected(BcWardrobeEncodingRejectionReason.RevisionChanged, BcWardrobeCodec.prepareSave(decoded, BcWardrobeSaveRequest(0, revision), emptyList()))
            assertRejected(BcWardrobeEncodingRejectionReason.RevisionChanged, BcWardrobeCodec.prepareRename(decoded, BcWardrobeRenameRequest(0, revision, "Valid"), "Alice"))
        }
    }

    @Test
    fun renameAcceptsEmptyAndAsciiNamesWithoutTrimmingSpaces() {
        val decoded = state(namesJson = "[\"Original\"]")
        listOf("", " ", "  A 1  ", "abcdefghijklmnopqrst", "ABCDEFGHIJKLMNOPQRSTUVWXYZ".take(20)).forEach { name ->
            val output = json(encoded(BcWardrobeCodec.prepareRename(decoded, renameRequest(0, name), "Alice"))).asJsonArray

            assertEquals(name, output[0].asString)
            assertEquals(25, output.size())
            assertTrue(output.drop(1).all { it.asString == "Alice" })
        }
        assertEquals("[\"Original\"]", decoded.rawNamesJson)
    }

    @Test
    fun renameRejectsNonAsciiPunctuationControlCharactersAndNamesLongerThanTwenty() {
        listOf("a".repeat(21), "é", "中文", "💫", "A_B", "A-B", "A.B", "A\tB", "A\nB").forEach { name ->
            assertRejected(BcWardrobeEncodingRejectionReason.InvalidName, BcWardrobeCodec.prepareRename(state(), renameRequest(0, name), "Alice"))
        }
    }

    @Test
    fun missingAndNullNamesInitializeTheNativeTwentyFiveNameEntries() {
        listOf(null, "null", "[]").forEach { raw ->
            val decoded = state(namesJson = raw, fallbackName = "Alice")
            val output = json(encoded(BcWardrobeCodec.prepareRename(decoded, renameRequest(23, "Last Slot"), "Alice"))).asJsonArray

            assertTrue(decoded.namesAvailable)
            assertEquals(25, output.size())
            assertEquals("Last Slot", output[23].asString)
            assertTrue(output.filterIndexed { index, _ -> index != 23 }.all { it.asString == "Alice" })
            assertEquals(raw, decoded.rawNamesJson)
        }
    }

    @Test
    fun renamePreservesExtraNamesAndUntouchedNonStringEntries() {
        val original = JsonArray().apply {
            repeat(26) { add("Name $it") }
            add(json("{\"FutureName\":\"保留💫\"}"))
            add(json("null"))
        }
        val decoded = state(namesJson = original.toString())
        val output = json(encoded(BcWardrobeCodec.prepareRename(decoded, renameRequest(2, "New Name"), "Alice"))).asJsonArray

        assertEquals(original.size(), output.size())
        original.forEachIndexed { index, value -> assertEquals(if (index == 2) JsonPrimitive("New Name") else value, output[index]) }
        assertEquals(original.toString(), decoded.rawNamesJson)
    }

    @Test
    fun corruptNamesRemainUnavailableAndDoNotBlockSavingTheWardrobe() {
        listOf("{", "{}", "true", "42", "\"names\"").forEach { raw ->
            val decoded = state(namesJson = raw, fallbackName = "Alice")

            assertFalse(decoded.namesAvailable)
            assertEquals(raw, decoded.rawNamesJson)
            assertTrue(decoded.slots.all { it.name == null })
            assertRejected(BcWardrobeEncodingRejectionReason.NamesUnavailable, BcWardrobeCodec.prepareRename(decoded, renameRequest(0, "New"), "Alice"))
            encoded(BcWardrobeCodec.prepareSave(decoded, saveRequest(0), emptyList()))
        }
        val unavailableWardrobe = state("{}", "[\"Original\"]")
        assertEquals("Renamed", json(encoded(BcWardrobeCodec.prepareRename(unavailableWardrobe, renameRequest(0, "Renamed"), "Alice"))).asJsonArray[0].asString)
    }

    @Test
    fun inventoryDecodesLegacyObjectListsDictionariesAndNativeLzTuples() {
        val native = fixture.getAsJsonObject("inventory")
        val compressed = fromCodeUnits(native.getAsJsonArray("compressedLegacyCodeUnits"))
        val expected = listOf(BcWardrobeInventoryItem("Cloth", "Shirt"), BcWardrobeInventoryItem("ClothAccessory", "星空💫"))
        val legacyVariants = listOf(
            "[{\"Name\":\"Shirt\",\"Group\":\"Cloth\",\"Future\":true},{\"Name\":\"星空💫\",\"Group\":\"ClothAccessory\"}]",
            "{\"Cloth\":[\"Shirt\"],\"ClothAccessory\":[\"星空💫\"]}",
            JsonPrimitive(compressed).toString(),
        )

        legacyVariants.forEach { raw ->
            val inputs = BcWardrobeCodec.decodePrivateInputs("{}", raw, null)

            assertTrue(inputs.gameAvailable)
            assertTrue(inputs.inventoryAvailable)
            assertEquals(raw, inputs.inventoryJson)
            assertEquals(expected, inputs.inventoryItems)
            assertNull(inputs.inventoryIds)
        }
        assertEquals(native.getAsJsonArray("legacyTuples"), json(LzStringUtf16Codec.decompressFromUtf16(compressed)!!))
    }

    @Test
    fun nativeInventoryDataUtf16IdsAndRangesTakePriorityOverLegacyInventory() {
        val native = fixture.getAsJsonObject("inventory")
        val data = fromCodeUnits(native.getAsJsonArray("dataCodeUnits"))

        listOf("{broken", "[{\"Name\":\"Other\",\"Group\":\"Cloth\"}]").forEach { legacy ->
            val inputs = BcWardrobeCodec.decodePrivateInputs("{\"WardrobeClothesOnly\":true}", legacy, data)

            assertTrue(inputs.inventoryAvailable)
            assertEquals(native.getAsJsonArray("ids").map { it.asInt }, inputs.inventoryIds)
            assertEquals(emptyList<BcWardrobeInventoryItem>(), inputs.inventoryItems)
            assertEquals(data, inputs.inventoryData)
            assertEquals(legacy, inputs.inventoryJson)
        }
        assertEquals(native.getAsJsonArray("ids").map { it.asInt }, native.getAsJsonArray("nativeItems").map { it.asJsonObject["Name"].asString.removePrefix("Asset").toInt() })
    }

    @Test
    fun loginAccountNonStringInventoryDataFallsBackToLegacyInventoryWithoutCoercion() {
        val legacy = "[{\"Name\":\"Shirt\",\"Group\":\"Cloth\"}]"
        listOf("1e2", "true", "false", "[]", "[101]", "{}", "{\"code\":101}", "null").forEach { raw ->
            val account = loginAccount("\"InventoryData\":$raw,\"Inventory\":$legacy")
            val inputs = BcWardrobeCodec.decodePrivateInputs(account.game?.toString(), account.inventory?.toString(), account.inventoryData)

            assertEquals("account-1001", account.id)
            assertEquals(1001, account.memberNumber)
            assertEquals("Alice", account.name)
            assertNull("InventoryData=$raw", account.inventoryData)
            assertEquals(json(legacy), account.inventory)
            assertTrue(inputs.inventoryAvailable)
            assertEquals(listOf(BcWardrobeInventoryItem("Cloth", "Shirt")), inputs.inventoryItems)
            assertNull(inputs.inventoryIds)
        }
    }

    @Test
    fun loginAccountRealInventoryDataPreservesEveryUtf16CodeUnitAndTakesPriority() {
        val native = fixture.getAsJsonObject("inventory")
        val data = fromCodeUnits(native.getAsJsonArray("dataCodeUnits"))
        val account = loginAccount("\"InventoryData\":${JsonPrimitive(data)},\"Inventory\":{\"Cloth\":[\"Legacy Shirt\"]}")
        val inputs = BcWardrobeCodec.decodePrivateInputs(account.game?.toString(), account.inventory?.toString(), account.inventoryData)

        assertEquals(data, account.inventoryData)
        assertEquals(native.getAsJsonArray("dataCodeUnits").map { it.asInt }, account.inventoryData!!.map(Char::code))
        assertTrue(inputs.inventoryAvailable)
        assertEquals(native.getAsJsonArray("ids").map { it.asInt }, inputs.inventoryIds)
        assertEquals(emptyList<BcWardrobeInventoryItem>(), inputs.inventoryItems)
        assertEquals(data, inputs.inventoryData)
    }

    @Test
    fun loginAccountOwnerStringsRemainExactAndNonStringsCannotBecomeNpcOwnerNames() {
        val legacy = "{\"Cloth\":[\"Shirt\"]}"
        listOf("Mistress Alice", "  NPC Owner  ", "NPC 保留💫", "123", "").forEach { owner ->
            val account = loginAccount("\"Owner\":${JsonPrimitive(owner)},\"Inventory\":$legacy")
            val inputs = BcWardrobeCodec.decodePrivateInputs(
                account.game?.toString(), account.inventory?.toString(), account.inventoryData,
                account.privateCharacter?.toString(), account.owner,
            )

            assertEquals(owner, account.owner)
            assertEquals(owner, inputs.owner)
            assertTrue(inputs.inventoryAvailable)
        }
        listOf("1e2", "true", "false", "[]", "[\"NPC\"]", "{}", "{\"Name\":\"NPC\"}", "null").forEach { raw ->
            val account = loginAccount("\"Owner\":$raw,\"Inventory\":$legacy")
            val inputs = BcWardrobeCodec.decodePrivateInputs(
                account.game?.toString(), account.inventory?.toString(), account.inventoryData,
                account.privateCharacter?.toString(), account.owner,
            )

            assertNull("Owner=$raw", account.owner)
            assertNull(inputs.owner)
            assertEquals(listOf(BcWardrobeInventoryItem("Cloth", "Shirt")), inputs.inventoryItems)
        }
    }

    @Test
    fun emptyInventoryDataFallsBackToLegacyWhileNullInventoryIsAnAvailableEmptyInventory() {
        val inputs = BcWardrobeCodec.decodePrivateInputs(null, "{\"Cloth\":[\"Shirt\"]}", "")

        assertTrue(inputs.inventoryAvailable)
        assertEquals(listOf(BcWardrobeInventoryItem("Cloth", "Shirt")), inputs.inventoryItems)
        assertNull(inputs.inventoryIds)
        assertEquals("", inputs.inventoryData)
        listOf(null, "null", "[]", "{}").forEach { raw ->
            val empty = BcWardrobeCodec.decodePrivateInputs(null, raw, null)
            assertTrue("raw=$raw", empty.inventoryAvailable)
            assertEquals(emptyList<BcWardrobeInventoryItem>(), empty.inventoryItems)
        }
    }

    @Test
    fun malformedLegacyInventoryRemainsUnavailableAndPreservesItsRawData() {
        listOf(
            "{", "true", "42", "\"corrupt\"", "[null]", "[[\"Shirt\",\"Cloth\"]]",
            "[{\"Group\":\"Cloth\"}]", "{\"Cloth\":\"Shirt\"}", "{\"Cloth\":[42]}",
            compressed("{}"), compressed("[[\"Shirt\",42]]"),
        ).forEach { raw ->
            val inputs = BcWardrobeCodec.decodePrivateInputs("{}", raw, null)

            assertFalse("raw=$raw", inputs.inventoryAvailable)
            assertNull(inputs.inventoryItems)
            assertNull(inputs.inventoryIds)
            assertEquals(raw, inputs.inventoryJson)
            assertTrue(inputs.gameAvailable)
        }
    }

    @Test
    fun privateGameAvailabilityValidatesOnlyItsOwnRawObjectShape() {
        listOf(null, "null", "{}", "{\"WardrobeClothesOnly\":true,\"Future\":{\"keep\":true}}").forEach { raw ->
            val inputs = BcWardrobeCodec.decodePrivateInputs(raw, "[]", null)
            assertTrue("raw=$raw", inputs.gameAvailable)
            assertEquals(raw, inputs.gameJson)
        }
        listOf("{", "[]", "true", "42", "\"game\"").forEach { raw ->
            val inputs = BcWardrobeCodec.decodePrivateInputs(raw, "[]", null)
            assertFalse("raw=$raw", inputs.gameAvailable)
            assertEquals(raw, inputs.gameJson)
            assertTrue(inputs.inventoryAvailable)
        }
        val privateInputs = BcWardrobeCodec.decodePrivateInputs("{}", "[]", null)
        val decoded = BcWardrobeCodec.decodeState(1001, "session-1001", 42, "[]", null, privateInputs = privateInputs)
        assertEquals(privateInputs, decoded.privateInputs)
        assertEquals(1001, decoded.memberNumber)
        assertEquals("session-1001", decoded.selfRuleLogSessionId)
        assertEquals(42L, decoded.revision)
    }

    @Test
    fun privateCharactersPreserveTheirRawArrayAndValidateIndependently() {
        listOf(null, "null", "[]", "[{\"Name\":\"Private NPC\",\"Future\":{\"keep\":true}}]").forEach { raw ->
            val inputs = BcWardrobeCodec.decodePrivateInputs("{}", "[]", null, privateCharacterJson = raw)

            assertTrue("raw=$raw", inputs.privateCharacterAvailable)
            assertEquals(raw, inputs.privateCharacterJson)
            assertTrue(inputs.gameAvailable)
            assertTrue(inputs.inventoryAvailable)
        }
        listOf("{", "{}", "true", "42", "\"private characters\"").forEach { raw ->
            val inputs = BcWardrobeCodec.decodePrivateInputs("{}", "[]", null, privateCharacterJson = raw)

            assertFalse("raw=$raw", inputs.privateCharacterAvailable)
            assertEquals(raw, inputs.privateCharacterJson)
            assertTrue(inputs.gameAvailable)
            assertTrue(inputs.inventoryAvailable)
        }
    }

    private fun state(wardrobeJson: String? = "[]", namesJson: String? = null, fallbackName: String? = null) =
        BcWardrobeCodec.decodeState(1001, "session-1001", 42, wardrobeJson, namesJson, fallbackName)

    private fun saveRequest(slot: Int) = BcWardrobeSaveRequest(slot, 42)

    private fun renameRequest(slot: Int, name: String) = BcWardrobeRenameRequest(slot, 42, name)

    private fun loginAccount(fieldsJson: String): ServerAccountData {
        val payload = """{"ID":"account-1001","MemberNumber":1001,"Name":"Alice",$fieldsJson}"""
        val response = RazorWireParser.parse("LoginResponse", payload) as RazorWireEvent.LoginResponse
        return (response.value as LoginResult.Success).data
    }

    private fun encoded(result: BcWardrobeEncodingResult): String {
        assertTrue("Expected encoded wardrobe data, got $result", result is BcWardrobeEncodingResult.Encoded)
        return (result as BcWardrobeEncodingResult.Encoded).valueJson
    }

    private fun assertRejected(reason: BcWardrobeEncodingRejectionReason, result: BcWardrobeEncodingResult) {
        assertEquals(BcWardrobeEncodingResult.Rejected(reason), result)
    }

    private fun compressed(compactJson: String): String = JsonPrimitive(LzStringUtf16Codec.compressToUtf16(compactJson)).toString()

    private fun compact(valueJson: String): JsonArray = json(LzStringUtf16Codec.decompressFromUtf16(json(valueJson).asString)!!).asJsonArray

    private fun json(value: String): JsonElement = JsonParser.parseString(value)

    private fun fromCodeUnits(units: JsonArray): String = units.map { it.asInt.toChar() }.joinToString("")

    private fun bundleItem(value: JsonElement): BcWardrobeItem {
        val bundle = value.asJsonObject
        return BcWardrobeItem(
            group = bundle["Group"].asString,
            name = bundle["Name"].asString,
            colorJson = bundle["Color"]?.takeUnless { it.isJsonNull }?.toString(),
            propertyJson = bundle["Property"]?.takeUnless { it.isJsonNull }?.toString(),
        )
    }

    private val fixture: JsonObject by lazy {
        requireNotNull(javaClass.getResourceAsStream("/wardrobe/r129-native-wardrobe.json")) {
            "Native R129 wardrobe fixture is missing"
        }.bufferedReader(Charsets.UTF_8).use { json(it.readText()).asJsonObject }
    }
}
