package dev.vivian.mobilebc.domain.session.runtime

import dev.vivian.mobilebc.domain.character.BcCharacterInteractions
import dev.vivian.mobilebc.domain.session.auth.BcSessionAuth
import dev.vivian.mobilebc.domain.session.chat.BcSessionChat
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.connection.SessionConnector
import dev.vivian.mobilebc.domain.session.connection.SessionRecoverySettings
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRooms
import dev.vivian.mobilebc.domain.session.relationship.BcSessionRelationships
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline

/**
 * Capability aggregate exposed by one active session.
 *
 * Composition code may unpack this aggregate, but feature constructors should receive only the
 * individual capabilities they use.
 */
interface SessionAccess {
    val auth: BcSessionAuth
    val connectionStatus: ConnectionStatusReader
    val connector: SessionConnector
    val recoverySettings: SessionRecoverySettings
    val rooms: BcSessionRooms
    val relationships: BcSessionRelationships
    val characterInteractions: BcCharacterInteractions
    val chat: BcSessionChat
    val timeline: BcSessionTimeline
}

/**
 * Host-only lifecycle controls. This interface must not be passed to features or UI code.
 */
interface SessionHostLifecycle {
    fun restoreSavedSession(
        server: BcServerConfig,
        accountName: String,
        password: String,
        lastRoomName: String? = null,
    )

    fun onNetworkAvailable()
    fun onNetworkLost()
    fun onTransportSuspect(reason: ReconnectReason)
    fun onOperationFailure(reason: ReconnectReason, throwable: Throwable?)
    fun stop()
}

/**
 * One runtime instance and all resources whose lifetime is tied to it.
 */
interface ManagedSession {
    val access: SessionAccess
    val hostLifecycle: SessionHostLifecycle

    fun close()
}
