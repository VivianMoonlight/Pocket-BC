package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.core.model.BcCharacterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcItemDisplayTextTest {
    @Test fun emptyLinesKeepTheirPositionAfterTextIsCleared() {
        val item = BcCharacterItem("ItemMisc", "WoodenSign", propertyJson = """{"Text":"","Text2":"第二行"}""")
        assertEquals(listOf("", "第二行"), item.displayText().textLines)
    }

    @Test
    fun craftedNameDescriptionAndNumberedTextRemainLiteralAndRefreshFromTheItem() {
        val item = BcCharacterItem("ItemNeckAccessories", "CustomCollarTag",
            craftJson = """{"Name":"\u5b9a\u5236🔒","Description":"第一行\n第二行 <b>原文</b>"}""",
            propertyJson = """{"Text10":"尾行","Text2":"$2 TargetCharacter","Text":"\u4f60\u597d","Text1":"SourceCharacter","TextColor":"Red","Nested":{"Text":"hidden"}}""")
        val text = item.displayText()
        assertEquals("定制🔒 (姓名牌)", text.displayName("姓名牌"))
        assertEquals("第一行\n第二行 <b>原文</b>", text.description)
        assertEquals(listOf("你好", "SourceCharacter", "$2 TargetCharacter", "尾行"), text.textLines)
        val modified = item.copy(propertyJson = BcItemPropertyJson.mergeForModification(item.propertyJson,
            """{"TypeRecord":{"typed":1}}"""))
        assertEquals(text, modified.displayText())
        assertEquals("新文字", item.copy(propertyJson = """{"Text":"新文字"}""").displayText().textLines.single())
    }

    @Test
    fun blankAndNonTextValuesDoNotReplaceTheBaseNameOrBecomeUserText() {
        val text = BcCharacterItem("ItemArms", "Rope", craftJson = """{"Name":"  ","Description":null}""",
            propertyJson = """{"Text":true,"Text2":{"Text":"hidden"},"Text3":null,"Password":"secret"}""").displayText()
        assertEquals("麻绳", text.displayName("麻绳"))
        assertNull(text.description)
        assertEquals(emptyList<String>(), text.textLines)
    }
}
