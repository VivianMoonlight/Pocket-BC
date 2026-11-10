package dev.vivian.mobilebc.portrait

import dev.vivian.mobilebc.domain.bcrules.BcInteractionZoneRect
import dev.vivian.mobilebc.feature.roomdetails.InteractionGroupUiState
import org.json.JSONObject

/** Geometry of the final 500x1000 image, from the same renderer revision as the frame. */
internal data class PortraitGeometry(
    val x: Double, val width: Double, val sourceY: Double, val sourceHeight: Double,
    val destY: Double, val destHeight: Double, val inverted: Boolean,
) {
    fun bounds(zone: BcInteractionZoneRect): PortraitZoneBounds? {
        if (width <= 0 || sourceHeight <= 0 || destHeight <= 0) return null
        // Match R133 DialogGetCharacterZone, including its vertical-only inversion.
        val ratio = width / 500.0
        val yOffset = if (sourceY != 0.0) -sourceY * ratio else 1000.0 - sourceHeight * ratio
        val left = x + zone.x * ratio
        val top = if (inverted) 1000.0 - (yOffset + (zone.y + zone.height) * ratio)
            else yOffset + zone.y * ratio
        val right = (left + zone.width * ratio).coerceAtMost(500.0)
        val bottom = (top + zone.height * ratio).coerceAtMost(1000.0)
        val clippedLeft = left.coerceAtLeast(0.0)
        val clippedTop = top.coerceAtLeast(0.0)
        return if (right > clippedLeft && bottom > clippedTop) {
            PortraitZoneBounds(clippedLeft.toFloat(), clippedTop.toFloat(), right.toFloat(), bottom.toFloat())
        } else null
    }

    fun contains(zone: BcInteractionZoneRect, px: Float, py: Float): Boolean =
        bounds(zone)?.let { px >= it.left && px < it.right && py >= it.top && py < it.bottom } == true
    fun json(): String = JSONObject().put("x", x).put("width", width).put("sourceY", sourceY)
        .put("sourceHeight", sourceHeight).put("destY", destY).put("destHeight", destHeight)
        .put("inverted", inverted).toString()

    companion object {
        fun parse(raw: String): PortraitGeometry? = runCatching {
            val json = JSONObject(raw)
            PortraitGeometry(json.getDouble("x"), json.getDouble("width"), json.getDouble("sourceY"),
                json.getDouble("sourceHeight"), json.getDouble("destY"), json.getDouble("destHeight"),
                json.getBoolean("inverted")).takeIf { geometry ->
                listOf(geometry.x, geometry.width, geometry.sourceY, geometry.sourceHeight,
                    geometry.destY, geometry.destHeight).all { it.isFinite() } &&
                    geometry.width > 0 && geometry.sourceHeight > 0 && geometry.destHeight > 0
            }
        }.getOrNull()
    }
}

internal data class PortraitZoneBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal fun portraitGroupsAt(groups: List<InteractionGroupUiState>, geometry: PortraitGeometry, x: Float, y: Float,
    eligible: (InteractionGroupUiState) -> Boolean = { it.hasActivities }): List<InteractionGroupUiState> =
    groups.filter { group -> eligible(group) && group.zones.any { geometry.contains(it, x, y) } }
        .distinctBy { it.name }
