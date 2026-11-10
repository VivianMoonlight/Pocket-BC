package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.protocol.DeadLetterRecord
import dev.vivian.mobilebc.core.protocol.DeadLetterSink
import dev.vivian.mobilebc.core.protocol.ProtocolError
import java.security.MessageDigest

internal class StorageBackedDeadLetterSink(
    private val dao: DeadLetterDao,
) : DeadLetterSink {
    override suspend fun record(record: DeadLetterRecord) {
        dao.upsert(record.toPersistedDeadLetter().toEntity())
    }

    private fun DeadLetterRecord.toPersistedDeadLetter(): PersistedDeadLetter {
        val error = reason.toPersistedError()
        return PersistedDeadLetter(
            id = "dl:${sha256Hex("${frame.transportEventName}|${frame.raw}|${error.code}|$recordedAtMillis")}",
            stage = "DECODE",
            eventName = frame.transportEventName,
            codecName = codecName,
            rawPayload = frame.raw.ifBlank { frame.args.joinToString(separator = "\n") },
            errorCode = error.code,
            errorMessage = error.message,
            createdAtMillis = recordedAtMillis,
        )
    }

    private fun ProtocolError.toPersistedError(): PersistedProtocolError = when (this) {
        ProtocolError.MissingPayload -> PersistedProtocolError(
            code = "MISSING_PAYLOAD",
            message = "Missing payload.",
        )
        is ProtocolError.UnknownEvent -> PersistedProtocolError(
            code = "UNKNOWN_EVENT",
            message = "Unknown transport event: $eventName.",
        )
        is ProtocolError.DecodeFailed -> PersistedProtocolError(
            code = "DECODE_FAILED",
            message = reason,
        )
        is ProtocolError.UnsupportedVersion -> PersistedProtocolError(
            code = "UNSUPPORTED_VERSION",
            message = "Unsupported protocol version: $version.",
        )
    }

    private data class PersistedProtocolError(
        val code: String,
        val message: String,
    )
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
