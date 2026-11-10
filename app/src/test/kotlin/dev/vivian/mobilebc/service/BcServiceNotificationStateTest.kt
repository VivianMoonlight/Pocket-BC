package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcError
import dev.vivian.mobilebc.domain.session.auth.BcAccountSwitchState
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class BcServiceNotificationStateTest {
    private val staleSession = BcSessionSnapshot(
        state = BcSessionState.Ready,
        connectionHealth = BcConnectionHealth.Stale,
        desiredOnline = true,
        account = BcAccount(123, "Tester", "tester"),
    )

    @Test
    fun staleAuthenticationShowsReconnectingRatherThanStarting() {
        val notification = buildBcServiceNotificationState(staleSession, BcAccountSwitchState())

        assertEquals(BcServiceAvailability.Reconnecting, notification.availability)
        assertEquals("Reconnecting Tester", notification.text)
    }

    @Test
    fun terminalStateTakesPrecedenceOverStaleHealth() {
        val stopped = buildBcServiceNotificationState(
            staleSession.copy(state = BcSessionState.Stopped, desiredOnline = false),
            BcAccountSwitchState(),
        )
        val failed = buildBcServiceNotificationState(
            staleSession.copy(state = BcSessionState.Error(BcError.Network("lost"), recoverable = true)),
            BcAccountSwitchState(),
        )

        assertEquals(BcServiceAvailability.Stopped, stopped.availability)
        assertEquals(BcServiceAvailability.Failed, failed.availability)
    }
}
