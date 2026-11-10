package dev.vivian.mobilebc.domain.session.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionManagerTest {
    @Test
    fun cancelledSwitchPreparationCannotTransferSessionOwnership() = runTest {
        var preparation: Continuation<Unit>? = null
        var preparationCalls = 0
        val sessions = mutableListOf<FakeSession>()
        val manager = AccountSessionManager(
            createSession = { FakeSession().also(sessions::add) },
            prepareAccountSwitch = {
                if (++preparationCalls == 1) suspendCoroutine { preparation = it }
            },
        )
        val original = manager.requireActiveSession()
        val switch = launch { manager.switchActiveAccount("bob") }
        try {
            runCurrent()
            switch.cancel()
            requireNotNull(preparation).resume(Unit)
            switch.join()

            assertSame(original, manager.activeSession.value)
            assertEquals(0, original.closeCalls)
            assertEquals(1, sessions.size)

            val replacement = manager.switchActiveAccount("charlie")
            assertSame(replacement, manager.activeSession.value)
            assertEquals(1, original.closeCalls)
            assertEquals(2, sessions.size)
        } finally { manager.close() }
    }

    @Test
    fun closeDuringSwitchPreparationCannotPublishAReplacement() = runTest {
        val prepared = CompletableDeferred<Unit>()
        val sessions = mutableListOf<FakeSession>()
        val manager = AccountSessionManager(
            createSession = { FakeSession().also(sessions::add) },
            prepareAccountSwitch = { prepared.await() },
        )
        val switch = async { runCatching { manager.switchActiveAccount("bob") } }
        runCurrent()
        manager.close()
        manager.close()
        assertNull(manager.activeSession.value)
        assertEquals(1, sessions.single().closeCalls)
        prepared.complete(Unit)
        assertTrue(switch.await().isFailure)
        assertEquals(1, sessions.size)
        assertNull(manager.activeSession.value)
        assertTrue(runCatching { manager.ensureActiveSession() }.isFailure)
        assertTrue(runCatching { manager.switchActiveAccount("alice") }.isFailure)
        assertEquals(1, sessions.size)
    }

    @Test
    fun ordinaryStopAllowsRestartButClosePermanentlyEndsOwnership() = runTest {
        val sessions = mutableListOf<FakeSession>()
        val manager = AccountSessionManager(createSession = { FakeSession().also(sessions::add) })
        val initial = manager.requireActiveSession()
        manager.stopActiveSession()
        assertEquals(1, initial.closeCalls)
        assertNull(manager.activeSession.value)
        val restarted = manager.ensureActiveSession()
        assertSame(restarted, manager.ensureActiveSession())
        assertEquals(2, sessions.size)
        manager.close()
        manager.stopActiveSession()
        assertEquals(1, restarted.closeCalls)
        assertTrue(runCatching { manager.ensureActiveSession() }.isFailure)
    }

    private class FakeSession : ManagedSession {
        override val access: SessionAccess get() = error("Unused")
        override val hostLifecycle: SessionHostLifecycle get() = error("Unused")
        var closeCalls = 0
        override fun close() { closeCalls++ }
    }
}
