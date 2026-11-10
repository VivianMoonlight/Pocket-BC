package dev.vivian.mobilebc.domain.session.runtime

import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal const val ONLINE_FRIEND_ROOM_POLL_INTERVAL_MS = 60_000L

internal class OnlineFriendRoomPoller(
    private val scope: CoroutineScope,
    private val sessionSnapshot: StateFlow<BcSessionSnapshot>,
    private val pollIntervalMs: Long = ONLINE_FRIEND_ROOM_POLL_INTERVAL_MS,
    private val refresh: suspend () -> Unit,
) {
    private data class PollOwner(val memberNumber: Int, val accountGeneration: Long)

    private var pollingJob: Job? = null

    init {
        require(pollIntervalMs > 0) { "Online friend room polling interval must be positive." }
    }

    fun start() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            sessionSnapshot
                .map { snapshot ->
                    snapshot.account?.takeIf { snapshot.isVerifiedOnline && snapshot.desiredOnline }?.let { account ->
                        PollOwner(account.memberNumber, snapshot.relationships.accountGeneration)
                    }
                }
                .distinctUntilChanged()
                .collectLatest { owner ->
                    if (owner == null) return@collectLatest
                    while (true) {
                        delay(pollIntervalMs)
                        try {
                            refresh()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            // The next interval is still useful after a transient polling failure.
                        }
                    }
                }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
