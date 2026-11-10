package dev.vivian.mobilebc.portrait

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PortraitVisibilityTest {
    @Test fun defaultsHiddenAndPersistsExplicitChangesAcrossRecreation() {
        val values = mutableMapOf<String, Boolean>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { values[args[0] as String] = args[1] as Boolean; proxy }
                "apply" -> null
                else -> error("Unexpected editor call ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> values[args[0] as String] ?: args[1] as Boolean
                "edit" -> editor
                else -> error("Unexpected preferences call ${method.name}")
            }
        } as SharedPreferences
        val visibility = PortraitVisibility(preferences)
        assertFalse(visibility.visible)
        visibility.updateVisible(true)
        assertTrue(visibility.visible)
        assertTrue(PortraitVisibility(preferences).visible)
        visibility.updateVisible(false)
        assertFalse(visibility.visible)
        assertFalse(PortraitVisibility(preferences).visible)
        val pending = visibility.beginReveal()
        visibility.updateVisible(false)
        visibility.completeReveal(pending)
        assertFalse("A stale reveal must not undo an explicit hide", visibility.visible)

        visibility.completeReveal(visibility.beginReveal())
        assertTrue(visibility.visible)
    }
}
