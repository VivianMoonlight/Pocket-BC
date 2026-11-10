package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.common.ItemBundle
import com.google.gson.Gson
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyEffect
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import dev.vivian.mobilebc.domain.session.core.withNativeEffect
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Owns outbound serialization, rate-limit lanes, and debounced account updates. */
internal class RazorOutboundPipeline(
    private val gson: Gson,
    private val captureEmitGuard: () -> ((() -> Unit) -> Unit) = { { emitPacket -> emitPacket() } },
    private val captureAccountEmitGuard: () -> ((() -> Unit) -> Unit) = { { emitPacket -> emitPacket() } },
    emitNow: (eventName: String, payloadJson: String) -> Unit,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mobile-bc-outbound").apply { isDaemon = true }
    }
    private val rateLimiter = RazorRateLimiter(executor = executor, emit = emitNow)
    private val accountUpdates = RazorAccountUpdateQueue(
        executor = executor,
        send = { fields -> enqueueAccountBatch(RazorAccountUpdateBatch(fields)) },
        sendBatch = ::enqueueAccountBatch,
    )

    suspend fun sendJson(eventName: String, value: Any?, emitGuard: ((() -> Unit) -> Unit)? = null) =
        sendRaw(eventName, gson.toJson(value), emitGuard)

    suspend fun sendRaw(eventName: String, payloadJson: String, emitGuard: ((() -> Unit) -> Unit)? = null) {
        rateLimiter.send(buildPacket(eventName, payloadJson, emitGuard))
    }

    suspend fun <T> sendPreparedJson(
        eventName: String,
        emitGuard: ((() -> Unit) -> Unit)? = null,
        prepare: () -> T,
    ): T {
        var prepared: PreparedValue<T>? = null
        val packet = buildPacket(eventName, "", emitGuard).copy(
            preparePayloadJson = {
                val value = prepare()
                gson.toJson(value).also { prepared = PreparedValue(value) }
            },
        )
        rateLimiter.send(packet)
        return checkNotNull(prepared) { "The outbound guard did not emit the prepared packet." }.value
    }

    /** One Hidden message plus its item update, with one permission fence and chat rate slot. */
    suspend fun sendPreparedItemWithCompanion(
        emitGuard: (() -> Unit) -> Unit,
        prepare: () -> RazorItemUpdateWithCompanion,
    ) {
        val packet = buildPacket("ChatRoomChat", "", emitGuard).copy(prepareEmissions = {
            val update = prepare()
            val item = "ChatRoomCharacterItemUpdate" to gson.toJson(update.item)
            val companion = "ChatRoomChat" to gson.toJson(update.companion)
            if (update.companionFirst) listOf(companion, item) else listOf(item, companion)
        })
        rateLimiter.send(packet)
    }

    fun queueAccountAppearance(appearance: List<ItemBundle>) {
        queueAccountUpdates(mapOf("Appearance" to appearance))
    }

    fun queueAccountUpdates(fields: Map<String, Any?>) {
        val accountGuard = captureAccountEmitGuard()
        accountUpdates.queue(fields, accountGuard)
    }

    fun queueRelationshipLists(update: BcRelationshipListsUpdate) {
        queueAccountUpdates(update.toAccountUpdateFields(gson))
    }

    fun queueRelationshipEconomyEffect(
        effect: BcRelationshipEconomyEffect,
        currentState: BcRelationshipEconomyState,
    ): BcRelationshipEconomyState = currentState.withNativeEffect(effect).also { nextState ->
        queueAccountUpdates(effect.toAccountUpdateFields(nextState))
    }

    suspend fun flushAccountUpdates(batch: OutboundPacket? = drainAccountUpdatePacket()) {
        batch?.let { rateLimiter.send(it) }
    }

    fun drainAccountUpdatePacket(): OutboundPacket? =
        accountUpdates.drainPendingBatch()?.let(::buildAccountPacket)

    private fun enqueueAccountBatch(batch: RazorAccountUpdateBatch) {
        rateLimiter.enqueue(buildAccountPacket(batch))
    }

    private fun buildAccountPacket(batch: RazorAccountUpdateBatch): OutboundPacket {
        val connectionGuard = captureEmitGuard()
        val payloadJson = gson.toJson(batch.fields)
        return buildPacket("AccountUpdate", payloadJson, batch.emitGuard, connectionGuard)
    }

    fun clear(failure: Throwable = IllegalStateException("Socket sender queue was cleared.")) {
        accountUpdates.clear()
        rateLimiter.clear(failure)
    }

    fun close() {
        accountUpdates.clear()
        rateLimiter.close()
        executor.shutdownNow()
    }

    private fun buildPacket(
        eventName: String,
        payloadJson: String,
        emitGuard: ((() -> Unit) -> Unit)? = null,
        connectionGuard: (() -> Unit) -> Unit = captureEmitGuard(),
    ): OutboundPacket {
        val guard = if (emitGuard == null) {
            connectionGuard
        } else {
            { emitPacket: () -> Unit -> emitGuard { connectionGuard(emitPacket) } }
        }
        return OutboundPacket(eventName, payloadJson, eventName.toOutboundLane(), emitGuard = guard)
    }

    private fun String.toOutboundLane(): OutboundLane =
        when (this) {
            "AccountLogin",
            -> OutboundLane.Control

            "ChatRoomJoin",
            "ChatRoomLeave",
            "ChatRoomCharacterItemUpdate",
            -> OutboundLane.RoomControl

            "ChatRoomSearch",
            "AccountQuery",
            "ChatRoomAllowItem",
            -> OutboundLane.InteractiveQuery

            "ChatRoomChat",
            "AccountBeep",
            -> OutboundLane.Chat

            "AccountUpdate" -> OutboundLane.AccountUpdate
            else -> OutboundLane.InteractiveQuery
        }
}

private data class PreparedValue<T>(val value: T)

internal data class RazorAccountUpdateBatch(
    val fields: Map<String, Any?>,
    val emitGuard: ((() -> Unit) -> Unit)? = null,
)

/** Debounces and merges the single AccountUpdate stream owned by [RazorOutboundPipeline]. */
internal class RazorAccountUpdateQueue(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    private val executor: ScheduledExecutorService,
    private val send: (Map<String, Any?>) -> Unit,
    private val sendBatch: ((RazorAccountUpdateBatch) -> Unit)? = null,
) {
    private val pending = LinkedHashMap<String, Any?>()
    // Replacing a field also replaces its origin; untouched fields retain their login fence.
    private val pendingGuards = LinkedHashMap<String, ((() -> Unit) -> Unit)?>()
    private var debounceTimer: ScheduledFuture<*>? = null
    private var batchStartMillis: Long = 0L

    @Synchronized
    fun queue(fields: Map<String, Any?>, emitGuard: ((() -> Unit) -> Unit)? = null) {
        if (fields.isEmpty()) return
        pending.putAll(fields)
        fields.keys.forEach { pendingGuards[it] = emitGuard }

        if (batchStartMillis == 0L) {
            batchStartMillis = System.currentTimeMillis()
        }
        debounceTimer?.cancel(false)
        val elapsed = System.currentTimeMillis() - batchStartMillis
        val delay = if (elapsed >= maxWaitMs) {
            0L
        } else {
            debounceMs.coerceAtMost(maxWaitMs - elapsed)
        }
        debounceTimer = executor.schedule({ flush() }, delay, TimeUnit.MILLISECONDS)
    }

    fun flush() {
        val batch = drainPendingBatch() ?: return
        if (sendBatch != null) sendBatch.invoke(batch) else send(batch.fields)
    }

    fun drainPending(): Map<String, Any?> = drainPendingBatch()?.fields.orEmpty()

    @Synchronized
    fun drainPendingBatch(): RazorAccountUpdateBatch? {
        debounceTimer?.cancel(false)
        debounceTimer = null
        if (pending.isEmpty()) return null
        val fields = LinkedHashMap(pending)
        val guards = pendingGuards.values.filterNotNull().distinct()
        pending.clear()
        pendingGuards.clear()
        batchStartMillis = 0L
        val guard = guards.takeIf { it.isNotEmpty() }?.let {
            { emitPacket: () -> Unit -> emitAccountBatch(guards, 0, emitPacket) }
        }
        return RazorAccountUpdateBatch(fields, guard)
    }

    fun <T> drainPending(transform: (Map<String, Any?>) -> T): T? {
        val fields = drainPending()
        return if (fields.isEmpty()) null else transform(fields)
    }

    @Synchronized
    fun clear() {
        debounceTimer?.cancel(false)
        debounceTimer = null
        pending.clear()
        pendingGuards.clear()
        batchStartMillis = 0L
    }

    private companion object {
        private const val DEFAULT_DEBOUNCE_MS = 2_000L
        private const val DEFAULT_MAX_WAIT_MS = 8_000L
    }
}

private fun emitAccountBatch(
    guards: List<(() -> Unit) -> Unit>,
    index: Int,
    emitPacket: () -> Unit,
) {
    if (index == guards.size) emitPacket() else guards[index] {
        emitAccountBatch(guards, index + 1, emitPacket)
    }
}
