package dev.vivian.mobilebc.domain.session.connection

import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only projection of the current session connection state.
 */
interface ConnectionStatusReader {
    val snapshot: StateFlow<BcSessionSnapshot>
}

/**
 * User-facing command for starting a lightweight BC connection.
 */
fun interface SessionConnector {
    suspend fun connect(server: BcServerConfig)
}

/**
 * User-editable recovery policy. This does not expose reconnect or host lifecycle controls.
 */
interface SessionRecoverySettings {
    fun setAutoJoinRoomAfterReconnect(enabled: Boolean)
}
