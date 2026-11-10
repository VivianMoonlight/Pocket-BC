package dev.vivian.mobilebc.feature.roomdetails

/** Complete R133 Female3DCG Zone bounds, also used by DialogFocusGroup.Create.
 * Keep the frame independent of visible groups so filtering never moves a slot.
 */
internal object RoomBodyPartLayout {
    const val left = 10f
    const val top = 0f
    const val width = 480f
    const val height = 1000f

    fun regions(groups: List<InteractionGroupUiState>): List<BodyPartRegion> =
        groups.flatMap { group ->
            group.zones.map { zone ->
                BodyPartRegion(
                    group = group,
                    x = (zone.x - left) / width,
                    y = (zone.y - top) / height,
                    width = zone.width / width,
                    height = zone.height / height,
                )
            }
        }.sortedWith(compareBy<BodyPartRegion> { it.y }.thenBy { it.x })
}

internal data class BodyPartRegion(
    val group: InteractionGroupUiState,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)
