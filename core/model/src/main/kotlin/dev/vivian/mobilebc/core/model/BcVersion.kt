package dev.vivian.mobilebc.core.model

private val BcVersionPattern = Regex(
    pattern = "^R([0-9]+)(?:(ALPHA|BETA)([0-9]*))?$",
    option = RegexOption.IGNORE_CASE,
)

fun String.normalizedBcVersion(): String {
    val candidate = trim()
    require(candidate.isNotBlank()) { "BC version is required." }
    val match = requireNotNull(BcVersionPattern.matchEntire(candidate)) {
        "Invalid BC version '$this'. Expected R<number>, optionally followed by Alpha/Beta and a number."
    }
    val release = match.groupValues[1]
    val channel = match.groupValues[2]
        .lowercase()
        .replaceFirstChar { it.uppercase() }
    val channelNumber = match.groupValues[3]
    return "R$release$channel$channelNumber"
}

fun String.normalizedBcVersionOrNull(): String? =
    runCatching { normalizedBcVersion() }.getOrNull()
