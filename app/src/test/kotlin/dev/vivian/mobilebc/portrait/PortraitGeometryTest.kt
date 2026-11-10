package dev.vivian.mobilebc.portrait

import dev.vivian.mobilebc.domain.bcrules.BcInteractionZoneRect
import dev.vivian.mobilebc.feature.roomdetails.InteractionGroupUiState
import org.junit.Assert.*
import org.junit.Test

class PortraitGeometryTest {
    private val zone = BcInteractionZoneRect(95, 195, 10, 10)

    @Test fun mapsScaledAndShiftedZonesLikeR133Dialog() {
        val geometry = PortraitGeometry(x = 25.0, width = 450.0, sourceY = 0.0,
            sourceHeight = 1250.0, destY = -125.0, destHeight = 1125.0, inverted = false)
        // R133: left = 25 + 95 * 0.9; top = -125 + 195 * 0.9.
        assertTrue(geometry.contains(zone, 115f, 55f))
        assertFalse(geometry.contains(zone, 125f, 55f))
        assertFalse(geometry.contains(zone, 115f, 65f))
        assertFalse(geometry.contains(zone, -1f, 55f))
    }

    @Test fun invertedZonesFlipVerticallyButNotHorizontally() {
        val geometry = PortraitGeometry(x = 25.0, width = 450.0, sourceY = 0.0,
            sourceHeight = 1250.0, destY = 0.0, destHeight = 1000.0, inverted = true)
        // R133: top = 1000 - (-125 + (195 + 10) * 0.9).
        assertTrue(geometry.contains(zone, 115f, 945f))
        assertFalse(geometry.contains(zone, 385f, 945f))
        assertFalse(geometry.contains(zone, 115f, 55f))
    }

    @Test fun croppedPortraitUsesOriginalYOffsetForInteraction() {
        val geometry = PortraitGeometry(x = -50.0, width = 600.0, sourceY = -100.0,
            sourceHeight = 1000.0 / 1.2, destY = 0.0, destHeight = 1000.0, inverted = false)
        assertTrue(geometry.contains(zone, 70f, 360f))
        assertFalse(geometry.contains(zone, 70f, 220f))
    }

    @Test fun drawnZoneBoundsAndHitTestingUseTheSameClippedRectangle() {
        val geometry = PortraitGeometry(0.0, 500.0, 0.0, 1000.0, 0.0, 1000.0, false)
        val clipped = BcInteractionZoneRect(-20, -10, 40, 30)
        assertEquals(PortraitZoneBounds(0f, 0f, 20f, 20f), geometry.bounds(clipped))
        assertTrue(geometry.contains(clipped, 0f, 0f))
        assertTrue(geometry.contains(clipped, 19f, 19f))
        assertFalse(geometry.contains(clipped, 20f, 10f))
        assertFalse(geometry.contains(clipped, -1f, 10f))
        assertNull(geometry.bounds(BcInteractionZoneRect(600, 0, 20, 20)))
    }
    @Test fun rejectsInvalidOrNonPositiveGeometry() {
        assertNull(PortraitGeometry.parse("{}"))
        assertNull(PortraitGeometry.parse("""{"x":0,"width":0,"sourceY":0,"sourceHeight":1000,"destY":0,"destHeight":1000,"inverted":false}"""))
        val geometry = PortraitGeometry(0.0, 500.0, 0.0, 1000.0, 0.0, 1000.0, false)
        assertEquals(geometry, PortraitGeometry.parse(geometry.json()))
    }

    @Test fun itemZonesIncludeAvailableAndCurrentlyWornItems() {
        val geometry = PortraitGeometry(0.0, 500.0, 0.0, 1000.0, 0.0, 1000.0, false)
        fun item(name: String, hasItems: Boolean, worn: Boolean) = InteractionGroupUiState(name, name,
            hasActivities = false, hasItems = hasItems, hasCurrentItem = worn, zones = listOf(zone))
        val groups = listOf(item("available", true, false), item("worn", false, true), item("empty", false, false))
        val matches = portraitGroupsAt(groups, geometry, 100f, 200f) { it.hasItems || it.hasCurrentItem }
        assertEquals(listOf("available", "worn"), matches.map { it.name })
        assertTrue(portraitGroupsAt(groups, geometry, 100f, 200f).isEmpty())
    }
    @Test fun overlappingAvailableGroupsAreAllOfferedWithoutDuplicates() {
        fun group(name: String, available: Boolean) = InteractionGroupUiState(name, name,
            hasActivities = available, hasItems = false, hasCurrentItem = false, zones = listOf(zone))
        val geometry = PortraitGeometry(0.0, 500.0, 0.0, 1000.0, 0.0, 1000.0, false)
        val result = portraitGroupsAt(listOf(group("a", true), group("b", true), group("a", true),
            group("disabled", false)), geometry, 100f, 200f)
        assertEquals(listOf("a", "b"), result.map { it.name })
        assertTrue(portraitGroupsAt(result, geometry, 10f, 10f).isEmpty())
    }
}
