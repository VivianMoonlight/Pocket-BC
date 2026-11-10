package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.model.normalizedBcVersion

/** Parses BC's background constants and literal tables without evaluating JavaScript. */
class BcRoomBackgroundCatalogParser {
    fun parse(version: String, source: String): BcRoomBackgroundCatalog {
        val reader = LiteralReader(source)
        val constants = linkedMapOf<String, String>()
        var defaultTags: List<Value>? = null
        var backgrounds: List<Value>? = null
        while (reader.peek() != null) {
            val declaration = reader.next()
            if (declaration?.kind != Kind.Identifier || declaration.text != "const") continue
            val name = reader.next()?.takeIf { it.kind == Kind.Identifier }?.text ?: continue
            val relevant = name == "BackgroundsList" || name.startsWith("BackgroundsTag")
            if (!relevant) continue
            reader.expect("=")
            val value = reader.value()
            reader.expect(";")
            when (name) {
                "BackgroundsTagList" -> {
                    require(defaultTags == null) { "Duplicate background tag list" }
                    defaultTags = (value as? ArrayValue)?.values
                        ?: error("BackgroundsTagList must be a literal array")
                }
                "BackgroundsList" -> {
                    require(backgrounds == null) { "Duplicate background list" }
                    backgrounds = (value as? ArrayValue)?.values
                        ?: error("BackgroundsList must be a literal array")
                }
                else -> {
                    val text = (value as? StringValue)?.text
                        ?: error("Background tag $name must be a string literal")
                    require(constants.put(name, text) == null) { "Duplicate background tag $name" }
                }
            }
            if (defaultTags != null && backgrounds != null) break
        }

        fun resolve(value: Value): String = when (value) {
            is StringValue -> value.text
            is Reference -> constants[value.name] ?: error("Unresolved background tag ${value.name}")
            else -> error("Background tag must be a string or constant")
        }
        val generalTags = requireNotNull(defaultTags) { "BackgroundsTagList is missing" }
            .map(::resolve).toSet()
        require(generalTags.isNotEmpty()) { "BackgroundsTagList is empty" }
        val asylumTag = requireNotNull(constants["BackgroundsTagAsylum"]) { "Asylum background tag is missing" }
        val entries = requireNotNull(backgrounds) { "BackgroundsList is missing" }.map { entry ->
            val fields = (entry as? ObjectValue)?.fields ?: error("Background entry must be an object")
            val name = (fields["Name"] as? StringValue)?.text
                ?.takeIf(String::isNotBlank) ?: error("Background name must be a nonempty string literal")
            val tags = (fields["Tag"] as? ArrayValue)?.values
                ?: error("Background $name is missing its tag array")
            name to tags.map(::resolve).toSet()
        }
        require(entries.isNotEmpty()) { "BackgroundsList is empty" }
        require(entries.map { it.first }.toSet().size == entries.size) { "Duplicate background name" }
        val general = entries.filter { (_, tags) -> tags.any(generalTags::contains) }.map { it.first }
        val asylum = entries.filter { (_, tags) -> asylumTag in tags }.map { it.first }
        require(general.isNotEmpty() && asylum.isNotEmpty()) { "Selectable background list is empty" }
        return BcRoomBackgroundCatalog(
            version = version.normalizedBcVersion(),
            allNames = entries.map { it.first },
            generalNames = general,
            asylumNames = asylum,
        )
    }

    private sealed interface Value
    private data class StringValue(val text: String) : Value
    private data class Reference(val name: String) : Value
    private data class ArrayValue(val values: List<Value>) : Value
    private data class ObjectValue(val fields: Map<String, Value>) : Value
    private enum class Kind { Identifier, String, Symbol }
    private data class Token(val kind: Kind, val text: String)

    private class LiteralReader(private val source: String) {
        private var offset = 0
        private var pending: Token? = null

        fun peek(): Token? = pending ?: readToken()?.also { pending = it }
        fun next(): Token? = peek().also { pending = null }
        fun expect(text: String) {
            require(next()?.text == text) { "Expected '$text' in background source" }
        }

        fun value(depth: Int = 0): Value {
            require(depth <= 4) { "Unsupported nesting in background source" }
            val token = next() ?: error("Truncated background source")
            if (token.kind == Kind.String) return StringValue(token.text)
            if (token.kind == Kind.Identifier) return Reference(token.text)
            return when (token.text) {
                "[" -> {
                    val values = mutableListOf<Value>()
                    while (peek()?.text != "]") {
                        values += value(depth + 1)
                        if (peek()?.text == "]") break
                        expect(",")
                    }
                    expect("]")
                    ArrayValue(values)
                }
                "{" -> {
                    val fields = linkedMapOf<String, Value>()
                    while (peek()?.text != "}") {
                        val key = next()?.takeIf { it.kind == Kind.Identifier || it.kind == Kind.String }
                            ?.text ?: error("Invalid background field")
                        expect(":")
                        require(fields.put(key, value(depth + 1)) == null) { "Duplicate background field $key" }
                        if (peek()?.text == "}") break
                        expect(",")
                    }
                    expect("}")
                    ObjectValue(fields)
                }
                else -> error("Unsupported expression in background source")
            }
        }

        private fun readToken(): Token? {
            while (offset < source.length) {
                when {
                    source[offset].isWhitespace() || source[offset] == '\uFEFF' -> offset++
                    source.startsWith("//", offset) -> {
                        offset = source.indexOf('\n', offset + 2).takeIf { it >= 0 } ?: source.length
                    }
                    source.startsWith("/*", offset) -> {
                        val end = source.indexOf("*/", offset + 2)
                        require(end >= 0) { "Unclosed comment in background source" }
                        offset = end + 2
                    }
                    else -> break
                }
            }
            if (offset >= source.length) return null
            val character = source[offset++]
            if (character == '\'' || character == '"') {
                return Token(Kind.String, readString(character))
            }
            if (character.isLetter() || character == '_' || character == '$') {
                val start = offset - 1
                while (offset < source.length && (
                    source[offset].isLetterOrDigit() || source[offset] == '_' || source[offset] == '$'
                )) offset++
                return Token(Kind.Identifier, source.substring(start, offset))
            }
            return Token(Kind.Symbol, character.toString())
        }

        private fun readString(quote: Char): String = buildString {
            while (offset < source.length) {
                val character = source[offset++]
                if (character == quote) return@buildString
                require(character != '\n' && character != '\r') { "Unclosed background string" }
                if (character != '\\') {
                    append(character)
                    continue
                }
                require(offset < source.length) { "Truncated background string escape" }
                when (val escaped = source[offset++]) {
                    '\'', '"', '\\' -> append(escaped)
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    'v' -> append('\u000B')
                    'u' -> append(hexCharacter(4))
                    'x' -> append(hexCharacter(2))
                    '\n' -> Unit
                    '\r' -> if (offset < source.length && source[offset] == '\n') offset++
                    else -> error("Unsupported background string escape")
                }
            }
            error("Unclosed background string")
        }

        private fun hexCharacter(length: Int): Char {
            require(offset + length <= source.length) { "Truncated background string escape" }
            val code = source.substring(offset, offset + length).toIntOrNull(16)
                ?: error("Invalid background string escape")
            offset += length
            return code.toChar()
        }
    }
}
