package dev.vivian.mobilebc.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.vivian.mobilebc.core.model.BcNotificationSettings

/**
 * Small app-layer settings store for notification interruption behavior.
 *
 * This intentionally lives outside platform/notification: the platform controller
 * only knows how to build Android notifications; this store represents user
 * preferences that other app/feature modules may edit later.
 */
class BcNotificationSettingsStore internal constructor(private val prefs: SharedPreferences) : AutoCloseable {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private var accountKey: String = "default"
    private val current = MutableStateFlow(read())
    val settings: StateFlow<BcNotificationSettings> = current.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        current.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        current.value = read()
    }

    override fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun selectAccount(memberNumber: Int?) {
        val key = memberNumber?.toString() ?: "default"
        if (accountKey == key) return
        accountKey = key
        current.value = read()
    }

    private fun read(): BcNotificationSettings = readNotificationRules(
        prefs.getString("rules_v1_$accountKey", null),
        BcNotificationSettings(
            suppressRoomMessagesWhenForeground = prefs.getBoolean(KEY_SUPPRESS_ROOM_FOREGROUND, true),
            suppressDirectMessagesWhenForeground = prefs.getBoolean(KEY_SUPPRESS_DIRECT_FOREGROUND, false),
            suppressSystemAlertsWhenForeground = prefs.getBoolean(KEY_SUPPRESS_SYSTEM_FOREGROUND, false),
        ),
    )

    fun save(settings: BcNotificationSettings) {
        prefs.edit()
            .putString("rules_v1_$accountKey", settings.toRulesJson())
            .apply()
        current.value = read()
    }

    private companion object {
        private const val PREFS_NAME = "bc_notification_settings"
        private const val KEY_SUPPRESS_ROOM_FOREGROUND = "suppress_room_foreground"
        private const val KEY_SUPPRESS_DIRECT_FOREGROUND = "suppress_direct_foreground"
        private const val KEY_SUPPRESS_SYSTEM_FOREGROUND = "suppress_system_foreground"
    }
}
