package dev.vivian.mobilebc.domain.bcitemproperty

/**
 * Lightweight JSON helpers for BC item Property payloads.
 *
 * BC item properties are small object-shaped payloads coming either from parsed static rules or
 * from the room snapshot. This module intentionally keeps the parser narrow and dependency-light:
 * it preserves unknown top-level fields during modification merges, but it does not try to be a
 * general JSON DOM or execute Inventory scripts.
 */
data class BcItemPropertyOverlay(
    val effects: Set<String> = emptySet(),
    val blocks: Set<String> = emptySet(),
    val expose: Set<String> = emptySet(),
    val allowActivity: Set<String> = emptySet(),
    val allowActivityOn: Set<String> = emptySet(),
    val allowActivePose: Set<String> = emptySet(),
    val setPose: Set<String> = emptySet(),
    val attributes: Set<String> = emptySet(),
    val typeRecord: Map<String, Int> = emptyMap(),
    val lockedBy: String? = null,
) {
    companion object {
        fun from(propertyJson: String?): BcItemPropertyOverlay = BcItemPropertyJson.readOverlay(propertyJson)
    }
}

object BcItemPropertyJson {
    fun withDefaults(propertyJson: String?, baselineJson: String?): String = parseObjectEntries(propertyJson).apply {
        parseObjectEntries(baselineJson).forEach { (key, value) -> putIfAbsent(key, value) }
    }.toJsonObject()

    fun withLockEffect(propertyJson: String): String {
        val entries = parseObjectEntries(propertyJson)
        val effect = entries["Effect"]?.trim()
        if (effect?.startsWith('[') == true && effect.endsWith(']')) {
            if ("Lock" in readTokenValues(effect)) return propertyJson
            val body = effect.substring(1, effect.lastIndex).trim()
            entries["Effect"] = "[${body + if (body.isEmpty()) "" else ","}\"Lock\"]"
        } else entries["Effect"] = "[\"Lock\"]"
        return entries.toJsonObject()
    }

    fun overlayStrings(propertyJson: String?, values: Map<String, String>): String =
        parseObjectEntries(propertyJson).apply { values.forEach { (key, value) -> put(key, value.toJsonStringLiteral()) } }.toJsonObject()

    fun withLock(propertyJson: String?, lockName: String, memberNumber: Int, memberName: String, baselineJson: String): String {
        val entries = parseObjectEntries(withoutLock(propertyJson))
        parseObjectEntries(baselineJson).forEach { (key, value) -> entries.putIfAbsent(key, value) }
        entries["Effect"] = (readTokenValues(entries.propertyValue("Effect")) + "Lock").toList().toJsonStringArray()
        entries["LockedBy"] = lockName.toJsonStringLiteral()
        entries["LockMemberNumber"] = memberNumber.toString()
        entries["LockMemberName"] = memberName.toJsonStringLiteral()
        if (lockName == "HighSecurityPadlock") entries["MemberNumberListKeys"] = memberNumber.toString().toJsonStringLiteral()
        return entries.toJsonObject()
    }

    /** Native wardrobe copying removes one key without normalizing nested TypeRecord data. */
    fun withoutProperty(propertyJson: String?, key: String): String? {
        val entries = parseObjectEntries(propertyJson)
        entries.remove(key)
        return entries.takeUnless { it.isEmpty() }?.toJsonObject()
    }

    /** Wardrobe loading copies whole property values, including unknown nested metadata. */
    fun overlayRawProperties(currentPropertyJson: String?, overlayPropertyJson: String?): String? {
        val entries = parseObjectEntries(currentPropertyJson)
        entries.putAll(parseObjectEntries(overlayPropertyJson))
        return entries.takeUnless { it.isEmpty() }?.toJsonObject()
    }

    internal fun readPropertyValue(propertyJson: String?, key: String): String? =
        parseObjectEntries(propertyJson).propertyValue(key)

    internal fun readExactPropertyValue(propertyJson: String?, key: String): String? =
        parseObjectEntries(propertyJson)[key]

    internal fun readStringValue(rawValue: String?): String? {
        val value = rawValue?.trim() ?: return null
        val quote = value.firstOrNull()?.takeIf { it == '"' || it == '\'' } ?: return null
        if (value.length < 2 || value.last() != quote || value.findStringEnd(0) != value.lastIndex) return null
        return value.substring(1, value.lastIndex).unescapeJsonString()
    }

    internal fun readObjectArray(rawValue: String?): List<String> {
        val value = rawValue?.trim() ?: return emptyList()
        if (!value.startsWith("[") || !value.endsWith("]")) return emptyList()
        val body = value.substring(1, value.lastIndex)
        val objects = mutableListOf<String>()
        var index = 0
        while (index < body.length) {
            val start = body.indexOfNextNonSpace(index) ?: break
            if (body[start] == ',') { index = start + 1; continue }
            val end = body.findEndOfJsonValue(start)
            val entry = body.substring(start, end).trim()
            if (entry.startsWith("{") && entry.endsWith("}")) objects += entry
            index = end + 1
        }
        return objects
    }

    internal fun readTokenValues(rawValue: String?): Set<String> {
        val value = rawValue?.trim() ?: return emptySet()
        if (!value.startsWith("[")) return setOfNotNull(value.readTokenValue())
        if (!value.endsWith("]")) return emptySet()
        val body = value.substring(1, value.lastIndex)
        val tokens = linkedSetOf<String>()
        var index = 0
        while (index < body.length) {
            val start = body.indexOfNextNonSpace(index) ?: break
            if (body[start] == ',') {
                index = start + 1
                continue
            }
            val end = body.findEndOfJsonValue(start)
            body.substring(start, end).readTokenValue()?.let(tokens::add)
            index = end + 1
        }
        return tokens
    }

    private fun String.readTokenValue(): String? =
        readStringValue(this)?.takeIf { it.isNotBlank() }
            ?: trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }

    fun readOverlay(propertyJson: String?): BcItemPropertyOverlay {
        val entries = parseObjectEntries(propertyJson)
        fun tokens(key: String) = readTokenValues(entries.propertyValue(key))
        return BcItemPropertyOverlay(
            effects = tokens("Effect"),
            blocks = tokens("Block"),
            expose = tokens("Expose"),
            allowActivity = tokens("AllowActivity"),
            allowActivityOn = tokens("AllowActivityOn"),
            allowActivePose = tokens("AllowActivePose"),
            setPose = tokens("SetPose"),
            attributes = tokens("Attribute") + tokens("Attributes"),
            typeRecord = readTypeRecord(entries),
            lockedBy = readStringValue(entries.propertyValue("LockedBy"))?.takeIf { it.isNotBlank() },
        )
    }

    fun readTypeRecord(propertyJson: String?): Map<String, Int> = readTypeRecord(parseObjectEntries(propertyJson))

    private fun readTypeRecord(entries: Map<String, String>): Map<String, Int> {
        val typeRecord = entries.propertyValue("TypeRecord") ?: return emptyMap()
        return parseObjectEntries(typeRecord).mapNotNull { (key, rawValue) ->
            rawValue.trim().trimEnd(',').toIntOrNull()?.let { key to it }
        }.toMap()
    }

    fun mergeForModification(
        currentPropertyJson: String?,
        optionPropertyJson: String,
        managedPropertyKeys: Set<String> = emptySet(),
    ): String {
        val currentEntries = parseObjectEntries(currentPropertyJson).toMutableLinkedMap()
        val optionEntries = parseObjectEntries(optionPropertyJson)

        val currentTypeRecord = readTypeRecord(currentEntries).mapValues { it.value.toString() }.toMutableLinkedMap()
        currentTypeRecord.putAll(readTypeRecord(optionEntries).mapValues { it.value.toString() })
        currentEntries.keys.removeAll { key ->
            managedPropertyKeys.any { it.equals(key, ignoreCase = true) } &&
                LOCK_PROPERTY_KEYS.none { it.equals(key, ignoreCase = true) }
        }

        optionEntries.forEach { (key, value) ->
            if (!key.equals("TypeRecord", ignoreCase = true)) {
                currentEntries[key] = value
            }
        }
        if (currentTypeRecord.isNotEmpty()) {
            currentEntries.keys.removeAll { it.equals("TypeRecord", ignoreCase = true) }
            currentEntries["TypeRecord"] = currentTypeRecord.toJsonObject()
        }
        if (readStringValue(currentEntries.propertyValue("LockedBy")) != null) {
            currentEntries["Effect"] = (readTokenValues(currentEntries.propertyValue("Effect")) + "Lock").toList().toJsonStringArray()
        }

        return currentEntries.toJsonObject()
    }

    internal fun propertyKeys(propertyJson: String?): Set<String> = parseObjectEntries(propertyJson).keys

    /** Static subset of BC's ModularItemMergeModuleValues / ModularItemSanitizeProperties. */
    internal fun composeModularProperty(
        typeRecord: Map<String, Int>,
        assetPropertyJson: String?,
        propertyJsons: List<String>,
    ): String? {
        val entries = linkedMapOf<String, String>()
        entries["TypeRecord"] = typeRecord.mapValues { it.value.toString() }.toJsonObject()
        entries["Difficulty"] = "0"
        val assetEntries = parseObjectEntries(assetPropertyJson)
        MODULAR_BASE_ARRAY_KEYS.forEach { key ->
            val value = assetEntries[key]
            entries[key] = if (value?.startsWith("[") == true && value.endsWith("]")) {
                readTokenValues(value).toList().toJsonStringArray()
            } else {
                "[]"
            }
        }
        readStringValue(assetEntries["CustomBlindBackground"])?.let {
            entries["CustomBlindBackground"] = it.toJsonStringLiteral()
        }

        for (propertyJson in propertyJsons) {
            for ((key, value) in parseObjectEntries(propertyJson)) {
                when {
                    key == "TypeRecord" -> Unit
                    key in MODULAR_ARRAY_KEYS -> {
                        if (!value.startsWith("[")) return null
                        entries[key] = (readTokenValues(entries[key]) + readTokenValues(value)).toList().toJsonStringArray()
                    }
                    key == "Difficulty" || key == "HeightModifier" -> {
                        val amount = value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                        val total = (entries[key]?.toDoubleOrNull() ?: 0.0) + amount
                        entries[key] = if (total % 1.0 == 0.0) total.toLong().toString() else total.toString()
                    }
                    key == "CustomBlindBackground" -> {
                        if (value == "null") entries[key] = "null"
                        else if (readStringValue(value) == null) return null
                        else if (key !in entries) entries[key] = value
                    }
                    key in setOf("Text", "Text2", "Text3") -> {
                        val text = readStringValue(value)
                        if (text != null && validBcItemText(text, Int.MAX_VALUE)) entries[key] = value
                    }
                    key in MODULAR_NUMBER_KEYS -> {
                        if (value.toDoubleOrNull()?.isFinite() != true) return null
                        entries[key] = value
                    }
                    key in MODULAR_BOOLEAN_KEYS -> {
                        if (value != "true" && value != "false") return null
                        entries[key] = value
                    }
                    else -> return null
                }
            }
        }
        return entries.toJsonObject()
    }

    /**
     * Mirrors BC's ValidationDeleteLock for the property subset carried by mobile snapshots.
     * Unknown non-lock fields are preserved so relationship cleanup cannot erase item settings.
     */
    fun withoutLock(propertyJson: String?): String? {
        val entries = parseObjectEntries(propertyJson).toMutableLinkedMap()
        if (entries.isEmpty()) return propertyJson

        var changed = false
        LOCK_PROPERTY_KEYS.forEach { key ->
            if (entries[key]?.trim()?.let { it != "null" } == true) {
                entries.remove(key)
                changed = true
            }
        }

        val effect = entries["Effect"]?.trim()
        if (effect?.startsWith('[') == true && effect.endsWith(']')) {
            val body = effect.substring(1, effect.lastIndex)
            val effects = mutableListOf<String>()
            var index = 0
            while (index < body.length) {
                val start = body.indexOfNextNonSpace(index) ?: break
                if (body[start] == ',') { index = start + 1; continue }
                val end = body.findEndOfJsonValue(start)
                effects += body.substring(start, end).trim()
                index = end + 1
            }
            if (effects.any { readStringValue(it) == "Lock" }) {
                entries["Effect"] = effects.filterNot { readStringValue(it) == "Lock" }.joinToString(",", "[", "]")
                changed = true
            }
        }

        return if (changed) entries.toJsonObject() else propertyJson
    }

    private fun parseObjectEntries(rawJson: String?): LinkedHashMap<String, String> {
        val source = rawJson?.trim()?.takeIf { it.startsWith("{") } ?: return linkedMapOf()
        val bodyStart = source.indexOf('{')
        val bodyEnd = source.findMatching(bodyStart, '{', '}') ?: return linkedMapOf()
        val body = source.substring(bodyStart + 1, bodyEnd)
        val entries = linkedMapOf<String, String>()
        var index = 0
        while (index < body.length) {
            val keyStart = body.indexOfNextNonSpace(index) ?: break
            if (body[keyStart] == ',') {
                index = keyStart + 1
                continue
            }
            val keyRead = body.readJsonObjectKey(keyStart) ?: break
            val (key, afterKey) = keyRead
            val colon = body.indexOfNextNonSpace(afterKey)?.takeIf { body[it] == ':' } ?: break
            val valueStart = body.indexOfNextNonSpace(colon + 1) ?: break
            val valueEnd = body.findEndOfJsonValue(valueStart)
            entries[key] = body.substring(valueStart, valueEnd).trim()
            index = valueEnd + 1
        }
        return entries
    }

    private fun <K, V> Map<K, V>.toMutableLinkedMap(): LinkedHashMap<K, V> = LinkedHashMap(this)

    private fun Map<String, String>.propertyValue(key: String): String? =
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun Map<String, String>.toJsonObject(): String = entries.joinToString(
        separator = ",",
        prefix = "{",
        postfix = "}",
    ) { (key, value) -> "${key.toJsonStringLiteral()}:$value" }

    private fun String.readJsonObjectKey(start: Int): Pair<String, Int>? {
        val first = getOrNull(start) ?: return null
        if (first == '"' || first == '\'') {
            val end = findStringEnd(start) ?: return null
            return substring(start + 1, end).unescapeJsonString() to (end + 1)
        }
        var end = start
        while (end < length && (this[end].isLetterOrDigit() || this[end] == '_' || this[end] == '-')) end++
        if (end == start) return null
        return substring(start, end) to end
    }

    private fun String.findEndOfJsonValue(start: Int): Int {
        if (start >= length) return start
        return when (this[start]) {
            '{' -> (findMatching(start, '{', '}') ?: lastIndex) + 1
            '[' -> (findMatching(start, '[', ']') ?: lastIndex) + 1
            '"', '\'' -> (findStringEnd(start) ?: lastIndex) + 1
            else -> {
                var index = start
                while (index < length && this[index] != ',') index++
                index
            }
        }
    }

    private fun String.findMatching(start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var index = start
        var stringQuote: Char? = null
        var escaped = false
        while (index < length) {
            val char = this[index]
            if (stringQuote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == stringQuote) {
                    stringQuote = null
                }
                index++
                continue
            }
            when (char) {
                '"', '\'' -> stringQuote = char
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun String.findStringEnd(start: Int): Int? {
        val quote = getOrNull(start) ?: return null
        var index = start + 1
        var escaped = false
        while (index < length) {
            val char = this[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == quote) {
                return index
            }
            index++
        }
        return null
    }

    private fun String.indexOfNextNonSpace(start: Int): Int? {
        var index = start
        while (index < length) {
            if (!this[index].isWhitespace()) return index
            index++
        }
        return null
    }

    private fun String.toJsonStringLiteral(): String = buildString {
        append('"')
        this@toJsonStringLiteral.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
            }
        }
        append('"')
    }

    private fun String.readJsonStringArray(): List<String> {
        val source = trim()
        if (!source.startsWith("[") || !source.endsWith("]")) return emptyList()
        return Regex("""["']((?:\\.|[^"'\\])*)["']""")
            .findAll(source)
            .map { it.groupValues[1].unescapeJsonString() }
            .toList()
    }

    private fun List<String>.toJsonStringArray(): String =
        joinToString(separator = ",", prefix = "[", postfix = "]") { it.toJsonStringLiteral() }

    private fun String.unescapeJsonString(): String = buildString {
        var index = 0
        while (index < this@unescapeJsonString.length) {
            val char = this@unescapeJsonString[index]
            if (char == '\\' && index + 1 < this@unescapeJsonString.length) {
                val next = this@unescapeJsonString[index + 1]
                if (next == 'u' && index + 6 <= this@unescapeJsonString.length) {
                    val code = this@unescapeJsonString.substring(index + 2, index + 6).toIntOrNull(16)
                    if (code != null) {
                        append(code.toChar())
                        index += 6
                        continue
                    }
                }
                append(
                    when (next) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        else -> next
                    },
                )
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }

    private val LOCK_PROPERTY_KEYS = setOf(
        "LockedBy",
        "LockMemberNumber",
        "LockMemberName",
        "LockMessage",
        "EnableRandomInput",
        "RemoveItem",
        "ShowTimer",
        "CombinationNumber",
        "Password",
        "Hint",
        "LockSet",
        "LockPickSeed",
        "MemberNumberList",
        "RemoveTimer",
        "MemberNumberListKeys",
    )

    private val MODULAR_BASE_ARRAY_KEYS = setOf("Block", "Effect", "Hide", "HideItem", "AllowActivity", "Attribute")
    private val MODULAR_ARRAY_KEYS = MODULAR_BASE_ARRAY_KEYS + setOf("SetPose", "AllowActivePose")
    private val MODULAR_NUMBER_KEYS = setOf(
        "OverridePriority", "ShockLevel", "TriggerCount", "OrgasmCount", "RuinedOrgasmCount", "TimeWorn",
        "TimeSinceLastOrgasm", "InflateLevel", "Intensity", "Opacity", "AutoPunishUndoTimeSetting", "OriginalSetting",
        "AutoPunishUndoTime", "AutoPunish", "NextShockTime", "TargetAngle",
    )
    private val MODULAR_BOOLEAN_KEYS = setOf("Door", "Padding", "ShowText", "BlinkState", "PunishOrgasm", "PunishStandup")
}

fun String?.readBcBooleanProperty(key: String): Boolean? {
    val value = BcItemPropertyJson.readPropertyValue(this, key) ?: return null
    return when {
        value.equals("true", ignoreCase = true) -> true
        value.equals("false", ignoreCase = true) -> false
        else -> null
    }
}

fun String?.readBcStringProperty(key: String): String? =
    BcItemPropertyJson.readStringValue(BcItemPropertyJson.readPropertyValue(this, key))
        ?.takeIf { it.isNotBlank() }

fun String?.readBcIntProperty(key: String): Int? =
    BcItemPropertyJson.readPropertyValue(this, key)?.toIntOrNull()

fun String?.readBcPropertyTokens(key: String): Set<String> =
    BcItemPropertyJson.readTokenValues(BcItemPropertyJson.readPropertyValue(this, key))

fun String?.containsBcTokenOrFalse(token: String): Boolean =
    this?.contains(token, ignoreCase = true) == true

fun String.sameBcToken(other: String): Boolean = equals(other, ignoreCase = true)

fun String.containsBcToken(other: String): Boolean = contains(other, ignoreCase = true)
