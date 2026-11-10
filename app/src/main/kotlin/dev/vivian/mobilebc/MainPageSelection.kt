package dev.vivian.mobilebc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Separates requested navigation from the page that still owns transient UI. */
internal class MainPageSelection(initialPage: Int) {
    var committedPage by mutableStateOf(initialPage)
        private set
    var pendingPage by mutableStateOf<Int?>(null)
        private set
    private var generation = 0L

    fun request(page: Int): Long? {
        // A settled gesture is echoed through navigation; it is not another page transition.
        if (page == committedPage && pendingPage == null) return null
        pendingPage = page
        return ++generation
    }

    fun complete(request: Long?, settledPage: Int): Boolean {
        if (request == null) return false
        if (request != generation || pendingPage != settledPage) return false
        committedPage = settledPage
        pendingPage = null
        return true
    }

    fun settleGesture(page: Int, scrolling: Boolean): Boolean {
        if (scrolling || pendingPage != null || page == committedPage) return false
        committedPage = page
        return true
    }
}
