package dev.vivian.mobilebc.core.protocol

/**
 * BC-compatible LZString UTF-16 codec.
 *
 * Bondage Club uses LZString 1.4.4 `compressToUTF16`/`decompressFromUTF16` for account fields such
 * as FriendNames and SubmissivesList. This is a direct Kotlin port of that wire algorithm; changing
 * its bit layout would make existing account payloads unreadable.
 */
object LzStringUtf16Codec {
    fun compressToUtf16(input: String?): String {
        if (input == null) return ""
        return compress(input, bitsPerChar = 15) { value -> (value + UTF16_OFFSET).toChar() } + " "
    }

    fun decompressFromUtf16(compressed: String?): String? {
        if (compressed == null) return ""
        if (compressed.isEmpty()) return null
        return decompress(
            length = compressed.length,
            resetValue = UTF16_RESET_VALUE,
            nextValue = { index -> compressed[index].code - UTF16_OFFSET },
        )
    }

    private fun compress(
        uncompressed: String,
        bitsPerChar: Int,
        charFromValue: (Int) -> Char,
    ): String {
        val dictionary = mutableMapOf<String, Int>()
        val dictionaryToCreate = mutableSetOf<String>()
        val writer = BitWriter(bitsPerChar, charFromValue)
        var enlargeIn = 2
        var dictionarySize = 3
        var numberOfBits = 2
        var w = ""

        uncompressed.forEach { character ->
            val c = character.toString()
            if (c !in dictionary) {
                dictionary[c] = dictionarySize++
                dictionaryToCreate += c
            }

            val wc = w + c
            if (wc in dictionary) {
                w = wc
            } else {
                if (w in dictionaryToCreate) {
                    val firstCodeUnit = w.first().code
                    if (firstCodeUnit < 256) {
                        writer.writeValue(numberOfBits, 0)
                        writer.writeValue(8, firstCodeUnit)
                    } else {
                        writer.writeValue(numberOfBits, 1)
                        writer.writeValue(16, firstCodeUnit)
                    }
                    enlargeIn -= 1
                    if (enlargeIn == 0) {
                        enlargeIn = 1 shl numberOfBits
                        numberOfBits += 1
                    }
                    dictionaryToCreate -= w
                } else {
                    writer.writeValue(numberOfBits, requireNotNull(dictionary[w]))
                }

                enlargeIn -= 1
                if (enlargeIn == 0) {
                    enlargeIn = 1 shl numberOfBits
                    numberOfBits += 1
                }

                dictionary[wc] = dictionarySize++
                w = c
            }
        }

        if (w.isNotEmpty()) {
            if (w in dictionaryToCreate) {
                val firstCodeUnit = w.first().code
                if (firstCodeUnit < 256) {
                    writer.writeValue(numberOfBits, 0)
                    writer.writeValue(8, firstCodeUnit)
                } else {
                    writer.writeValue(numberOfBits, 1)
                    writer.writeValue(16, firstCodeUnit)
                }
                enlargeIn -= 1
                if (enlargeIn == 0) {
                    enlargeIn = 1 shl numberOfBits
                    numberOfBits += 1
                }
                dictionaryToCreate -= w
            } else {
                writer.writeValue(numberOfBits, requireNotNull(dictionary[w]))
            }

            enlargeIn -= 1
            if (enlargeIn == 0) {
                numberOfBits += 1
            }
        }

        writer.writeValue(numberOfBits, END_OF_STREAM)
        return writer.finish()
    }

    private fun decompress(
        length: Int,
        resetValue: Int,
        nextValue: (Int) -> Int,
    ): String? {
        val dictionary = mutableListOf<String?>(null, null, null)
        val reader = BitReader(length, resetValue, nextValue)
        val first = when (reader.read(2) ?: return "") {
            0 -> (reader.read(8) ?: return "").toChar().toString()
            1 -> (reader.read(16) ?: return "").toChar().toString()
            END_OF_STREAM -> return ""
            else -> return null
        }

        dictionary += first
        var w = first
        val result = StringBuilder(first)
        var enlargeIn = 4
        var dictionarySize = 4
        var numberOfBits = 3

        while (true) {
            if (reader.index > length) return ""
            var code = reader.read(numberOfBits) ?: return ""
            when (code) {
                0 -> {
                    val value = reader.read(8) ?: return ""
                    dictionary += value.toChar().toString()
                    dictionarySize += 1
                    code = dictionarySize - 1
                    enlargeIn -= 1
                }
                1 -> {
                    val value = reader.read(16) ?: return ""
                    dictionary += value.toChar().toString()
                    dictionarySize += 1
                    code = dictionarySize - 1
                    enlargeIn -= 1
                }
                END_OF_STREAM -> return result.toString()
            }

            if (enlargeIn == 0) {
                enlargeIn = 1 shl numberOfBits
                numberOfBits += 1
            }

            val entry = when {
                code < dictionary.size && dictionary[code] != null -> requireNotNull(dictionary[code])
                code == dictionarySize -> w + w.first()
                else -> return null
            }
            result.append(entry)
            dictionary += w + entry.first()
            dictionarySize += 1
            enlargeIn -= 1
            w = entry

            if (enlargeIn == 0) {
                enlargeIn = 1 shl numberOfBits
                numberOfBits += 1
            }
        }
    }

    private class BitWriter(
        private val bitsPerChar: Int,
        private val charFromValue: (Int) -> Char,
    ) {
        private val output = StringBuilder()
        private var value = 0
        private var position = 0

        fun writeValue(bitCount: Int, initialValue: Int) {
            var remaining = initialValue
            repeat(bitCount) {
                writeBit(remaining and 1)
                remaining = remaining shr 1
            }
        }

        fun finish(): String {
            while (true) {
                value = value shl 1
                if (position == bitsPerChar - 1) {
                    output.append(charFromValue(value))
                    return output.toString()
                }
                position += 1
            }
        }

        private fun writeBit(bit: Int) {
            value = (value shl 1) or bit
            if (position == bitsPerChar - 1) {
                position = 0
                output.append(charFromValue(value))
                value = 0
            } else {
                position += 1
            }
        }
    }

    private class BitReader(
        private val length: Int,
        private val resetValue: Int,
        private val nextValue: (Int) -> Int,
    ) {
        private var value = nextValue(0)
        private var position = resetValue
        var index: Int = 1
            private set

        fun read(bitCount: Int): Int? {
            var bits = 0
            var power = 1
            repeat(bitCount) {
                val bit = value and position
                position = position shr 1
                if (position == 0) {
                    position = resetValue
                    if (index >= length) {
                        value = 0
                    } else {
                        value = nextValue(index)
                    }
                    index += 1
                }
                if (bit > 0) bits = bits or power
                power = power shl 1
            }
            return bits
        }
    }

    private const val UTF16_OFFSET = 32
    private const val UTF16_RESET_VALUE = 16_384
    private const val END_OF_STREAM = 2
}
