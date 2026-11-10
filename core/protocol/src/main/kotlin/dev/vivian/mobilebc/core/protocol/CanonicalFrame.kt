package dev.vivian.mobilebc.core.protocol

data class CanonicalFrame(
    val namespace: String,
    val transportEventName: String,
    val args: List<String>,
    val receivedAtMillis: Long,
    val socketId: String? = null,
    val ackId: String? = null,
    val raw: String,
    val occurrenceId: Long? = null,
    val receiveContext: BcInboundReceiveContext? = null,
)

/** Local receive-time metadata; it is never part of the packet payload or identity. */
data class BcInboundReceiveContext(
    val ownerMemberNumber: Int?,
    val ownerDisplayName: String?,
    val roomName: String?,
    val roomMembers: Map<Int, String>,
    val roomPresenceEpoch: Long,
)

sealed interface DecodeResult<out T> {
    data class Success<T>(val packet: T) : DecodeResult<T>
    data class DeadLetter(
        val frame: CanonicalFrame,
        val reason: ProtocolError,
    ) : DecodeResult<Nothing>
}

interface PacketCodec<DomainPacket> {
    val transportEventNames: Set<String>
    val domainEventName: String

    fun canDecode(frame: CanonicalFrame): Boolean =
        frame.transportEventName in transportEventNames

    fun decode(frame: CanonicalFrame): DecodeResult<DomainPacket>
}

sealed interface ProtocolError {
    data object MissingPayload : ProtocolError
    data class UnknownEvent(val eventName: String) : ProtocolError
    data class DecodeFailed(val reason: String) : ProtocolError
    data class UnsupportedVersion(val version: Int) : ProtocolError
}
