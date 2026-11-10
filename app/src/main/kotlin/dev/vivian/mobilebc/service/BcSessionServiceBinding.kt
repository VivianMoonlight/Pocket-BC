package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.BcNotificationSettings
import dev.vivian.mobilebc.core.model.BcNotificationDiagnostics
import dev.vivian.mobilebc.core.socket.PacketLogStore
import dev.vivian.mobilebc.domain.session.auth.BcAccountSwitchState
import dev.vivian.mobilebc.domain.session.auth.BcSessionPreferencesStore
import dev.vivian.mobilebc.domain.session.runtime.SessionAccess
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable Activity-to-service boundary.
 *
 * Android clients receive this interface from the Binder; they never receive the Service instance
 * or any host lifecycle control. [activeSession] is a projection of the service-owned managed
 * session, not a second owner of session state.
 */
interface BcSessionServiceBinding {
    val activeSession: StateFlow<SessionAccess?>
    val accountSwitchState: StateFlow<BcAccountSwitchState>
    val sessionPreferences: BcSessionPreferencesStore

    fun switchAccount(accountName: String)
    fun currentPacketLog(): PacketLogStore
    val notificationSettings: StateFlow<BcNotificationSettings>
    fun readNotificationDiagnostics(): BcNotificationDiagnostics
    fun saveNotificationSettings(settings: BcNotificationSettings)
    val deobfuscationEnabled: StateFlow<Boolean>
    fun saveDeobfuscationEnabled(enabled: Boolean)
}
