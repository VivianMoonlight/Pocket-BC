package dev.vivian.mobilebc.feature.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class ChatImageHost(val endpoint: String, val fileField: String, val label: Int) {
    Litterbox("https://litterbox.catbox.moe/resources/internals/api.php", "fileToUpload", R.string.chat_image_host_litterbox),
    Catbox("https://catbox.moe/user/api.php", "fileToUpload", R.string.chat_image_host_catbox),
    TmpFiles("https://tmpfiles.org/api/v1/upload", "file", R.string.chat_image_host_tmpfiles),
    ImgBB("https://api.imgbb.com/1/upload", "image", R.string.chat_image_host_imgbb),
    Imgur("https://api.imgur.com/3/image", "image", R.string.chat_image_host_imgur),

    ;
    val needsCredential: Boolean get() = this == ImgBB || this == Imgur
    val maxMegabytes: Int get() = if (this == Imgur) 10 else 20
}

internal const val ChatImageMaxBytes = 20 * 1024 * 1024
internal class ImageTooLargeException : IOException()
internal class InvalidImageException : IOException()

internal fun copyChatImage(input: InputStream, output: OutputStream, maxBytes: Int = ChatImageMaxBytes, checkActive: () -> Unit = {}) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw ImageTooLargeException()
        output.write(buffer, 0, count)
    }
    if (total == 0L) throw InvalidImageException()
}

internal fun hostedImageUrl(host: ChatImageHost, response: String): String {
    val url = response.trim()
    val uri = runCatching { URI(url) }.getOrNull() ?: throw IOException("Invalid image URL")
    val expectedHost = when (host) {
        ChatImageHost.Litterbox -> "litter.catbox.moe"
        ChatImageHost.Catbox -> "files.catbox.moe"
        ChatImageHost.TmpFiles -> "tmpfiles.org"
        ChatImageHost.ImgBB -> "i.ibb.co"
        ChatImageHost.Imgur -> "i.imgur.com"
    }
    if (uri.scheme !in setOf("http", "https") || uri.host != expectedHost || uri.userInfo != null ||
        uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null || uri.path.isNullOrBlank() || uri.path == "/"
    ) throw IOException("Invalid image URL")
    val path = if (host == ChatImageHost.TmpFiles && !uri.rawPath.startsWith("/dl/")) "/dl${uri.rawPath}" else uri.rawPath
    return "https://$expectedHost$path"
}

internal class ChatImageUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun upload(context: Context, uri: Uri, host: ChatImageHost, credential: String = ""): String = withContext(Dispatchers.IO) {
        if (host.needsCredential && credential.isBlank()) throw IOException("Missing credential")
        val file = File.createTempFile("chat-image-", ".upload", context.cacheDir)
        try {
            val coroutineContext = coroutineContext
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> copyChatImage(input, output, host.maxMegabytes * 1024 * 1024) { coroutineContext.ensureActive() } }
            } ?: throw InvalidImageException()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val extension = when (bounds.outMimeType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/bmp", "image/x-ms-bmp" -> "bmp"
                else -> throw InvalidImageException()
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw InvalidImageException()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                if (host == ChatImageHost.Catbox || host == ChatImageHost.Litterbox) addFormDataPart("reqtype", "fileupload")
                if (host == ChatImageHost.Litterbox) addFormDataPart("time", "12h")
                if (host == ChatImageHost.TmpFiles) addFormDataPart("expire", "3600")
                if (host == ChatImageHost.ImgBB) addFormDataPart("key", credential.trim())
                addFormDataPart(host.fileField, "image.$extension", RequestBody.create(MediaType.parse(bounds.outMimeType), file))
            }.build()
            val request = Request.Builder().url(host.endpoint).post(body).apply {
                if (host == ChatImageHost.Imgur) header("Authorization", "Client-ID ${credential.trim()}")
            }.build()
            val response = execute(request)
            parseImageUploadResponse(host, response)
        } finally {
            file.delete()
        }
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code()}")
                        val source = it.body()?.source() ?: throw IOException("Empty response")
                        source.request(4097)
                        if (source.buffer().size() > 4096) throw IOException("Invalid response")
                        source.readUtf8()
                    }
                    continuation.resume(text)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
}

internal fun parseImageUploadResponse(host: ChatImageHost, response: String): String {
    val rawUrl = when (host) {
        ChatImageHost.Litterbox, ChatImageHost.Catbox -> response
        else -> {
            val json = JSONObject(response)
            val success = if (host == ChatImageHost.TmpFiles) json.optString("status") == "success" else json.optBoolean("success")
            if (!success) throw IOException("Upload rejected")
            json.getJSONObject("data").getString(if (host == ChatImageHost.Imgur) "link" else "url")
        }
    }
    return hostedImageUrl(host, rawUrl)
}
