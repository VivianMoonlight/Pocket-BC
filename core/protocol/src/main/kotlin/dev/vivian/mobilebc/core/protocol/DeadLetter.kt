package dev.vivian.mobilebc.core.protocol

data class DeadLetterRecord(
    val frame: CanonicalFrame,
    val reason: ProtocolError,
    val recordedAtMillis: Long,
    val codecName: String? = null,
)

interface DeadLetterSink {
    suspend fun record(record: DeadLetterRecord)
}

object NoOpDeadLetterSink : DeadLetterSink {
    override suspend fun record(record: DeadLetterRecord) = Unit
}
