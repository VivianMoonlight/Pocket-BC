package dev.vivian.mobilebc.domain.session.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the currently active managed session for the app.
 *
 * This remains single-active-account. The manager knows only [ManagedSession], not the concrete
 * transport, storage, sync, or runtime implementation behind it.
 */
class AccountSessionManager<Session : ManagedSession>(
    private val createSession: () -> Session,
    private val prepareAccountSwitch: suspend (accountName: String) -> Unit = {},
) : AutoCloseable {
    private val stateLock = Any()
    private var closed = false
    private val lifecycleMutex = Mutex()
    private val _activeSession = MutableStateFlow<Session?>(createSession())

    val activeSession: StateFlow<Session?> = _activeSession.asStateFlow()

    fun requireActiveSession(): Session =
        activeSession.value ?: error("No active BC account session.")

    /**
     * Returns the current active session, creating one if a previous stop/replacement left the
     * manager empty.
     */
    suspend fun ensureActiveSession(): Session = lifecycleMutex.withLock {
        synchronized(stateLock) {
            check(!closed) { "Account session manager is closed." }
            activeSession.value ?: createAndPublishActiveSession()
        }
    }

    /**
     * Switches the single active session to another saved account.
     *
     * The old session is removed and closed before the replacement is published.
     */
    suspend fun switchActiveAccount(accountName: String): Session {
        val normalizedAccountName = accountName.trim()
        require(normalizedAccountName.isNotBlank()) { "Account name is required." }
        return lifecycleMutex.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) { check(!closed) { "Account session manager is closed." } }
            prepareAccountSwitch(normalizedAccountName)
            val context = currentCoroutineContext()
            synchronized(stateLock) {
                context.ensureActive()
                check(!closed) { "Account session manager is closed." }
                clearAndCloseActiveSession()
                createAndPublishActiveSession()
            }
        }
    }

    suspend fun stopActiveSession() {
        lifecycleMutex.withLock {
            synchronized(stateLock) { clearAndCloseActiveSession() }
        }
    }

    /** Permanently ends ownership, including switches still waiting for preparation. */
    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            clearAndCloseActiveSession()
        }
    }

    private fun createAndPublishActiveSession(): Session {
        val next = createSession()
        publishActiveSession(next)
        return next
    }

    private fun clearAndCloseActiveSession() {
        val old = activeSession.value
        publishActiveSession(null)
        old?.close()
    }

    private fun publishActiveSession(session: Session?) {
        _activeSession.value = session
    }
}
