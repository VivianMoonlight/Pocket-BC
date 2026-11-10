package dev.vivian.mobilebc.core.socket

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PendingRequestTest {
    @Test
    fun singleRequestRejectsDuplicateAndAcceptsNextAfterCompletion(): Unit = runBlocking {
        val requests = PendingRequest<String>("duplicate")
        val first = requests.begin()

        assertFailureMessage("duplicate") { requests.begin() }

        requests.complete("first response")
        assertEquals("first response", first.await())

        val second = requests.begin()
        requests.complete("second response")
        assertEquals("second response", second.await())
    }

    @Test
    fun staleSingleRequestCleanupDoesNotClearNewOwner() {
        val requests = PendingRequest<String>("duplicate")
        val first = requests.begin()
        requests.complete("done")
        val second = requests.begin()

        requests.clear(first)

        assertFailureMessage("duplicate") { requests.begin() }
        requests.clear(second)
        requests.begin()
    }

    @Test
    fun exceptionalCompletionReleasesSingleRequest() {
        val requests = PendingRequest<String>("duplicate")
        val pending = requests.begin()
        val failure = IllegalStateException("disconnected")

        requests.completeExceptionally(failure)

        val observed = runCatching { runBlocking { pending.await() } }.exceptionOrNull()
        assertTrue(observed is IllegalStateException)
        assertEquals("disconnected", observed?.message)
        requests.begin()
    }

    @Test
    fun keyedRequestsCorrelateResponsesAndRejectOnlyDuplicateKey(): Unit = runBlocking {
        val requests = KeyedPendingRequests<Int, Boolean> { "duplicate $it" }
        val first = requests.begin(1001)
        val second = requests.begin(1002)

        assertFailureMessage("duplicate 1001") { requests.begin(1001) }

        requests.complete(1002, true)
        requests.complete(1001, false)
        assertEquals(false, first.await())
        assertEquals(true, second.await())
    }

    @Test
    fun keyedCompleteAllReleasesEveryKey(): Unit = runBlocking {
        val requests = KeyedPendingRequests<Int, Boolean> { "duplicate $it" }
        val first = requests.begin(1001)
        val second = requests.begin(1002)

        requests.completeAll(false)

        assertEquals(false, first.await())
        assertEquals(false, second.await())
        requests.begin(1001).cancel()
        requests.begin(1002).cancel()
    }

    private fun assertFailureMessage(expected: String, action: () -> Unit) {
        try {
            action()
            fail("Expected IllegalStateException")
        } catch (failure: IllegalStateException) {
            assertEquals(expected, failure.message)
        }
    }
}
