package dev.vivian.mobilebc.service

import android.content.Context
import android.content.SharedPreferences
import dev.vivian.mobilebc.core.model.AppAppearance
import dev.vivian.mobilebc.core.model.ChatBackground
import dev.vivian.mobilebc.core.model.ThemeColorRole

class BcAppearanceStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("bc_appearance", Context.MODE_PRIVATE),
    )

    fun read(): AppAppearance {
        val base = AppAppearance.fromStored(prefs.getString("mode", null), prefs.getString("palette", null))
        val colors = ThemeColorRole.entries.mapNotNull { role ->
            prefs.getString("color_${role.name}", null)?.toLongOrNull(16)
                ?.takeIf { it in 0..0xFFFFFFFFL }?.let { role to it }
        }.toMap()
        fun background(prefix: String) = ChatBackground(
            imageUri = prefs.getString("${prefix}_image", null),
            overlayPercent = prefs.getString("${prefix}_overlay", null)?.toIntOrNull()?.coerceIn(0, 100) ?: 75,
        )
        return base.copy(customColors = colors, roomBackground = background("room"), directBackground = background("direct"))
    }

    fun save(appearance: AppAppearance) {
        val editor = prefs.edit().putString("mode", appearance.mode.name).putString("palette", appearance.palette.name)
        ThemeColorRole.entries.forEach { role ->
            val color = appearance.customColors[role]
            if (color == null) editor.remove("color_${role.name}")
            else editor.putString("color_${role.name}", color.toString(16))
        }
        fun saveBackground(prefix: String, background: ChatBackground) {
            if (background.imageUri == null) editor.remove("${prefix}_image")
            else editor.putString("${prefix}_image", background.imageUri)
            editor.putString("${prefix}_overlay", background.overlayPercent.coerceIn(0, 100).toString())
        }
        saveBackground("room", appearance.roomBackground)
        saveBackground("direct", appearance.directBackground)
        editor.apply()
    }
}
