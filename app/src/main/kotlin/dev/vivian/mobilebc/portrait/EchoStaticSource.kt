package dev.vivian.mobilebc.portrait

import org.json.JSONArray
import org.json.JSONObject

/** Recognizes a small declaration grammar; never evaluates JavaScript or loads imports. */
internal class EchoStaticSource(private val source: String) {
    private data class Token(val text: String, val string: Boolean = false)
    private val tokens = lex()
    private var position = 0
    private var remainingWork = 1_000_000
    private val constants = mutableMapOf<String, Any>(
        "PoseType" to JSONObject().put("DEFAULT", "").put("HIDE", "Hide"),
        "ExtendedArchetype" to JSONObject().put("TYPED", "typed").put("MODULAR", "modular").put("TEXT", "text").put("NOARCH", "noarch"),
    )
    private val imported = mutableSetOf<String>()
    private val assets = JSONArray()
    private val aliases = JSONArray()
    private val names = JSONArray()

    fun extract(): JSONObject {
        while (peek("import")) {
            expect("import"); expect("{")
            val symbols = mutableListOf<String>()
            while (!take("}")) {
                symbols += identifier()
                if (!peek("}")) expect(",")
            }
            expect("from")
            val module = next().also { require(it.string) }.text
            val allowed = when (module) {
                "@local/AssetManager" -> setOf("AssetManager")
                "@local/lib/fixups" -> setOf("groupFixup", "luziSuffixFixups", "luziPrefixFixups", "customFixup")
                else -> error("Unsupported import: $module")
            }
            require(symbols.all { it in allowed })
            imported.addAll(symbols)
            expect(";")
        }
        while (take("const")) {
            val name = identifier()
            require(name !in constants && name !in imported)
            expect("="); constants[name] = expression().also(::checkSize); expect(";")
        }
        expect("export"); expect("default"); expect("function"); expect("("); expect(")"); expect("{")
        while (!take("}")) {
            val function = identifier()
            require(function in imported) { "Unsupported registration: $function" }
            val method = if (function == "AssetManager") {
                expect(".")
                identifier().also { require(it in setOf("addAsset", "addAssetWithConfig", "addGroupedAssets", "addGroupedAssetsWithConfig")) }
            } else null
            expect("(")
            val args = mutableListOf<Any>()
            while (!take(")")) {
                if (((method == "addAsset" && args.size in 2..4) ||
                    (method in setOf("addGroupedAssets", "addGroupedAssetsWithConfig") && args.size in 1..2)) && take("undefined")) args += JSONObject.NULL
                else if (take("...")) args.addAll(arrayValues(expression() as JSONArray)) else args += expression()
                if (!peek(")")) expect(",")
            }
            expect(";")
            when (method) {
                "addAsset" -> registerLegacy(args)
                "addAssetWithConfig" -> register(args)
                "addGroupedAssets", "addGroupedAssetsWithConfig" -> registerGrouped(args, method == "addGroupedAssetsWithConfig")
                else -> fixup(function, args)
            }
        }
        take(";")
        require(position == tokens.size && assets.length() > 0) { "Unsupported source body" }
        return JSONObject().put("groups", JSONArray()).put("mirrors", JSONArray()).put("assets", assets)
            .put("aliases", aliases).put("imageMappings", JSONObject())
            .put("names", JSONObject().put("groups", JSONArray()).put("assets", names)).also(::checkSize)
    }

    private fun registerGrouped(args: List<Any>, withConfig: Boolean) {
        require(args.size in 1..3 && args[0] is JSONObject)
        require(args.drop(1).all { it === JSONObject.NULL || it is JSONObject })
        val grouped = args[0] as JSONObject
        val translations = args.getOrNull(1) as? JSONObject ?: JSONObject()
        val extra = args.getOrNull(2) as? JSONObject ?: JSONObject()
        for (group in grouped.keys()) for (definition in arrayValues(grouped.getJSONArray(group))) {
            definition as JSONObject
            val name = definition.getString("Name")
            fun select(entries: JSONObject) = JSONObject().apply {
                for (language in entries.keys()) {
                    val values = entries.getJSONObject(language)
                    if (values.has(group)) {
                        val groupValues = values.getJSONObject(group)
                        if (groupValues.has(name)) put(language, groupValues.get(name))
                    }
                }
            }
            val config = JSONObject().put("translation", select(translations))
            if (withConfig) config.put("layerNames", select(extra))
            else if (extra.has(group) && extra.getJSONObject(group).has(name)) {
                config.put("extended", extra.getJSONObject(group).getJSONObject(name))
            }
            register(listOf(group, definition, config))
        }
    }

    private fun registerLegacy(args: List<Any>) {
        require(args.size in 2..5 && args[0] is String && args[1] is JSONObject)
        val config = JSONObject()
        for ((index, key) in listOf(2 to "extended", 3 to "translation", 4 to "noMirror")) {
            args.getOrNull(index)?.takeUnless { it === JSONObject.NULL }?.let { config.put(key, it) }
        }
        register(listOf(args[0], args[1], config))
    }

    private fun register(args: List<Any>) {
        if (args.size == 1 && args[0] is JSONArray) {
            arrayValues(args[0] as JSONArray).forEach { register(arrayValues(it as JSONArray)) }
            return
        }
        require(args.size in 2..3)
        require(args.size == 2 || args[2] is JSONObject)
        val groups = if (args[0] is String) listOf(args[0] as String) else arrayValues(args[0] as JSONArray).map { it as String }
        val definition = args[1]
        if (definition is JSONArray) {
            arrayValues(definition).forEach { entry -> register(listOf(args[0]) + arrayValues(entry as JSONArray)) }
            return
        }
        definition as JSONObject
        val config = args.getOrNull(2) as? JSONObject ?: JSONObject()
        require(config.keys().asSequence().all { it in setOf("extended", "translation", "layerNames", "assetStrings", "noMirror") })
        config.keys().forEach {
            require(if (it == "noMirror") config.get(it) is Boolean else config.get(it) is JSONObject) { "Invalid static configuration: $it" }
        }
        val name = definition.getString("Name")
        config.optJSONObject("extended")?.let { extended ->
            requireEchoExtension(extended, "Unsupported extension")
        }
        require(!definition.optBoolean("Extended") || config.has("extended"))
        val targetGroups = groups.flatMap { group ->
            if (!config.optBoolean("noMirror") && group in setOf("ItemTorso", "ItemTorso2")) listOf("ItemTorso", "ItemTorso2") else listOf(group)
        }.distinct()
        for (group in targetGroups) {
            require(assets.length() < 2048 && group.isNotBlank() && name.isNotBlank())
            assets.put(JSONObject().put("group", group).put("definition", definition).apply {
                if (config.optBoolean("noMirror")) put("noMirror", true)
                config.optJSONObject("extended")?.let { put("extended", it) }
            })
            names.put(JSONObject().put("group", group).put("name", name).apply {
                config.optJSONObject("translation")?.let { put("translation", it) }
            })
        }
    }

    private fun fixup(function: String, args: List<Any>) {
        if (function == "customFixup") {
            require(args.size == 1)
            val rule = args.single() as JSONObject
            require(rule.keys().asSequence().toSet() == setOf("Old", "New"))
            val old = rule.getJSONObject("Old")
            val replacement = rule.getJSONObject("New")
            for (item in listOf(old, replacement)) {
                require(item.keys().asSequence().toSet() == setOf("Group", "Name"))
                item.getString("Group")
                item.getString("Name")
            }
            require(old.getString("Group") == replacement.getString("Group"))
            require(aliases.length() < 8192)
            aliases.put(JSONObject().put("group", old.getString("Group"))
                .put("oldName", old.getString("Name")).put("name", replacement.getString("Name")))
            return
        }
        require(args.size in 2..3)
        val groups = if (args[0] is String) listOf(args[0] as String) else arrayValues(args[0] as JSONArray).map { it as String }
        val value = args[1] as String
        val old = when (function) {
            "luziSuffixFixups" -> { require(args.size == 2); "${value}_Luzi" }
            "luziPrefixFixups" -> args.getOrNull(2) as? String ?: "Luzi_$value"
            "groupFixup" -> { require(args.size == 3); value }
            else -> error("Unsupported fixup")
        }
        val name = if (function == "groupFixup") args[2] as String else value
        groups.flatMap { if (it == "ItemTorso") listOf("ItemTorso", "ItemTorso2") else listOf(it) }.distinct().forEach {
            require(aliases.length() < 8192)
            aliases.put(JSONObject().put("group", it).put("oldName", old).put("name", name))
        }
    }

    private fun expression(depth: Int = 0): Any {
        require(depth < 64)
        var value = atom(depth + 1)
        while (peek("+") || peek("-")) {
            val op = next().text
            val right = atom(depth + 1) as Number
            val number = (value as Number).toDouble() + (if (op == "+") 1 else -1) * right.toDouble()
            require(number.isFinite())
            value = number
        }
        return value
    }

    private fun atom(depth: Int): Any {
        require(depth < 64)
        if (take("-")) return -(atom(depth + 1) as Number).toDouble()
        if (take("[")) {
            val result = JSONArray()
            while (!take("]")) {
                if (take("...")) arrayValues(expression(depth + 1) as JSONArray).forEach(result::put)
                else result.put(expression(depth + 1))
                require(result.length() <= 8192)
                if (!peek("]")) expect(",")
            }
            return result
        }
        if (take("{")) {
            val result = JSONObject()
            while (!take("}")) {
                if (take("...")) {
                    val other = expression(depth + 1) as JSONObject
                    other.keys().forEach { require(--remainingWork > 0); result.put(it, other.get(it)) }
                } else {
                    val key = next()
                    require(key.string || key.text.matches(Identifier))
                    require(isEchoDataKey(key.text))
                    result.put(key.text, if (take(":")) expression(depth + 1) else checkNotNull(constants[key.text]))
                }
                require(result.length() <= 8192)
                if (!peek("}")) expect(",")
            }
            return result
        }
        val token = next()
        var value: Any = when {
            token.string -> token.text
            token.text == "true" -> true
            token.text == "false" -> false
            token.text == "null" -> JSONObject.NULL
            token.text.firstOrNull()?.isDigit() == true -> token.text.toDouble().also { require(it.isFinite()) }
            else -> checkNotNull(constants[token.text]) { "Unsupported expression: ${token.text}" }
        }
        while (take(".")) {
            val key = identifier()
            require(key !in EchoPrototypeKeys)
            value = (value as JSONObject).get(key)
        }
        return value
    }

    private fun identifier(): String = next().also { require(!it.string && it.text.matches(Identifier)) }.text
    private fun peek(value: String) = tokens.getOrNull(position)?.let { !it.string && it.text == value } == true
    private fun take(value: String): Boolean = peek(value).also { if (it) position++ }
    private fun expect(value: String) { require(take(value)) { "Expected $value at token $position" } }
    private fun next(): Token = tokens.getOrNull(position++) ?: error("Unexpected end of source")

    private fun checkSize(value: Any) {
        var nodes = 0
        fun visit(node: Any, depth: Int) {
            require(depth <= 64 && ++nodes <= 100_000 && --remainingWork > 0) { "Static data expansion limit exceeded" }
            when (node) {
                is JSONObject -> node.keys().forEach { visit(node.get(it), depth + 1) }
                is JSONArray -> for (i in 0 until node.length()) visit(node.get(i), depth + 1)
            }
        }
        visit(value, 0)
    }

    private fun lex(): List<Token> {
        require(source.length <= 512 * 1024)
        val result = mutableListOf<Token>()
        var i = 0
        while (i < source.length) {
            require(result.size < 100_000)
            val char = source[i]
            when {
                char.isWhitespace() -> i++
                source.startsWith("//", i) -> { i = source.indexOf('\n', i).let { if (it < 0) source.length else it + 1 } }
                source.startsWith("/*", i) -> { val end = source.indexOf("*/", i + 2); require(end >= 0); i = end + 2 }
                char == '\'' || char == '"' -> {
                    val quote = char
                    i++
                    val value = StringBuilder()
                    while (i < source.length && source[i] != quote) {
                        val c = source[i++]
                        require(c != '\n' && c != '\r')
                        if (c != '\\') value.append(c) else {
                            require(i < source.length)
                            value.append(when (val escaped = source[i++]) {
                                'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'
                                '\\', '\'', '"', '/' -> escaped
                                'u' -> { require(i + 4 <= source.length); source.substring(i, i + 4).toInt(16).toChar().also { i += 4 } }
                                else -> error("Unsupported string escape")
                            })
                        }
                    }
                    require(i < source.length); i++
                    result += Token(value.toString(), string = true)
                }
                char.isLetter() || char == '_' || char == '$' -> {
                    val start = i++
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] in "_$")) i++
                    result += Token(source.substring(start, i))
                }
                char.isDigit() -> {
                    val start = i++
                    while (i < source.length && (source[i].isDigit() || source[i] == '.')) i++
                    result += Token(source.substring(start, i))
                }
                source.startsWith("...", i) -> { result += Token("..."); i += 3 }
                char in "{}[](),;:.=+-" -> { result += Token(char.toString()); i++ }
                else -> error("Unsupported source token: $char")
            }
        }
        return result
    }

    private companion object {
        val Identifier = Regex("[\\p{L}_$][\\p{L}\\p{N}_$]*")
        fun arrayValues(array: JSONArray): List<Any> = (0 until array.length()).map(array::get)
    }
}
