package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.room.RoomManagementResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

/** Coordinates the two room-management response channels, which have no wire request IDs. */
internal class RazorRoomManagementRequests(
    private val requestTimeoutMs: Long = 30_000L,
) {
    data class Scope(
        val connectionGeneration: Long,
        val accountMemberNumber: Int,
        val loginSessionId: String,
        val roomPresenceEpoch: Long,
        val roomName: String?,
    ) {
        internal fun hasSameLogin(other: Scope): Boolean =
            connectionGeneration == other.connectionGeneration &&
                accountMemberNumber == other.accountMemberNumber &&
                loginSessionId == other.loginSessionId
    }

    enum class Kind { Create, Update }

    sealed interface Result {
        data class Authoritative(val acknowledged: Boolean) : Result
        data class Rejected(val token: String) : Result
        data class InvalidResponse(val payloadJson: String) : Result
        data class TimedOut(val emitted: Boolean, val acknowledged: Boolean) : Result
    }

    class RequestTicket internal constructor(
        val scope: Scope,
        val kind: Kind,
        val expectedRoomName: String,
        private val markEmittingAction: () -> Unit,
    ) {
        /** Call in the final room/account/transport guard, immediately before socket emit. */
        fun markEmitting() = markEmittingAction()
    }

    class BusyException : IllegalStateException("A room management request is already pending.")

    class ChannelQuarantinedException(val kind: Kind) :
        IllegalStateException("The previous room ${kind.name.lowercase()} response is still unresolved.")

    private class Pending(
        val scope: Scope,
        val kind: Kind,
        val expectedRoomName: String,
        var waitingJob: Job?,
    ) {
        val response = CompletableDeferred<Result>()
        lateinit var ticket: RequestTicket
        var emitted = false
        var acknowledged = false
        var terminal: Terminal? = null
    }

    private sealed interface Terminal {
        data class Value(val result: Result) : Terminal
        data class Failure(val throwable: Throwable) : Terminal
    }

    private data class Quarantined(
        val pending: Pending,
        val releaseAfterResponse: Boolean,
    )

    private val lock = Any()
    private var active: Pending? = null
    private val quarantined = mutableMapOf<Kind, Quarantined>()

    val pendingTicket: RequestTicket?
        get() = synchronized(lock) { active?.ticket }

    val isOperationActive: Boolean
        get() = synchronized(lock) { active != null }

    val hasUnknownOutcome: Boolean
        get() = synchronized(lock) { quarantined.values.any { !it.releaseAfterResponse } }

    fun ensureRoomTransitionAvailable() {
        synchronized(lock) {
            if (active != null) throw BusyException()
            quarantined.entries.firstOrNull { !it.value.releaseAfterResponse }?.let { (kind, _) ->
                throw ChannelQuarantinedException(kind)
            }
        }
    }

    suspend fun await(
        scope: Scope,
        kind: Kind,
        expectedRoomName: String,
        send: suspend (RequestTicket) -> Unit,
    ): Result {
        val callerJob = currentCoroutineContext()[Job]
        val pending = synchronized(lock) {
            if (active != null) throw BusyException()
            if (kind in quarantined) throw ChannelQuarantinedException(kind)
            Pending(scope, kind, expectedRoomName, callerJob).also { request ->
                request.ticket = RequestTicket(scope, kind, expectedRoomName) { markEmitting(request) }
                active = request
            }
        }
        return try {
            withTimeoutOrNull(requestTimeoutMs) {
                val waitingJob = currentCoroutineContext()[Job]
                synchronized(lock) { pending.waitingJob = waitingJob }
                send(pending.ticket)
                pending.response.await()
            } ?: timedOut(pending)
        } finally {
            abandon(pending)
        }
    }

    /** ACK scopes match the login, since a valid create or rename may already have changed the room. */
    fun handleResponse(scope: Scope, kind: Kind, response: RoomManagementResponse): Boolean {
        var completion: Pair<CompletableDeferred<Result>, Result>? = null
        val handled = synchronized(lock) {
            val retired = quarantined[kind]
            if (retired != null && retired.pending.scope.hasSameLogin(scope)) {
                if (retired.releaseAfterResponse) quarantined.remove(kind)
                return@synchronized true
            }
            val pending = active
            if (pending == null || pending.kind != kind || !pending.emitted ||
                !pending.scope.hasSameLogin(scope)
            ) return@synchronized false
            val result = when (response) {
                is RoomManagementResponse.Valid -> {
                    if (response.token == kind.successToken()) {
                        pending.acknowledged = true
                        null
                    } else {
                        Result.Rejected(response.token)
                    }
                }
                is RoomManagementResponse.Invalid -> Result.InvalidResponse(response.payloadJson)
            }
            if (result != null) {
                active = null
                pending.terminal = Terminal.Value(result)
                if (result is Result.InvalidResponse) quarantineUnknownOutcome(pending)
                completion = pending.response to result
            }
            true
        }
        completion?.let { (pending, result) -> pending.complete(result) }
        return handled
    }

    /** The gateway supplies the original scope only after matching a real Sync/Properties update. */
    fun confirm(scope: Scope, currentRoomName: String, editedFieldsMatched: Boolean): Boolean {
        var completion: Pair<CompletableDeferred<Result>, Result>? = null
        val confirmed = synchronized(lock) {
            val pending = active
            if (pending == null || pending.scope != scope || !pending.emitted ||
                pending.expectedRoomName != currentRoomName || !editedFieldsMatched
            ) return@synchronized false
            active = null
            if (!pending.acknowledged) {
                quarantined[pending.kind] = Quarantined(pending, releaseAfterResponse = true)
            }
            val result = Result.Authoritative(pending.acknowledged)
            pending.terminal = Terminal.Value(result)
            completion = pending.response to result
            true
        }
        completion?.let { (pending, result) -> pending.complete(result) }
        return confirmed
    }

    /** Room loss invalidates only the active operation and preserves its uncorrelated ACK channel. */
    fun abortActive(failure: Throwable) {
        val pending = synchronized(lock) {
            active.also { pending ->
                active = null
                if (pending != null) {
                    pending.terminal = Terminal.Failure(failure)
                    quarantineUnknownOutcome(pending)
                }
            }
        }
        pending?.response?.completeExceptionally(failure)
    }

    /** Login or connection changes retire every old owner; their eventual finally blocks are harmless. */
    fun reset(failure: Throwable = IllegalStateException("The room management session changed.")) {
        val pending = synchronized(lock) {
            active.also {
                active = null
                quarantined.clear()
                it?.terminal = Terminal.Failure(failure)
            }
        }
        pending?.response?.completeExceptionally(failure)
    }

    private fun markEmitting(pending: Pending) {
        synchronized(lock) {
            if (active !== pending || !pending.response.isActive || pending.waitingJob?.isActive == false) {
                throw CancellationException("The room management request is no longer active.")
            }
            check(!pending.emitted) { "The room management request was already emitted." }
            pending.emitted = true
        }
    }

    private fun timedOut(pending: Pending): Result = synchronized(lock) {
        when (val terminal = pending.terminal) {
            is Terminal.Value -> return@synchronized terminal.result
            is Terminal.Failure -> throw terminal.throwable
            null -> Unit
        }
        val result = Result.TimedOut(pending.emitted, pending.acknowledged)
        if (active === pending) {
            active = null
            pending.terminal = Terminal.Value(result)
            quarantineUnknownOutcome(pending)
        }
        result
    }

    private fun abandon(pending: Pending) {
        synchronized(lock) {
            if (active !== pending) return
            active = null
            quarantineUnknownOutcome(pending)
        }
    }

    private fun quarantineUnknownOutcome(pending: Pending) {
        if (pending.emitted) {
            quarantined[pending.kind] = Quarantined(pending, releaseAfterResponse = false)
        }
    }

    private fun Kind.successToken(): String = when (this) {
        Kind.Create -> "ChatRoomCreated"
        Kind.Update -> "Updated"
    }
}
