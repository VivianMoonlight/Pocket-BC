package dev.vivian.mobilebc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Composition forgets outgoing owners before remembering replacements in the same frame. */
internal class ScreenModelOwners(private val scope: CoroutineScope) {
    private val counts = mutableMapOf<Any, Int>()
    private val releases = mutableMapOf<Any, Job>()

    fun retain(model: Any, close: () -> Unit): () -> Unit {
        releases.remove(model)?.cancel()
        counts[model] = (counts[model] ?: 0) + 1
        var released = false
        return {
            if (!released) {
                released = true
                release(model, close)
            }
        }
    }

    private fun release(model: Any, close: () -> Unit) {
        val remaining = (counts[model] ?: return) - 1
        if (remaining > 0) {
            counts[model] = remaining
            return
        }
        counts.remove(model)
        releases[model] = scope.launch {
            yield()
            releases.remove(model)
            if (model !in counts) close()
        }
    }
}
