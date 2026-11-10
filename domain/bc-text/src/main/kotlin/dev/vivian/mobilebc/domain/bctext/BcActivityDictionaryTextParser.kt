package dev.vivian.mobilebc.domain.bctext

import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

class BcActivityDictionaryTextParser(
    resourceReader: (version: String, relativePath: String) -> String?,
    language: String = "CN",
    languageProvider: (() -> String)? = null,
    dictionaryValueResolver: BcDictionaryValueResolver = BcDictionaryValueResolver.None,
) : BcActivityTextParser by BcCsvBackedTextParser(
    resourceReader = resourceReader,
    csvPath = BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv,
    languageProvider = { languageProvider?.invoke() ?: language },
    dictionaryValueResolver = dictionaryValueResolver,
)

class BcInterfaceTextParser(
    resourceReader: (version: String, relativePath: String) -> String?,
    language: String = "CN",
    languageProvider: (() -> String)? = null,
    dictionaryValueResolver: BcDictionaryValueResolver = BcDictionaryValueResolver.None,
) : BcActivityTextParser by BcCsvBackedTextParser(
    resourceReader = resourceReader,
    csvPath = BcResourcePaths.Screens.InterfaceCsv,
    languageProvider = { languageProvider?.invoke() ?: language },
    dictionaryValueResolver = dictionaryValueResolver,
)

class BcAssetStringsTextParser(
    resourceReader: (version: String, relativePath: String) -> String?,
    language: String = "CN",
    languageProvider: (() -> String)? = null,
    dictionaryValueResolver: BcDictionaryValueResolver = BcDictionaryValueResolver.None,
) : BcActivityTextParser by BcCsvBackedTextParser(
    resourceReader = resourceReader,
    csvPath = BcResourcePaths.Assets.Female3DCG.AssetStringsCsv,
    languageProvider = { languageProvider?.invoke() ?: language },
    dictionaryValueResolver = dictionaryValueResolver,
)

private class BcCsvBackedTextParser(
    private val resourceReader: (version: String, relativePath: String) -> String?,
    private val csvPath: String,
    private val languageProvider: () -> String,
    private val dictionaryValueResolver: BcDictionaryValueResolver,
) : BcActivityTextParser {
    private val dictionaries = ConcurrentHashMap<String, Map<String, String>>()

    override fun parse(request: BcTextParseRequest): BcParsedText? {
        val version = request.version
            .takeIf(String::isNotBlank)
            ?.normalizedBcVersionOrNull()
            ?: return null
        val language = languageProvider().trim().uppercase().ifBlank { "EN" }
        val localizedDictionary = dictionary(version, language)
        val template = localizedDictionary[request.key] ?: return null
        val text = substitute(
            version = version,
            template = template,
            dictionaryJson = request.dictionaryJson,
            memberLookup = request.memberLookup,
            textLookup = localizedDictionary::get,
        )
        return BcParsedText(key = request.key, text = text)
    }

    private fun dictionary(version: String, language: String): Map<String, String> {
        val cacheKey = "$version:$language"
        dictionaries[cacheKey]?.let { return it }

        val csv = resourceReader(version, csvPath) ?: return emptyMap()
        val base = parseCsvDictionary(csv)
        val translationText = if (language == "EN") {
            null
        } else {
            resourceReader(
                version,
                BcResourcePaths.translatedTextPath(csvPath, language),
            )
        }
        val translations = translationText?.let { parseBcTranslationPairs(it, language) }.orEmpty()
        val parsed = base.mapValues { (key, englishTemplate) ->
            translations[key] ?: translations[englishTemplate.trim()] ?: englishTemplate
        }
        // Missing files are transient while a version snapshot is being synchronized. Do not
        // permanently retain either an empty base dictionary or an English localization fallback.
        if (language == "EN" || translationText != null) dictionaries[cacheKey] = parsed
        return parsed
    }

    private fun substitute(
        version: String,
        template: String,
        dictionaryJson: String?,
        memberLookup: Map<Int, String>,
        textLookup: (String) -> String?,
    ): String {
        if (dictionaryJson.isNullOrBlank()) return template
        return runCatching {
            substituteBcDictionaryText(
                version = version,
                template = template,
                dictionary = JSONArray(dictionaryJson),
                memberLookup = memberLookup,
                valueResolver = dictionaryValueResolver,
                textLookup = textLookup,
            )
        }.getOrElse { template }
    }

    private fun parseCsvDictionary(csv: String): Map<String, String> =
        csv.lineSequence()
            .mapNotNull(::parseTwoColumnCsvLine)
            .toMap()

    private fun parseTwoColumnCsvLine(line: String): Pair<String, String>? {
        val columns = parseBcCsvRows(line).firstOrNull() ?: return null
        val key = columns.getOrNull(0)?.trim().orEmpty()
        val value = columns.getOrNull(1)?.trim().orEmpty()
        return if (key.isBlank() || value.isBlank()) null else key to value
    }

}
