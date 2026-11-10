package dev.vivian.mobilebc.core.ui

import org.junit.Assert.*
import org.junit.Test

class DismissalGuardTest {
    @Test fun buttonAndSystemDismissOnlyCompleteOnce() {
        val guard = DismissalGuard()
        assertTrue(guard.begin())
        assertFalse(guard.begin())
        assertTrue(guard.complete())
        assertFalse(guard.complete())
        assertFalse(guard.begin())
    }

    @Test fun cancelledHideCanBeRetried() {
        val guard = DismissalGuard()
        assertTrue(guard.begin())
        guard.cancelPending()
        assertTrue(guard.begin())
        assertTrue(guard.complete())
    }

    @Test fun oldInstanceCannotDismissReopenedSheetOrChangedOwner() {
        val old = DismissalGuard()
        old.begin()
        old.invalidate()
        val reopened = DismissalGuard()
        assertFalse(old.complete())
        assertTrue(reopened.begin())
        assertTrue(reopened.complete())
    }
}
