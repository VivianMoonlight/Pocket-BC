package dev.vivian.mobilebc.resource

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpBcWebResourceFetcher internal constructor(
    private val callFactory: Call.Factory = defaultClient(),
) : BcWebResourceFetcher {
    override suspend fun fetch(url: String): BcFetchedResource {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "mobile-bc-resource-sync/1.0")
            .header("Accept", "*/*")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = callFactory.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { completedResponse ->
                            if (!continuation.isActive) return
                            try {
                                val code = completedResponse.code()
                                // No range was requested: a partial response is not a source file.
                                if (code != 200) {
                                    throw BcWebResourceHttpException(code, url)
                                }
                                val body = completedResponse.body()
                                val fetched = BcFetchedResource(
                                    finalUrl = completedResponse.request().url().toString(),
                                    contentType = body?.contentType()?.toString(),
                                    bytes = body?.bytes() ?: byteArrayOf(),
                                )
                                if (continuation.isActive) continuation.resume(fetched)
                            } catch (error: Throwable) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                },
            )
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15L, TimeUnit.SECONDS)
            .readTimeout(30L, TimeUnit.SECONDS)
            .callTimeout(60L, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
