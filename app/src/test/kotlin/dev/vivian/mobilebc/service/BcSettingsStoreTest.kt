package dev.vivian.mobilebc.service

import android.content.SharedPreferences
import dev.vivian.mobilebc.core.model.BcNotificationSettings
import dev.vivian.mobilebc.core.model.BcNotificationRule
import dev.vivian.mobilebc.core.model.BcNotificationLevel
import dev.vivian.mobilebc.core.model.ChatBackground
import dev.vivian.mobilebc.core.model.ThemeColorRole
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcSettingsStoreTest {
    @Test
    fun customAppearancePersistsAndCanBeResetIndependently() {
        val prefs = BooleanPreferences()
        val store = BcAppearanceStore(prefs.instance)
        val appearance = store.read().copy(
            customColors = mapOf(ThemeColorRole.Primary to 0xFF123456L),
            roomBackground = ChatBackground("content://room/image", 35),
            directBackground = ChatBackground("content://direct/image", 80),
        )
        store.save(appearance)
        assertEquals(appearance, BcAppearanceStore(prefs.instance).read())
        store.save(appearance.copy(customColors = emptyMap(), roomBackground = ChatBackground()))
        assertEquals(appearance.copy(customColors = emptyMap(), roomBackground = ChatBackground()), store.read())
        prefs.instance.edit().putString("color_Primary", "invalid").putString("direct_overlay", "500").apply()
        assertTrue(store.read().customColors.isEmpty())
        assertEquals(100, store.read().directBackground.overlayPercent)
    }

    @Test
    fun appearancePersistsAcrossRecreationAndFallsBackForUnknownValues() {
        val prefs = BooleanPreferences()
        val store = BcAppearanceStore(prefs.instance)
        assertEquals(dev.vivian.mobilebc.core.model.AppAppearance(), store.read())
        val appearance = dev.vivian.mobilebc.core.model.AppAppearance(
            dev.vivian.mobilebc.core.model.ThemeMode.Black,
            dev.vivian.mobilebc.core.model.ThemePalette.Forest,
        )
        store.save(appearance)
        assertEquals(appearance, BcAppearanceStore(prefs.instance).read())
        prefs.instance.edit().putString("palette", "future").apply()
        assertEquals(appearance.copy(palette = dev.vivian.mobilebc.core.model.ThemePalette.Rose), store.read())
    }

    @Test
    fun notificationRulesPersistPerAccountAndPreserveOrder() {
        val prefs = BooleanPreferences()
        val store = BcNotificationSettingsStore(prefs.instance)
        store.selectAccount(10)
        val settings = BcNotificationSettings(
            matrix = mapOf("Submissive:Beep" to BcNotificationLevel.Critical),
            rules = listOf(
                BcNotificationRule("one", "Silence", memberNumber = 42, level = BcNotificationLevel.Off),
                BcNotificationRule("two", "Help", keyword = "help", involvesSelf = true),
            ),
        )
        store.save(settings)
        store.selectAccount(20)
        assertEquals(BcNotificationSettings(), store.settings.value)
        store.save(BcNotificationSettings(systemLevel = BcNotificationLevel.Off))
        store.selectAccount(10)
        assertEquals(settings, store.settings.value)
        store.close()
        val reopened = BcNotificationSettingsStore(prefs.instance)
        reopened.selectAccount(10)
        assertEquals(settings, reopened.settings.value)
        reopened.close()
    }

    @Test
    fun legacySuppressionMigratesAndCorruptRulesFallBack() {
        val prefs = BooleanPreferences()
        prefs.instance.edit().putBoolean("suppress_direct_foreground", true).apply()
        prefs.instance.edit().putString("rules_v1_10", "invalid json").apply()
        val store = BcNotificationSettingsStore(prefs.instance)
        store.selectAccount(10)
        assertTrue(store.settings.value.suppressDirectMessagesWhenForeground)
        store.close()
    }

    @Test
    fun notificationStoresSharePersistedUpdatesAndUnregisterOnClose() {
        val prefs = BooleanPreferences()
        val first = BcNotificationSettingsStore(prefs.instance)
        val second = BcNotificationSettingsStore(prefs.instance)
        val changed = BcNotificationSettings(suppressDirectMessagesWhenForeground = true)
        first.save(changed)
        assertEquals(changed, first.settings.value)
        assertEquals(changed, second.settings.value)
        first.close()
        second.save(changed.copy(suppressSystemAlertsWhenForeground = true))
        assertEquals(changed, first.settings.value)
        assertTrue(second.settings.value.suppressSystemAlertsWhenForeground)
        val restored = BcNotificationSettingsStore(prefs.instance)
        assertEquals(second.settings.value, restored.settings.value)
        second.close()
        restored.close()
        assertTrue(prefs.listeners.isEmpty())
    }

    @Test
    fun textSettingsFollowOtherWritersAndKeepTheirPersistedValue() {
        val prefs = BooleanPreferences()
        val first = BcTextDisplaySettingsStore(prefs.instance)
        val second = BcTextDisplaySettingsStore(prefs.instance)
        assertFalse(first.deobfuscationEnabled.value)
        first.saveDeobfuscationEnabled(true)
        assertTrue(second.deobfuscationEnabled.value)
        first.close()
        second.saveDeobfuscationEnabled(false)
        assertTrue(first.deobfuscationEnabled.value)
        assertFalse(second.deobfuscationEnabled.value)
        second.close()
        assertTrue(prefs.listeners.isEmpty())
    }

    /** Supports the preference operations used by these stores; unexpected calls fail. */
    private class BooleanPreferences {
        private val values = mutableMapOf<String, Any>()
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val instance: SharedPreferences = proxy { name, args ->
            when (name) {
                "getBoolean", "getString" -> values[args[0] as String] ?: args[1]
                "registerOnSharedPreferenceChangeListener" -> {
                    listeners.add(args[0] as SharedPreferences.OnSharedPreferenceChangeListener)
                    null
                }
                "unregisterOnSharedPreferenceChangeListener" -> {
                    listeners.remove(args[0] as SharedPreferences.OnSharedPreferenceChangeListener)
                    null
                }
                "edit" -> editor()
                else -> error("Unexpected preference operation: $name")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy { name, args ->
                when (name) {
                    "putBoolean", "putString" -> {
                        changes[args[0] as String] = args[1] as Any
                        editor
                    }
                    "remove" -> {
                        changes[args[0] as String] = null
                        editor
                    }
                    "apply" -> {
                        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        changes.keys.forEach { key ->
                            listeners.toList().forEach { it.onSharedPreferenceChanged(instance, key) }
                        }
                        null
                    }
                    else -> error("Unexpected editor operation: $name")
                }
            }
            return editor
        }

        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args ?: emptyArray())
            } as T
    }
}
