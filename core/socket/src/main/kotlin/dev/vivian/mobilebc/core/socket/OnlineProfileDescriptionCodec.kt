package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.protocol.LzStringUtf16Codec

private const val ONLINE_PROFILE_DESCRIPTION_COMPRESSION_MAGIC = '\u256C'
private const val ONLINE_PROFILE_DESCRIPTION_MAX_LENGTH = 10_000

internal fun String?.decodeOnlineProfileDescription(): String? {
    if (this == null) return null
    val decoded = if (startsWith(ONLINE_PROFILE_DESCRIPTION_COMPRESSION_MAGIC)) {
        LzStringUtf16Codec.decompressFromUtf16(substring(1)) ?: ""
    } else {
        this
    }
    return decoded.take(ONLINE_PROFILE_DESCRIPTION_MAX_LENGTH)
}
