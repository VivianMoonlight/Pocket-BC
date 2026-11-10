package dev.vivian.mobilebc.domain.session.runtime

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineFriendRoomPollerTest {
    @Test
    fun changingAccountOrLoginCancelsTheOldRefreshAndStartsANewInterval() = runTest {
        val changes: List<(BcSessionSnapshot) -> BcSessionSnapshot> = listOf(
            { it.copy(account = BcAccount(2, "Bob", "bob")) },
            { it.copy(relationships = it.relationships.nextUnloaded()) },
        )
        changes.forEach { change ->
            val session = MutableStateFlow(BcSessionSnapshot(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = BcAccount(1, "Alice", "alice"),
            ))
            var started = 0
            var cancelled = 0
            val poller = OnlineFriendRoomPoller(backgroundScope, session, 1_000) {
                started++
                try { awaitCancellation() } finally { cancelled++ }
            }
            try {
                poller.start()
                runCurrent()
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(1, started)
                session.value = change(session.value)
                runCurrent()
                assertEquals(1, cancelled)
                advanceTimeBy(999)
                runCurrent()
                assertEquals(1, started)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(2, started)
            } finally {
                poller.stop()
                runCurrent()
            }
        }
    }

    @Test
    fun unrelatedAccountMetadataDoesNotRestartThePollingInterval() = runTest {
        val session = MutableStateFlow(BcSessionSnapshot(
            state = BcSessionState.Ready,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            desiredOnline = true,
            account = BcAccount(1, "Alice", "alice"),
        ))
        var refreshes = 0
        val poller = OnlineFriendRoomPoller(backgroundScope, session, 1_000) { refreshes++ }
        try {
            poller.start()
            runCurrent()
            advanceTimeBy(500)
            session.value = session.value.copy(account = BcAccount(1, "New display name", "alice"))
            runCurrent()
            advanceTimeBy(500)
            runCurrent()
            assertEquals(1, refreshes)
        } finally { poller.stop() }
    }

    @Test
    fun pollsOnlyWhileTheAuthenticatedSessionIsVerifiedOnline() = runTest {
        val session = MutableStateFlow(BcSessionSnapshot())
        var refreshCount = 0
        val poller = OnlineFriendRoomPoller(
            scope = backgroundScope,
            sessionSnapshot = session,
            pollIntervalMs = 1_000,
            refresh = { refreshCount += 1 },
        )
        poller.start()
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, refreshCount)

        session.value = session.value.copy(
            state = BcSessionState.Ready,
            account = BcAccount(1, "Alice", "alice"),
            desiredOnline = true,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
        )
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, refreshCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, refreshCount)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, refreshCount)

        session.value = session.value.copy(connectionHealth = BcConnectionHealth.Offline)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, refreshCount)

        session.value = session.value.copy(
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            desiredOnline = false,
        )
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, refreshCount)

        session.value = session.value.copy(desiredOnline = true)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, refreshCount)

        poller.stop()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, refreshCount)
    }

    @Test
    fun waitsForThePreviousRefreshBeforeSchedulingTheNextOne() = runTest {
        val session = MutableStateFlow(
            BcSessionSnapshot(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = BcAccount(1, "Alice", "alice"),
            ),
        )
        var concurrentRefreshes = 0
        var maximumConcurrentRefreshes = 0
        var refreshCount = 0
        val poller = OnlineFriendRoomPoller(
            scope = backgroundScope,
            sessionSnapshot = session,
            pollIntervalMs = 1_000,
            refresh = {
                refreshCount += 1
                concurrentRefreshes += 1
                maximumConcurrentRefreshes = maxOf(maximumConcurrentRefreshes, concurrentRefreshes)
                delay(1_500)
                concurrentRefreshes -= 1
            },
        )
        poller.start()
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(2, refreshCount)
        assertEquals(1, maximumConcurrentRefreshes)
        poller.stop()
    }
}
