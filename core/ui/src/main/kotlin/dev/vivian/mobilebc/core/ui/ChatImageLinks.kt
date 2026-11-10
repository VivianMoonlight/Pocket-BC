package dev.vivian.mobilebc.core.ui

import java.net.URI

private val webLink = Regex("https?://[^\\s<>\"\\[\\]]+", RegexOption.IGNORE_CASE)
private val imagePath = Regex(".*\\.(png|jpe?g|gif|webp|bmp|avif|svg)$", RegexOption.IGNORE_CASE)

internal fun chatImageLinks(text: String): List<String> = webLink.findAll(text).mapNotNull { match ->
    val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', '}', '，', '。', '）')
    val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
    url.takeIf { uri.host != null && uri.userInfo == null && imagePath.matches(uri.path.orEmpty()) }
}.distinct().toList()
