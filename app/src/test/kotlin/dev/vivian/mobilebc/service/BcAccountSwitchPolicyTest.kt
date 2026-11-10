package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginAccount
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcAccountSwitchPolicyTest {
    @Test
    fun switchVersionComesFromTheConfirmedSystemSetting() {
        val state = savedState(version = " r131Beta2 ", confirmed = true)

        assertEquals("R131Beta2", state.confirmedNormalizedBcVersionOrNull())
        assertNull(state.copy(bcVersionConfirmedByUser = false).confirmedNormalizedBcVersionOrNull())
        assertNull(state.copy(bcVersion = "latest").confirmedNormalizedBcVersionOrNull())
    }

    private fun savedState(version: String, confirmed: Boolean): BcSavedLoginState =
        BcSavedLoginState(
            accounts = listOf(savedAccount("alice"), savedAccount("bob")),
            selectedAccountName = "alice",
            saveCredentialsByDefault = true,
            bcVersion = version,
            bcVersionConfirmedByUser = confirmed,
        )

    private fun savedAccount(accountName: String): BcSavedLoginAccount =
        BcSavedLoginAccount(
            accountName = accountName,
            password = "secret",
            serverUrl = "https://example.test/",
        )
}
