package dev.vivian.mobilebc.domain.bctext

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal fun parseBcTranslationPairs(text: String, language: String): Map<String, String> {
    val decodedText = if (language.equals("CN", ignoreCase = true)) {
        text.decodeLegacyBcChineseMojibake()
    } else {
        text
    }
    val lines = decodedText.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { it.startsWith("###") }
        .toList()
    return buildMap {
        var index = 0
        while (index + 1 < lines.size) {
            put(lines[index], lines[index + 1])
            index += 2
        }
    }
}

/** Reverses the legacy Windows-1252 round-trip present in some mirrored BC CN resources. */
private fun String.decodeLegacyBcChineseMojibake(): String {
    val bytes = ByteArray(length)
    forEachIndexed { index, char ->
        val value = char.toWindows1252Byte() ?: return this
        bytes[index] = value.toByte()
    }
    val decoded = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull() ?: return this
    return decoded.takeIf { it.cjkCharacterCount() > cjkCharacterCount() } ?: this
}

private fun Char.toWindows1252Byte(): Int? = when (this) {
    '\u20AC' -> 0x80
    '\u201A' -> 0x82
    '\u0192' -> 0x83
    '\u201E' -> 0x84
    '\u2026' -> 0x85
    '\u2020' -> 0x86
    '\u2021' -> 0x87
    '\u02C6' -> 0x88
    '\u2030' -> 0x89
    '\u0160' -> 0x8A
    '\u2039' -> 0x8B
    '\u0152' -> 0x8C
    '\u017D' -> 0x8E
    '\u2018' -> 0x91
    '\u2019' -> 0x92
    '\u201C' -> 0x93
    '\u201D' -> 0x94
    '\u2022' -> 0x95
    '\u2013' -> 0x96
    '\u2014' -> 0x97
    '\u02DC' -> 0x98
    '\u2122' -> 0x99
    '\u0161' -> 0x9A
    '\u203A' -> 0x9B
    '\u0153' -> 0x9C
    '\u017E' -> 0x9E
    '\u0178' -> 0x9F
    else -> code.takeIf { it in 0x00..0xFF }
}

private fun String.cjkCharacterCount(): Int = count { char ->
    char.code in 0x3400..0x4DBF ||
        char.code in 0x4E00..0x9FFF ||
        char.code in 0xF900..0xFAFF
}
