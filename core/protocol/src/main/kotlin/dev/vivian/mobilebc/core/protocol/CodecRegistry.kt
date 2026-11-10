package dev.vivian.mobilebc.core.protocol

class CodecRegistry<DomainPacket>(
    codecs: List<PacketCodec<DomainPacket>>,
    private val deadLetterSink: DeadLetterSink = NoOpDeadLetterSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val codecs: List<PacketCodec<DomainPacket>> =
        codecs.distinctBy { it.domainEventName }

    suspend fun decode(frame: CanonicalFrame): DecodeResult<DomainPacket> {
        val codec = codecs.firstOrNull { it.canDecode(frame) }
        if (codec == null) {
            val result = DecodeResult.DeadLetter(frame, ProtocolError.UnknownEvent(frame.transportEventName))
            deadLetterSink.record(result.toRecord(codecName = null))
            return result
        }

        val result = codec.decode(frame)
        if (result is DecodeResult.DeadLetter) {
            deadLetterSink.record(result.toRecord(codec.domainEventName))
        }
        return result
    }

    private fun DecodeResult.DeadLetter.toRecord(codecName: String?): DeadLetterRecord =
        DeadLetterRecord(
            frame = frame,
            reason = reason,
            recordedAtMillis = nowMillis(),
            codecName = codecName,
        )
}
