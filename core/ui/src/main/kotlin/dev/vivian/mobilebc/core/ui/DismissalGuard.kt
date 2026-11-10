package dev.vivian.mobilebc.core.ui

internal class DismissalGuard {
    private var valid = true
    private var pending = false

    fun begin(): Boolean {
        if (!valid || pending) return false
        pending = true
        return true
    }

    fun complete(): Boolean {
        if (!valid) return false
        valid = false
        return true
    }

    fun cancelPending() { pending = false }
    fun invalidate() { valid = false }
}
