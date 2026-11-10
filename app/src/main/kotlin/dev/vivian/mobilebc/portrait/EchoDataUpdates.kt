package dev.vivian.mobilebc.portrait

import android.content.Context
import android.util.Log
import dev.vivian.mobilebc.R
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** One immutable snapshot per process, shared by names and portraits. Updates apply next launch. */
internal class EchoDataUpdates(context: Context) {
    private val assets = context.applicationContext.assets
    private val directory = File(context.filesDir, "echo-data")
    private val supplement = assets.open("portrait/supplement/manifest.json").bufferedReader().use { it.readText() }
    private val source = context.getString(R.string.echo_data_update_url)
    private val bundledText by lazy { assets.open("portrait/echo/pack.json").bufferedReader().use { it.readText() } }
    private val store by lazy { EchoPackStore(directory, supplement) }
    val snapshot: EchoDataPack by lazy {
        val bundled = EchoDataPack.decode(bundledText, supplement)
        val selected = store.current(bundled)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { refresh(selected) }.onFailure { Log.w("EchoData", "Keeping current Echo data; update rejected", it) }
        }
        selected
    }

    private fun refresh(selected: EchoDataPack) {
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        fun download(url: String, limit: Int): ByteArray {
            requireEchoDownloadUrl(url)
            return client.newCall(Request.Builder().url(url).header("User-Agent", "mobile-bc-echo-data")
                .header("Accept", "application/vnd.github+json").build()).execute().use { response ->
                check(response.isSuccessful) { "Echo HTTP ${response.code()}" }
                val input = checkNotNull(response.body()).byteStream()
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "Echo response too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        if (source.isNotBlank()) updateEchoPack(source, selected, store, ::download)
        else {
            val index = assets.open("portrait/echo/source-index.json").bufferedReader().use { it.readText() }
            updateEchoFromGithub(selected, store, bundledText, index, ::download)?.let {
                Log.i("EchoData", "Echo update staged for next launch: $it")
            }
        }
    }
}

internal fun requireEchoDownloadUrl(url: String) {
    val uri = URI(url)
    if (uri.rawQuery == "recursive=1" && uri.host == "api.github.com" &&
        uri.path.matches(Regex("/repos/$EchoGithubRepository/git/trees/[a-f0-9]{40}"))) {
        requireUpdateUrl(url.substringBefore('?'))
    } else requireUpdateUrl(url)
}

internal fun updateEchoPack(source: String, selected: EchoDataPack, store: EchoPackStore, download: (String, Int) -> ByteArray) {
    requireUpdateUrl(source)
    val indexBytes = download(source, 64 * 1024)
    require(indexBytes.size <= 64 * 1024)
    val index = JSONObject(indexBytes.toString(Charsets.UTF_8))
    require(index.getInt("format") == 1)
    val sequence = index.getLong("sequence")
    if (sequence <= selected.sequence) return
    val url = index.getString("url")
    requireUpdateUrl(url)
    require(URI(url).host == URI(source).host) { "Echo pack must use the configured publisher host" }
    val bytes = download(url, EchoDataPack.MaxBytes)
    require(bytes.size <= EchoDataPack.MaxBytes)
    require(portraitSha256(bytes) == index.getString("sha256")) { "Echo package hash mismatch" }
    store.install(bytes.toString(Charsets.UTF_8), sequence, selected)
}

internal fun requireUpdateUrl(url: String) {
    val uri = URI(url)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.port == -1 && uri.rawFragment == null && uri.rawQuery == null) { "Expected an HTTPS data manifest URL" }
}
