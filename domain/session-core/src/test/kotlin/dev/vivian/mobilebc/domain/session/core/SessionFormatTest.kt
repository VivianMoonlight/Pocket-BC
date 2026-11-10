package dev.vivian.mobilebc.domain.session.core

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFormatTest {
    @Test
    fun roomSearchAndJoinPreserveOnlineStatusButReauthenticationCannotReuseOldVerification() {
        val online = BcSessionSnapshot(
            state = BcSessionState.Ready,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            desiredOnline = true,
            account = account,
        )

        assertTrue(online.copy(state = BcSessionState.SearchingRooms(BcRoomSearchFilter())).isVerifiedOnline)
        assertTrue(online.copy(state = BcSessionState.JoiningRoom("Other Room")).isVerifiedOnline)
        assertFalse(online.copy(state = BcSessionState.LoggingIn).isVerifiedOnline)
        assertFalse(online.copy(state = BcSessionState.LoginQueued(2)).isVerifiedOnline)
        assertFalse(online.copy(state = BcSessionState.Reconnecting(ReconnectReason.NetworkLost, 1)).isVerifiedOnline)
    }

    private val account = BcAccount(
        memberNumber = 42,
        displayName = "Vivian",
        accountName = "vivian",
    )

    @Test
    fun authenticatedAndOnlineHaveDistinctReconnectSemantics() {
        val reconnecting = BcSessionSnapshot(
            state = BcSessionState.Reconnecting(ReconnectReason.NetworkLost, attempt = 2),
            connectionHealth = BcConnectionHealth.Offline,
            account = account,
        )

        assertEquals(account, reconnecting.account)
        assertFalse(reconnecting.isVerifiedOnline)
        assertTrue(
            BcSessionSnapshot(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = account,
            ).isVerifiedOnline,
        )
        assertFalse(
            BcSessionSnapshot(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
            ).isVerifiedOnline,
        )
    }
}
