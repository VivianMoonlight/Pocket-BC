package dev.vivian.mobilebc.core.socket

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class PacketLogDirection {
    Outbound,
    Inbound,
}

data class PacketLogEntry(
    val id: Long,
    val direction: PacketLogDirection,
    val eventName: String,
    val payloadJson: String,
    val timestamp: Instant,
)

class PacketLogStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private var nextId = 1L
    private val _entries = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val entries: StateFlow<List<PacketLogEntry>> = _entries.asStateFlow()

    fun record(direction: PacketLogDirection, eventName: String, payloadJson: String) {
        val entry = PacketLogEntry(
            id = nextId(),
            direction = direction,
            eventName = eventName,
            payloadJson = payloadJson.redactSensitiveJson(),
            timestamp = Instant.now(),
        )
        synchronized(lock) {
            _entries.value = (_entries.value + entry).takeLast(maxEntries)
        }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
        }
    }

    fun exportText(): String {
        val snapshot = entries.value
        if (snapshot.isEmpty()) return "No packet logs."
        return buildString {
            appendLine("BC packet log")
            appendLine("entries=${snapshot.size}")
            appendLine()
            snapshot.forEach { entry ->
                appendLine("[${entry.timestamp}] ${entry.direction.label()} ${entry.eventName}")
                appendLine(entry.payloadJson)
                appendLine()
            }
        }.trimEnd()
    }

    private fun nextId(): Long =
        synchronized(lock) { nextId++ }

    private fun PacketLogDirection.label(): String =
        when (this) {
            PacketLogDirection.Outbound -> "SEND"
            PacketLogDirection.Inbound -> "RECV"
        }

    private fun String.redactSensitiveJson(): String =
        runCatching {
            when (val value = JSONTokener(this).nextValue()) {
                is JSONObject -> value.redacted().toString()
                is JSONArray -> value.redacted().toString()
                else -> this
            }
        }.getOrDefault(this)

    private fun JSONObject.redacted(): JSONObject {
        val output = JSONObject()
        keys().forEach { key ->
            val value = get(key)
            output.put(
                key,
                if (key.isSensitiveKey()) {
                    RedactedValue
                } else {
                    value.redactedJsonValue()
                },
            )
        }
        return output
    }

    private fun JSONArray.redacted(): JSONArray {
        val output = JSONArray()
        for (index in 0 until length()) {
            output.put(get(index).redactedJsonValue())
        }
        return output
    }

    private fun Any?.redactedJsonValue(): Any? =
        when (this) {
            is JSONObject -> redacted()
            is JSONArray -> redacted()
            else -> this
        }

    private fun String.isSensitiveKey(): Boolean {
        val normalized = lowercase()
        return normalized.contains("password") || normalized.contains("token")
    }

    private companion object {
        private const val DEFAULT_MAX_ENTRIES = 500
        private const val RedactedValue = "<redacted>"
    }
}
