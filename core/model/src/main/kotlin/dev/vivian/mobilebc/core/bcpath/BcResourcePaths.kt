package dev.vivian.mobilebc.core.bcpath

import dev.vivian.mobilebc.core.model.normalizedBcVersion

object BcResourcePaths {
    /** Known layouts within the requested version; never substitute another release. */
    fun remotePathCandidates(version: String, localPath: String): List<String> {
        val primary = remotePath(version, localPath)
        val flattened = flattenedAssetPath(localPath)
        return listOf(primary, localPath, flattened).distinct()
    }

    // Keep local cache keys stable for existing snapshots and domain readers.
    fun remotePath(version: String, localPath: String): String {
        val release = version.normalizedBcVersion().drop(1).takeWhile(Char::isDigit).toInt()
        if (release < 133) return localPath
        return flattenedAssetPath(localPath)
    }

    private fun flattenedAssetPath(localPath: String): String {
        val assetFile = localPath.removePrefix("Assets/Female3DCG/")
        return if (localPath.startsWith("Assets/Female3DCG/") &&
            (localPath in registeredCsvResources || localPath in registeredStandaloneResources ||
                Regex("(?:Female3DCG|AssetStrings)_[A-Z]+\\.txt").matches(assetFile))
        ) "Assets/$assetFile" else localPath
    }

    object Backgrounds {
        const val BackgroundsJs = "Backgrounds/Backgrounds.js"
    }

    object Assets {
        object Female3DCG {
            const val Female3DCGCsv = "Assets/Female3DCG/Female3DCG.csv"
            const val Female3DCGCn = "Assets/Female3DCG/Female3DCG_CN.txt"
            const val Female3DCGJs = "Assets/Female3DCG/Female3DCG.js"
            const val Female3DCGExtendedJs = "Assets/Female3DCG/Female3DCGExtended.js"
            const val AssetStringsCsv = "Assets/Female3DCG/AssetStrings.csv"
            const val AssetStringsCn = "Assets/Female3DCG/AssetStrings_CN.txt"
        }
    }

    object Screens {
        const val InterfaceCsv = "Screens/Interface.csv"
        const val InterfaceCn = "Screens/Interface_CN.txt"

        object Character {
            object Preference {
                const val ActivityDictionaryCsv = "Screens/Character/Preference/ActivityDictionary.csv"
                const val ActivityDictionaryCn = "Screens/Character/Preference/ActivityDictionary_CN.txt"
            }
        }
    }

    fun translatedTextPath(csvPath: String, language: String): String =
        csvPath.removeSuffix(".csv").removeSuffix(".CSV") + "_${language.uppercase()}.txt"

    val registeredCsvResources: List<String> = listOf(
        Assets.Female3DCG.Female3DCGCsv,
        Assets.Female3DCG.AssetStringsCsv,
        Screens.InterfaceCsv,
        Screens.Character.Preference.ActivityDictionaryCsv,
    )

    /** Non-CSV resources that are needed by domain parsers. */
    val registeredStandaloneResources: List<String> = listOf(
        Assets.Female3DCG.Female3DCGJs,
        Assets.Female3DCG.Female3DCGExtendedJs,
        Backgrounds.BackgroundsJs,
    )
}
