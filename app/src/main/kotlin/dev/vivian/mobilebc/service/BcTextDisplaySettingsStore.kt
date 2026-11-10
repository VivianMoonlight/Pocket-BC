package dev.vivian.mobilebc.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BcTextDisplaySettingsStore internal constructor(private val prefs: SharedPreferences) : AutoCloseable {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val current = MutableStateFlow(readDeobfuscationEnabled())
    val deobfuscationEnabled: StateFlow<Boolean> = current.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        current.value = readDeobfuscationEnabled()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        current.value = readDeobfuscationEnabled()
    }

    override fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun readDeobfuscationEnabled(): Boolean =
        prefs.getBoolean(KEY_DEOBFUSCATION_ENABLED, false)

    fun saveDeobfuscationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEOBFUSCATION_ENABLED, enabled).apply()
        current.value = readDeobfuscationEnabled()
    }

    private companion object {
        private const val PREFS_NAME = "bc_text_display_settings"
        private const val KEY_DEOBFUSCATION_ENABLED = "deobfuscation_enabled"
    }
}
