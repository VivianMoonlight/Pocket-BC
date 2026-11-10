package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.model.BcServerConfig
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Owns Socket.IO lifecycle and raw packet I/O; BC protocol semantics stay in the gateway. */
internal class RazorSocketTransport(
    private val packetLog: PacketLogStore,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String?) -> Unit,
    private val onIncoming: (eventName: String, rawPayload: String) -> Unit,
    private val callbackLock: Any = Any(),
    private val socketFactory: (URI, IO.Options) -> Socket = { uri, options -> IO.socket(uri, options) },
) {
    @Volatile
    private var socket: Socket? = null
    private val lifecycleLock = Any()
    private var connectionGeneration = 0L
    private var connectWaiter: CompletableDeferred<Unit>? = null
    private var terminallyClosed = false

    suspend fun connect(config: BcServerConfig, expectedGeneration: Long? = null): Long {
        val generation = synchronized(callbackLock) {
            if (expectedGeneration != null && captureGeneration() != expectedGeneration) {
                throw CancellationException("BC connection attempt was replaced before it started.")
            }
            close()
            synchronized(lifecycleLock) {
                check(!terminallyClosed) { "BC transport is closed." }
                ++connectionGeneration
            }
        }
        val connected = CompletableDeferred<Unit>()
        val options = IO.Options().apply {
            forceNew = true
            reconnection = false
            timeout = CONNECT_TIMEOUT_MS
            transports = arrayOf("polling", "websocket")
            extraHeaders = mapOf(
                "Origin" to listOf("https://www.bondageprojects.elementfx.com"),
                "Referer" to listOf(config.referer),
            )
        }

        val nextSocket = socketFactory(URI.create(config.serverUrl), options)
        bindSocketCallbacks(nextSocket, generation, connected)

        synchronized(callbackLock) {
            synchronized(lifecycleLock) {
                if (terminallyClosed || connectionGeneration != generation) {
                    nextSocket.off()
                    nextSocket.disconnect()
                    throw CancellationException("BC connection attempt was replaced.")
                }
                socket = nextSocket
                connectWaiter = connected
                nextSocket.connect()
            }
        }
        val didConnect = try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS + 1_000L) { connected.await() } != null
        } catch (throwable: Throwable) {
            close(nextSocket, generation)
            throw throwable
        }
        if (!didConnect) {
            close(nextSocket, generation)
            throw IllegalStateException("Socket.IO connect timed out.")
        }
        if (!isCurrent(nextSocket, generation)) {
            throw CancellationException("BC connection attempt was replaced.")
        }
        return generation
    }

    fun close(terminal: Boolean = false, expectedGeneration: Long? = null): Boolean {
        synchronized(callbackLock) {
            val current = synchronized(lifecycleLock) {
                if (expectedGeneration != null && connectionGeneration != expectedGeneration) return false
                if (terminal) terminallyClosed = true
                connectionGeneration += 1
                socket.also {
                    socket = null
                    connectWaiter?.cancel(CancellationException("BC transport was closed."))
                    connectWaiter = null
                }
            }
            current?.off()
            current?.disconnect()
            return true
        }
    }

    private fun close(candidate: Socket, generation: Long) {
        synchronized(callbackLock) {
            val current = synchronized(lifecycleLock) {
                if (socket !== candidate || connectionGeneration != generation) return
                connectionGeneration += 1
                socket = null
                connectWaiter?.cancel(CancellationException("BC transport was closed."))
                connectWaiter = null
                candidate
            }
            current.off()
            current.disconnect()
        }
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun captureGeneration(): Long = synchronized(lifecycleLock) { connectionGeneration }

    fun captureEmitGuard(): (() -> Unit) -> Unit {
        val generation = captureGeneration()
        return { emitPacket -> emitIfCurrent(generation, emitPacket) }
    }

    fun emitIfCurrent(generation: Long, emitPacket: () -> Unit) = synchronized(lifecycleLock) {
        check(connectionGeneration == generation && !terminallyClosed) {
            "BC connection was replaced before the packet could be sent."
        }
        emitPacket()
    }

    fun emitNow(eventName: String, payloadJson: String) = synchronized(lifecycleLock) {
        val current = socket
        check(current?.connected() == true) { "Socket.IO transport is not connected." }
        packetLog.record(PacketLogDirection.Outbound, eventName, payloadJson)
        current.emit(eventName, deserializeOutgoingPayload(payloadJson))
        Unit
    }

    private fun bindSocketCallbacks(candidate: Socket, generation: Long, connected: CompletableDeferred<Unit>) {
        candidate.on(Socket.EVENT_CONNECT) connectHandler@{ _ ->
            synchronized(callbackLock) {
                if (!isCurrent(candidate, generation)) return@connectHandler
                onConnected()
                connected.complete(Unit)
            }
        }
        candidate.on(Socket.EVENT_DISCONNECT) disconnectHandler@{ args ->
            synchronized(callbackLock) {
                if (!isCurrent(candidate, generation)) return@disconnectHandler
                onDisconnected(args.firstOrNull()?.toString())
            }
        }
        candidate.on(Socket.EVENT_CONNECT_ERROR) connectErrorHandler@{ args ->
            synchronized(callbackLock) {
                if (!isCurrent(candidate, generation)) return@connectErrorHandler
                val message = args.firstOrNull()?.toString() ?: "Socket.IO connect error."
                if (connected.isActive) connected.completeExceptionally(IllegalStateException(message))
            }
        }
        candidate.onAnyIncoming { args ->
            synchronized(callbackLock) {
                if (!isCurrent(candidate, generation) || args.isEmpty()) return@onAnyIncoming
                val eventName = args[0]?.toString() ?: return@onAnyIncoming
                val rawPayload = serializeIncomingPayload(args.getOrNull(1))
                packetLog.record(PacketLogDirection.Inbound, eventName, rawPayload)
                onIncoming(eventName, rawPayload)
            }
        }
    }

    private fun isCurrent(candidate: Socket, generation: Long): Boolean =
        synchronized(lifecycleLock) { socket === candidate && connectionGeneration == generation }

    private fun serializeIncomingPayload(payload: Any?): String =
        when (payload) {
            null -> "null"
            is JSONObject,
            is JSONArray,
            -> payload.toString()
            is String -> JSONObject.quote(payload)
            else -> payload.toString()
        }

    private fun deserializeOutgoingPayload(payloadJson: String): Any =
        runCatching { JSONTokener(payloadJson).nextValue() }.getOrElse { payloadJson }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
