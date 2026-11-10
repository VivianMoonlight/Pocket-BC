package dev.vivian.mobilebc.resource

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpBcWebResourceFetcherTest {
    @Test
    fun unsolicitedPartialOrEmptySuccessResponseIsNotAcceptedAsACompleteSource() = runBlocking {
        for (code in listOf(206, 204)) {
            val factory = FakeCallFactory { call, callback ->
                callback.onResponse(call, response(call.request(), code, "partial source"))
            }
            val failure = runCatching {
                OkHttpBcWebResourceFetcher(factory).fetch("https://example.invalid/source.js")
            }.exceptionOrNull()
            assertTrue(failure is BcWebResourceHttpException)
            assertEquals(code, (failure as BcWebResourceHttpException).statusCode)
        }
    }

    @Test
    fun successfulResponsePreservesHeadersMetadataAndBytes() = runBlocking {
        val factory = FakeCallFactory { call, callback ->
            callback.onResponse(
                call,
                response(
                    request = call.request(),
                    code = 200,
                    body = "payload",
                ),
            )
        }
        val fetcher = OkHttpBcWebResourceFetcher(factory)

        val fetched = fetcher.fetch("https://example.invalid/resource.csv")

        val request = factory.lastCall!!.request()
        assertEquals("mobile-bc-resource-sync/1.0", request.header("User-Agent"))
        assertEquals("*/*", request.header("Accept"))
        assertEquals("https://example.invalid/resource.csv", fetched.finalUrl)
        assertEquals("text/plain; charset=utf-8", fetched.contentType)
        assertEquals("payload", fetched.bytes.decodeToString())
    }

    @Test
    fun nonSuccessfulResponseKeepsTheHttpFailureContract() = runBlocking {
        val factory = FakeCallFactory { call, callback ->
            callback.onResponse(call, response(call.request(), code = 503, body = "unavailable"))
        }
        val fetcher = OkHttpBcWebResourceFetcher(factory)

        val failure = runCatching {
            fetcher.fetch("https://example.invalid/resource.csv")
        }.exceptionOrNull()

        assertTrue(failure is BcWebResourceHttpException)
        assertEquals(503, (failure as BcWebResourceHttpException).statusCode)
    }

    @Test
    fun cancellingAnEnqueuedRequestCancelsItsCallAndCompletesPromptly() = runBlocking {
        val enqueued = CountDownLatch(1)
        val factory = FakeCallFactory { _, _ -> enqueued.countDown() }
        val fetcher = OkHttpBcWebResourceFetcher(factory)

        val fetchJob = launch(start = CoroutineStart.UNDISPATCHED) {
            fetcher.fetch("https://example.invalid/blocked.csv")
        }
        assertTrue(enqueued.await(1, TimeUnit.SECONDS))

        fetchJob.cancel()
        withTimeout(1_000L) { fetchJob.join() }

        assertTrue(factory.lastCall!!.isCanceled())
    }

    @Test
    fun cancellationAtEnqueuePreventsTheRequestFromStarting() = runBlocking {
        lateinit var fetchJob: Job
        val startAttempts = AtomicInteger()
        val factory = FakeCallFactory { call, _ ->
            fetchJob.cancel()
            if (!call.isCanceled()) startAttempts.incrementAndGet()
        }
        val fetcher = OkHttpBcWebResourceFetcher(factory)
        fetchJob = launch(start = CoroutineStart.LAZY) {
            fetcher.fetch("https://example.invalid/cancel-before-start.csv")
        }

        fetchJob.start()
        withTimeout(1_000L) { fetchJob.join() }

        assertTrue(factory.lastCall!!.isCanceled())
        assertEquals(0, startAttempts.get())
    }

    private class FakeCallFactory(
        private val onEnqueue: (FakeCall, Callback) -> Unit,
    ) : Call.Factory {
        var lastCall: FakeCall? = null
            private set

        override fun newCall(request: Request): Call =
            FakeCall(request, onEnqueue).also { lastCall = it }
    }

    private class FakeCall(
        private val request: Request,
        private val onEnqueue: (FakeCall, Callback) -> Unit,
    ) : Call {
        private val executed = AtomicBoolean()
        private val canceled = AtomicBoolean()

        override fun request(): Request = request

        override fun execute(): Response = throw UnsupportedOperationException("Synchronous execution is unused.")

        override fun enqueue(responseCallback: Callback) {
            check(executed.compareAndSet(false, true)) { "Call already executed." }
            onEnqueue(this, responseCallback)
        }

        override fun cancel() {
            canceled.set(true)
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun isCanceled(): Boolean = canceled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(request, onEnqueue)
    }

    private companion object {
        fun response(
            request: Request,
            code: Int,
            body: String,
        ): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(ResponseBody.create(MediaType.parse("text/plain; charset=utf-8"), body))
            .build()
    }
}
