package dev.vivian.mobilebc

import org.junit.Assert.*
import org.junit.Test

class MainPageSelectionTest {
    @Test fun cancelledSwipeKeepsOwnership() {
        val state = MainPageSelection(0)
        assertFalse(state.settleGesture(1, scrolling = true))
        assertFalse(state.settleGesture(0, scrolling = false))
        assertEquals(0, state.committedPage)
    }

    @Test fun ownershipChangesOnlyAfterArrival() {
        val state = MainPageSelection(0)
        val request = state.request(2)
        assertEquals(0, state.committedPage)
        assertFalse(state.settleGesture(1, scrolling = false))
        assertFalse(state.complete(request, 1))
        assertTrue(state.complete(request, 2))
        assertEquals(2, state.committedPage)
        assertNull(state.pendingPage)
    }

    @Test fun newestRequestWinsEvenWhenReturningToTheSameTarget() {
        val state = MainPageSelection(0)
        val first = state.request(1)
        val second = state.request(2)
        val third = state.request(1)
        assertFalse(state.complete(first, 1))
        assertFalse(state.complete(second, 2))
        assertEquals(0, state.committedPage)
        assertTrue(state.complete(third, 1))
        assertFalse(state.complete(third, 1))
    }

    @Test fun settledGestureCommitsOnce() {
        val state = MainPageSelection(0)
        assertTrue(state.settleGesture(1, scrolling = false))
        assertFalse(state.settleGesture(1, scrolling = false))
        assertEquals(1, state.committedPage)
    }

    @Test fun navigationEchoAfterSwipeDoesNotDisableTheSettledPageOrCompleteTwice() {
        val state = MainPageSelection(0)
        assertTrue(state.settleGesture(1, scrolling = false))

        val echo = state.request(1)

        assertNull(echo)
        assertNull(state.pendingPage)
        assertFalse(state.complete(echo, 1))
        assertEquals(1, state.committedPage)
    }

    @Test fun returningToCommittedPageStillCancelsAnOutstandingRequest() {
        val state = MainPageSelection(0)
        val outgoing = state.request(2)
        val returning = state.request(0)

        assertNotNull(returning)
        assertFalse(state.complete(outgoing, 2))
        assertTrue(state.complete(returning, 0))
        assertNull(state.pendingPage)
    }
}
