package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.auth.LoginResult
import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.room.RoomJoinResult
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcAuthenticatedPrivateChatState
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.core.protocol.BcWardrobeCodec
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import dev.vivian.mobilebc.domain.session.core.BcRelationshipGatewayUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcRoomJoinRejectedException
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.withGatewayUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Owns Razor request/response correlation, timeout semantics, and disconnect completion. */
internal class RazorRequestCoordinator(
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val onAuthenticated: (memberNumber: Int) -> Unit = {},
    private val onRelationshipEconomyLoaded: (BcRelationshipEconomyState) -> Unit = {},
) {
    private val privateChatLock = Any()
    private data class AuthenticatedState(
        val privateChat: BcAuthenticatedPrivateChatState,
        val relationships: BcRelationshipSnapshot,
        val wardrobe: BcWardrobeState,
    )

    @Volatile
    private var authenticatedState: AuthenticatedState? = null

    private val privateChatState get() = authenticatedState?.privateChat
    private val relationshipState get() = authenticatedState?.relationships
    private val wardrobeState get() = authenticatedState?.wardrobe

    val authenticatedPrivateChatState: BcAuthenticatedPrivateChatState?
        get() = privateChatState

    val authenticatedRelationships: BcRelationshipSnapshot?
        get() = relationshipState

    val authenticatedWardrobeState: BcWardrobeState?
        get() = wardrobeState

    fun <T> withAuthenticatedWardrobeState(
        expectedToken: String,
        block: (BcWardrobeState, BcPrivateChatState) -> T,
    ): T? = synchronized(privateChatLock) {
        val account = privateChatState ?: return@synchronized null
        val wardrobe = wardrobeState ?: return@synchronized null
        if (account.selfRuleLogSessionId != expectedToken || wardrobe.selfRuleLogSessionId != expectedToken ||
            account.memberNumber != wardrobe.memberNumber
        ) return@synchronized null
        block(wardrobe, account.state)
    }

    fun compareAndSetWardrobeState(expected: BcWardrobeState, updated: BcWardrobeState): BcWardrobeState? =
        synchronized(privateChatLock) {
            val account = privateChatState ?: return@synchronized null
            if (wardrobeState !== expected || expected.revision == Long.MAX_VALUE ||
                account.memberNumber != expected.memberNumber || account.selfRuleLogSessionId != expected.selfRuleLogSessionId
            ) return@synchronized null
            updated.copy(
                memberNumber = expected.memberNumber,
                selfRuleLogSessionId = expected.selfRuleLogSessionId,
                revision = expected.revision + 1L,
            ).also { authenticatedState = requireNotNull(authenticatedState).copy(wardrobe = it) }
        }

    fun applyRelationshipUpdate(
        expectedToken: String,
        update: BcRelationshipGatewayUpdate,
        receivedAtMillis: Long,
    ): BcRelationshipSnapshot? = synchronized(privateChatLock) {
        val privateState = privateChatState ?: return@synchronized null
        val current = relationshipState ?: return@synchronized null
        if (privateState.selfRuleLogSessionId != expectedToken ||
            current.loginSessionId != expectedToken ||
            current.accountMemberNumber != privateState.memberNumber ||
            current.revision == Long.MAX_VALUE
        ) return@synchronized null
        current.withGatewayUpdate(update, receivedAtMillis)
            .copy(revision = current.revision + 1L)
            .also { authenticatedState = requireNotNull(authenticatedState).copy(relationships = it) }
    }

    fun updateRelationshipLists(
        expectedToken: String,
        update: BcRelationshipListsUpdate,
    ): BcRelationshipSnapshot? = synchronized(privateChatLock) {
        val privateState = privateChatState ?: return@synchronized null
        val current = relationshipState ?: return@synchronized null
        if (privateState.selfRuleLogSessionId != expectedToken ||
            current.loginSessionId != expectedToken ||
            current.accountMemberNumber != privateState.memberNumber ||
            current.revision == Long.MAX_VALUE
        ) return@synchronized null
        current.copy(
            self = current.self.copy(
                friends = update.friends.toSet(),
                friendNames = update.friendNames.toMap(),
                ignored = update.ignored.toSet(),
                whitelist = update.whitelist.toSet(),
                blacklist = update.blacklist.toSet(),
                submissives = update.submissives.toSet(),
            ),
            revision = current.revision + 1L,
        ).also { authenticatedState = requireNotNull(authenticatedState).copy(relationships = it) }
    }

    fun <T> withAuthenticatedPrivateChatState(
        expectedToken: String,
        block: (BcAuthenticatedPrivateChatState) -> T,
    ): T? = synchronized(privateChatLock) {
        val current = privateChatState ?: return@synchronized null
        if (current.selfRuleLogSessionId != expectedToken) return@synchronized null
        block(current)
    }

    fun compareAndSetPrivateChatState(
        expected: BcAuthenticatedPrivateChatState,
        updated: BcPrivateChatState,
    ): BcAuthenticatedPrivateChatState? = synchronized(privateChatLock) {
        if (privateChatState !== expected || expected.state.revision == Long.MAX_VALUE) return@synchronized null
        expected.copy(state = updated.copy(revision = expected.state.revision + 1L)).also { authenticatedState = requireNotNull(authenticatedState).copy(privateChat = it) }
    }

    val authenticatedSelfRuleLogSessionId: String?
        get() = synchronized(privateChatLock) { privateChatState?.selfRuleLogSessionId }

    private val login = PendingRequest<BcSessionLoginResult>("A login request is already pending.")
    private val roomSearch = PendingRequest<List<BcRoomSummary>>("A room search request is already pending.")
    private val onlineFriends = PendingRequest<List<BcConversation>>("An online friends query is already pending.")
    private val onlineFriendsMutex = Mutex()
    private val roomJoin = PendingRequest<RoomJoinResult>("A room join request is already pending.")
    private val allowItem = KeyedPendingRequests<Int, Boolean> { memberNumber ->
        "An allow-item query for member $memberNumber is already pending."
    }
    private val ownershipCapability = KeyedPendingRequests<Int, String?> { memberNumber ->
        "An ownership capability query for member $memberNumber is already pending."
    }
    private val lovershipCapability = KeyedPendingRequests<Int, String?> { memberNumber ->
        "A lovership capability query for member $memberNumber is already pending."
    }

    suspend fun awaitLogin(send: suspend () -> Unit): BcSessionLoginResult {
        val result = login.awaitResponse(
            onTimeout = { BcSessionLoginResult.Failure("Login timed out.") },
            send = {
                synchronized(privateChatLock) {
                    authenticatedState = null
                }
                send()
            },
        )
        if (result !is BcSessionLoginResult.Success) return result
        return synchronized(privateChatLock) {
            val current = relationshipState ?: return@synchronized result
            if (current.loginSessionId != result.selfRuleLogSessionId ||
                current.accountMemberNumber != result.memberNumber
            ) return@synchronized result
            result.copy(
                relationships = current.self,
                relationshipCapabilities = current.capabilitiesByMember,
                relationshipRevision = current.revision,
                relationshipOwnershipClearRevision = current.ownershipClearRevision,
                wardrobeState = wardrobeState?.takeIf {
                    it.memberNumber == result.memberNumber && it.selfRuleLogSessionId == result.selfRuleLogSessionId
                } ?: result.wardrobeState,
            )
        }
    }

    suspend fun awaitOnlineFriends(send: suspend () -> Unit): List<BcConversation> =
        onlineFriendsMutex.withLock {
            onlineFriends.awaitResponse(
                onTimeout = { error("Timed out while querying online friends.") },
                send = send,
            )
        }

    suspend fun awaitRoomSearch(send: suspend () -> Unit): List<BcRoomSummary> =
        roomSearch.awaitResponse(onTimeout = ::emptyList, send = send)

    suspend fun awaitAllowItem(memberNumber: Int, send: suspend () -> Unit): Boolean =
        allowItem.awaitResponse(
            key = memberNumber,
            onTimeout = { false },
            send = send,
        )

    // Native queries emit available options only, not negative acknowledgements.
    suspend fun awaitOwnershipCapability(memberNumber: Int, send: suspend () -> Unit): String? =
        ownershipCapability.awaitResponse(
            key = memberNumber,
            onTimeout = { null },
            timeoutMs = minOf(requestTimeoutMs, CAPABILITY_OBSERVATION_MS),
            send = send,
        )

    suspend fun awaitLovershipCapability(memberNumber: Int, send: suspend () -> Unit): String? =
        lovershipCapability.awaitResponse(
            key = memberNumber,
            onTimeout = { null },
            timeoutMs = minOf(requestTimeoutMs, CAPABILITY_OBSERVATION_MS),
            send = send,
        )

    suspend fun awaitRoomJoin(
        onRejected: (RoomJoinResult.Error) -> Unit = {},
        send: suspend () -> Unit,
    ) {
        when (
            val result = roomJoin.awaitResponse(
                onTimeout = { error("Room join timed out.") },
                onResponse = { result -> if (result is RoomJoinResult.Error) onRejected(result) },
                send = send,
            )
        ) {
            RoomJoinResult.Success -> Unit
            is RoomJoinResult.Error -> throw BcRoomJoinRejectedException(result.message)
        }
    }

    fun handle(event: RazorWireEvent): Boolean =
        when (event) {
            is RazorWireEvent.LoginResponse -> {
                completeLogin(event.value)
                true
            }
            is RazorWireEvent.AccountQueryResult -> {
                if (event.value.query == ONLINE_FRIENDS_QUERY) {
                    onlineFriends.complete(event.value.toOnlineFriendConversations())
                }
                true
            }
            is RazorWireEvent.ChatRoomSearchResult -> {
                roomSearch.complete(event.value.map { it.toBcRoomSummary() })
                true
            }
            is RazorWireEvent.ChatRoomSearchResponse -> {
                roomJoin.complete(event.value)
                true
            }
            is RazorWireEvent.AllowItem -> {
                allowItem.complete(event.memberNumber, event.allowItem)
                true
            }
            is RazorWireEvent.AccountOwnership -> {
                val memberNumber = event.value.memberNumber
                val result = event.value.result
                if (memberNumber != null && result != null) {
                    ownershipCapability.complete(memberNumber, result)
                }
                false
            }
            is RazorWireEvent.AccountLovership -> {
                val memberNumber = event.value.memberNumber
                val result = event.value.result
                if (memberNumber != null && result != null) {
                    lovershipCapability.complete(memberNumber, result)
                }
                false
            }
            else -> false
        }

    fun disconnect(message: String) {
        synchronized(privateChatLock) {
            authenticatedState = null
        }
        login.complete(BcSessionLoginResult.Failure(message))
        roomSearch.complete(emptyList())
        onlineFriends.completeExceptionally(IllegalStateException(message))
        roomJoin.completeExceptionally(IllegalStateException(message))
        allowItem.completeAll(false)
        val failure = IllegalStateException(message)
        ownershipCapability.completeAllExceptionally(failure)
        lovershipCapability.completeAllExceptionally(failure)
    }

    private fun completeLogin(result: LoginResult) {
        login.completeWith {
            when (result) {
                is LoginResult.Success -> {
                    val account = result.data
                    val relationships = account.toBcSelfRelations()
                    val selfRuleLogSessionId = UUID.randomUUID().toString()
                    val privateChat = account.toBcPrivateChatState()
                    val wardrobe = BcWardrobeCodec.decodeState(
                        memberNumber = account.memberNumber,
                        selfRuleLogSessionId = selfRuleLogSessionId,
                        revision = 0L,
                        rawWardrobeJson = account.wardrobe?.toString(),
                        rawNamesJson = account.wardrobeCharacterNames?.toString(),
                        fallbackName = account.name,
                        privateInputs = BcWardrobeCodec.decodePrivateInputs(
                            gameJson = account.game?.toString(),
                            inventoryJson = account.inventory?.toString(),
                            inventoryData = account.inventoryData,
                            privateCharacterJson = account.privateCharacter?.toString(),
                            owner = account.owner,
                        ),
                    )
                    synchronized(privateChatLock) {
                        authenticatedState = AuthenticatedState(
                            privateChat = BcAuthenticatedPrivateChatState(account.memberNumber, selfRuleLogSessionId, privateChat),
                            relationships = BcRelationshipSnapshot().nextLoaded(
                                memberNumber = account.memberNumber,
                                relations = relationships,
                                loginSessionId = selfRuleLogSessionId,
                            ),
                            wardrobe = wardrobe,
                        )
                    }
                    onAuthenticated(account.memberNumber)
                    onRelationshipEconomyLoaded(
                        BcRelationshipEconomyState(
                            money = relationships.money,
                            reputation = relationships.reputation,
                        )
                    )
                    BcSessionLoginResult.Success(
                        memberNumber = account.memberNumber,
                        name = account.nickname?.takeIf { it.isNotBlank() } ?: account.name,
                        relationships = relationships,
                        selfRuleLog = privateChat.ruleLog,
                        selfRuleLogSessionId = selfRuleLogSessionId,
                        privateChatState = privateChat,
                        wardrobeState = wardrobe,
                    )
                }
                is LoginResult.Error -> BcSessionLoginResult.Failure(result.message)
            }
        }
    }

    private suspend fun <T> PendingRequest<T>.awaitResponse(
        onTimeout: () -> T,
        onResponse: (T) -> Unit = {},
        send: suspend () -> Unit,
    ): T {
        val pending = begin(onResponse)
        return try {
            send()
            withTimeoutOrNull(requestTimeoutMs) { pending.await() } ?: onTimeout()
        } finally {
            clear(pending)
        }
    }

    private suspend fun <K, T> KeyedPendingRequests<K, T>.awaitResponse(
        key: K,
        onTimeout: () -> T,
        timeoutMs: Long = requestTimeoutMs,
        send: suspend () -> Unit,
    ): T {
        val pending = begin(key)
        return try {
            send()
            withTimeoutOrNull(timeoutMs) { pending.await() } ?: onTimeout()
        } finally {
            clear(key, pending)
        }
    }

    private companion object {
        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
        private const val CAPABILITY_OBSERVATION_MS = 3_000L
        private const val ONLINE_FRIENDS_QUERY = "OnlineFriends"
    }
}

internal fun ServerAccountData.toBcSelfRuleLog(): List<BcOwnerRuleLogRecord>? {
    return log?.toBcRuleLog()
}

internal fun com.google.gson.JsonElement.toBcRuleLog(): List<BcOwnerRuleLogRecord>? {
    val entries = takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val records = linkedMapOf<Pair<String, String>, BcOwnerRuleLogRecord>()
    for (element in entries) {
        val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val name = entry.get("Name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: return null
        val group = entry.get("Group")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: return null
        val value = entry.get("Value")?.takeUnless { it.isJsonNull }
        val expiresAtMillis = if (value == null) {
            null
        } else {
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
            runCatching { value.asBigDecimal.longValueExact() }.getOrNull() ?: return null
        }
        // BC LogLoad renews duplicate Name/Group entries with the last value.
        records[group to name] = BcOwnerRuleLogRecord(name, group, expiresAtMillis)
    }
    return records.values.toList()
}

/** Thread-safe ownership for one in-flight request/response pair. */
internal class PendingRequest<T>(
    private val duplicateMessage: String,
) {
    private val lock = Any()
    private data class Request<T>(val pending: CompletableDeferred<T>, val onResponse: (T) -> Unit)
    private var current: Request<T>? = null

    fun begin(onResponse: (T) -> Unit = {}): CompletableDeferred<T> = synchronized(lock) {
        check(current == null) { duplicateMessage }
        CompletableDeferred<T>().also { current = Request(it, onResponse) }
    }

    fun complete(value: T) {
        completeWith { value }
    }

    fun completeWith(value: () -> T) {
        val request = take() ?: return
        try {
            val response = value()
            request.onResponse(response)
            request.pending.complete(response)
        } catch (failure: Throwable) {
            request.pending.completeExceptionally(failure)
            throw failure
        }
    }

    fun completeExceptionally(throwable: Throwable) {
        take()?.pending?.completeExceptionally(throwable)
    }

    fun clear(expected: CompletableDeferred<T>) {
        synchronized(lock) {
            if (current?.pending === expected) current = null
        }
    }

    private fun take(): Request<T>? = synchronized(lock) {
        current.also { current = null }
    }
}

/** Thread-safe request registry for responses correlated by a protocol key. */
internal class KeyedPendingRequests<K, T>(
    private val duplicateMessage: (K) -> String,
) {
    private val lock = Any()
    private val current = mutableMapOf<K, CompletableDeferred<T>>()

    fun begin(key: K): CompletableDeferred<T> = synchronized(lock) {
        check(key !in current) { duplicateMessage(key) }
        CompletableDeferred<T>().also { current[key] = it }
    }

    fun complete(key: K, value: T) {
        take(key)?.complete(value)
    }

    fun completeAll(value: T) {
        val requests = synchronized(lock) {
            current.values.toList().also { current.clear() }
        }
        requests.forEach { it.complete(value) }
    }

    fun completeAllExceptionally(throwable: Throwable) {
        val requests = synchronized(lock) {
            current.values.toList().also { current.clear() }
        }
        requests.forEach { it.completeExceptionally(throwable) }
    }

    fun clear(key: K, expected: CompletableDeferred<T>) {
        synchronized(lock) {
            if (current[key] === expected) current.remove(key)
        }
    }

    private fun take(key: K): CompletableDeferred<T>? = synchronized(lock) {
        current.remove(key)
    }
}
