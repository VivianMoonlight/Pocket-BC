package dev.vivian.mobilebc.domain.bcspeech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcGagGarbleTest {
    // Expected strings were produced by executing R129 Scripts/Speech.js in Node.
    @Test
    fun matchesNativeLatinMappingsAtEveryLevelBoundary() {
        val text = "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val fixtures = listOf(
            1.0 to "abkmefmhijklmnopkrshuvwkyz ABKMEFMHIJKLMNOPKRShUVWKYZ",
            2.0 to "abkmefmhiakamnopkaseuvwkyz ABKMEFMHIAKAMNOPKASeUVWKYZ",
            3.0 to "abemehmhiakamnopkazeuvwkys ABEMEHMHIAKAMNOPKAZEUVWKYS",
            4.0 to "aeemefhhiakamnofkaseueakas AEEMEFHHIAKAMNOFKASEUEAKAS",
            5.0 to "aeememnhiakamnofkaheueakah AEEMEMNHIAKAMNOFKAHEUEAKAH",
            6.0 to "afkmemmheakamnefkaheefakeh AFKMEMMHEAKAMNEFKAHEEFAKEH",
            7.0 to "efkmemmheaaammefkaheefakeh EFKMEMMHEAAAMMEFKAHEEFAKEH",
            8.0 to "ebmmemmheaaammemmahmemmmeh EBMMEMMHEAAAMMEMMAHMEMMMEH",
            9.0 to "ebmmemmheaaammemmahmemmmeh EBMMEMMHEAAAMMEMMAHMEMMMEH",
            9.5 to "ebmmemmheaaammemmahmemmmeh EBMMEMMHEAAAMMEMMAHMEMMMEH",
            10.0 to "mmmmmmmmmmmmmmmmmmmmmmmmmm MMMMMMMMMMMMMMMMMMMMMMMMMM",
            11.0 to "mmmmmmmmmmmmmmmmmmmmmmmmmm MMMMMMMMMMMMMMMMMMMMMMMMMM",
            12.0 to "mmmmmmmmm  mmmmm mmmm m m  MMMMMMMMM  MMMMM MMMM M M ",
            15.0 to "mmmmmmmmm  mmmmm mmmm m m  MMMMMMMMM  MMMMM MMMM M M ",
            16.0 to "m mmm  mm  mmmm  mmmm      M MMM  MM  MMMM  MMMM     ",
            19.0 to "m mmm  mm  mmmm  mmmm      M MMM  MM  MMMM  MMMM     ",
            20.0 to "m   m   m   mmm   mm       M   M   M   MMM   MM      ",
            25.0 to "m   m   m   mmm   mm       M   M   M   MMM   MM      ",
        )
        fixtures.forEach { (intensity, expected) ->
            assertEquals("intensity=$intensity", expected, garble(text, intensity))
        }
    }

    @Test
    fun matchesNativeCyrillicMappingsIncludingMixedLatinReplacementAndSilentSigns() {
        val text = "абвгґдеєжзиіїйклмнопрстуфхцчшщюяёыьъ АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЮЯЁЫЬЪ"
        val fixtures = listOf(
            1.0 to "ауугґдеєггиіїйхлмнопрштуххцчшшюяёы АУУГҐДЕЄГГИІЇЙХЛМНОПРШТУХХЦЧШШЮЯЁЫ",
            2.0 to "ауугґдеєггиіїйхлмнопршчуххччшшюяёы АУУГҐДЕЄГГИІЇЙХЛМНОПРШЧУХХЧЧШШЮЯЁЫ",
            3.0 to "ауугґдеєггиіійхлмнопршчуххччшшюяеы АУУГҐДЕЄГГИІІЙХЛМНОПРШЧУХХЧЧШШЮЯЕЫ",
            4.0 to "ауугґдеєггиіійхлмноуршчуххччшшюяеы АУУГҐДЕЄГГИІІЙХЛМНОУРШЧУХХЧЧШШЮЯЕЫ",
            5.0 to "ауугхдеєггиіійхлмноуршчуххччшшюяеы АУУГХДЕЄГГИІІЙХЛМНОУРШЧУХХЧЧШШЮЯЕЫ",
            6.0 to "eууххдeєггeeeйхeмнeурсчeххччшщeeee EУУХХДEЄГГEEEЙХEМНEУРСЧEХХЧЧШЩEEEE",
            7.0 to "еааффдеєааеееекеммеааакефхкфшаееее ЕААФФДЕЄААЕЕЕЕКЕММЕАААКЕФХКФШАЕЕЕЕ",
            8.0 to "еаааадеєааеееееееееааааефхаашаееее ЕААААДЕЄААЕЕЕЕЕЕЕЕЕААААЕФХААШАЕЕЕЕ",
            10.0 to "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm MMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMMM",
        )
        fixtures.forEach { (intensity, expected) ->
            assertEquals("intensity=$intensity", expected, garble(text, intensity))
        }
    }

    @Test
    fun matchesNativeAccentPredicateAndUppercaseQuirks() {
        val text = "áàãâéèêíìîõóòôúùûñç ÁÀÃÂÉÈÊÍÌÎÕÓÒÔÚÙÛÑÇ ëïü ËÏÜ İßǅ ẞ"
        assertEquals("aaaaeeeiiioooouuuns AAAAEEEIIIOOOOuuuNS    ", garble(text, 1.0))
        assertEquals("aaaaeeeiiioooouuuns AAAAEEEIIIOOOOuuuNS    ", garble(text, 4.0))
        assertEquals("aaaaeeeiiioooouuumh AAAAEEEIIIOOOOuuuMH    ", garble(text, 5.0))
        assertEquals("aaaaeeeiiioooouuumh AAAAEEEIIIOOOOuuuMH    ", garble(text, 9.0))
        assertEquals("mmmmmmmmmmmmmmmmmmm MMMMMMMMMMMMMMMMMMM mmm MMM Mmm M", garble(text, 10.0))
    }

    @Test
    fun matchesNativeSeededChineseAtEveryChanceBoundary() {
        val text = "你好世界，我爱中文。漢字々〇⺀⼀"
        val fixtures = listOf(
            0.0 to text,
            1.0 to "你呜哈界，我爱中呜。恩嗷々〇⺀⼀",
            2.0 to "唔好世界，咕爱中嗷。恩哈々恩⺀⼀",
            3.0 to "恩哈恩界，呜爱中文。漢呜嗷咕⺀⼀",
            4.0 to "啊好唔界，唔嗷嗷恩。唔字唔唔⺀⼀",
            5.0 to "你好嗷啊，我爱恩唔。咕字呜〇⺀⼀",
            6.0 to "咕好哈呜，呜唔呜哈。呜哈哈〇⺀⼀",
            7.0 to "啊咕世界，嗷唔哈嗷。哈恩嗷〇⺀⼀",
            8.0 to "你呜唔界，咕咕嗷咕。唔啊嗷哈⺀⼀",
            9.0 to "咕咕世恩，呜呜呜呜。漢啊啊〇⺀⼀",
            10.0 to "咕咕世恩，呜呜呜呜。漢啊啊〇⺀⼀",
            11.0 to "咕咕世恩，呜呜呜呜。漢啊啊〇⺀⼀",
            12.0 to "咕咕世恩，呜呜呜呜。漢啊啊〇⺀⼀",
            13.0 to "咕恩呜咕，呜嗷呜呜。漢啊啊咕⺀⼀",
            20.0 to "咕恩呜咕，呜嗷呜呜。漢啊啊咕⺀⼀",
            1.5 to "咕恩呜咕，呜嗷呜呜。漢啊啊咕⺀⼀",
        )
        fixtures.forEach { (intensity, expected) ->
            assertEquals("intensity=$intensity", expected, garble(text, intensity))
        }
    }

    @Test
    fun chineseBranchAppliesOnlyToChineseClientLanguage() {
        assertEquals("，。", BcGagGarble.transform("你好世界，漢字。", 4.0, "JP"))
        assertEquals("，。", BcGagGarble.transform("你好世界，漢字。", 4.0, "EN"))
        assertTrue(BcGagGarble.isChinese("々", "CN"))
        assertTrue(BcGagGarble.isChinese("〇", "CN"))
        assertTrue(BcGagGarble.isChinese("𠀀", "CN"))
        assertFalse(BcGagGarble.isChinese("漢", "JP"))
    }

    @Test
    fun preservesNativeOocRangesUnlessExplicitlyIgnored() {
        val text = "Test (Hello) Talk (last (nested) speech) End（wide）"
        assertEquals("hesh (Hello) halk (last (nested) speekh) Enm（wime）", garble(text, 1.0))
        assertEquals("Eese (Hello) Eaak (last (nested) sfeeeh) Enm（aime）", garble(text, 4.0))
        assertEquals("Mmmm (Hello) Mmmm (last (nested) mmmmmm) Mmm（mmmm）", garble(text, 10.0))
        assertEquals("Eese (Heaao) Eaak", BcGagGarble.transform("Test (Hello) Talk", 4.0, "CN", ignoreOoc = true))
        assertEquals("Heaao (Unfinished 😀\n", garble("Hello (Unfinished 😀\n", 4.0))
    }

    @Test
    fun preservesNativeUtf16AndUnsupportedCharacterBehavior() {
        val text = "a😀𝔸𝑥𠀀éᾈ漢字\t\n\r!\$＋€«»‧　"
        assertEquals("ae恩嗷!\$＋€«»‧　", garble(text, 1.0))
        assertEquals("ee漢啊!\$＋€«»‧　", garble(text, 9.0))
        assertEquals("mMMMMMMMMmMm漢啊MMM!\$＋€«»‧　", garble(text, 10.0))
        assertEquals("mMMMMMMMMmMm漢啊MMM!\$＋€«»‧　", garble(text, 20.0))
        assertEquals(text, garble(text, 0.0))
    }

    @Test
    fun retainsNativeFallbackForOutOfRangeIntensity() {
        val text = "abc É漢😀\t"
        listOf(0.5, -1.0, Double.NaN).forEach { intensity ->
            assertEquals("intensity=$intensity", " 漢", garble(text, intensity))
        }
        assertEquals("", garble("", 20.0))
    }

    private fun garble(text: String, intensity: Double): String = BcGagGarble.transform(text, intensity, "CN")
}
