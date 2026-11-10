package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.domain.bcrules.BcInteractionZoneRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBodyPartLayoutTest {
    // R133 Assets/Female3DCG.js: group, source line, x, y, width, height.
    private val groups = checkNotNull(javaClass.getResourceAsStream("/r133-body-zones.tsv"))
        .bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { it.trimStart('\uFEFF').split('\t') }
                .toList().groupBy { it[0] }.map { (name, rows) ->
                    InteractionGroupUiState(
                        name = name,
                        displayName = name,
                        hasActivities = true,
                        hasItems = true,
                        hasCurrentItem = false,
                        zones = rows.map { row ->
                            BcInteractionZoneRect(row[2].toInt(), row[3].toInt(), row[4].toInt(), row[5].toInt())
                        },
                    )
                }
        }

    @Test
    fun r133RegionsCoverTheOriginalFrameWithoutOverlapping() {
        val regions = RoomBodyPartLayout.regions(groups)
        assertEquals(28, groups.size)
        assertEquals(32, regions.size)
        assertEquals(0f, regions.minOf { it.x }, 0.00001f)
        assertEquals(0f, regions.minOf { it.y }, 0.00001f)
        assertEquals(1f, regions.maxOf { it.x + it.width }, 0.00001f)
        assertEquals(1f, regions.maxOf { it.y + it.height }, 0.00001f)
        regions.forEachIndexed { index, a ->
            regions.drop(index + 1).forEach { b ->
                val overlapWidth = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
                val overlapHeight = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
                assertTrue("${a.group.name} overlaps ${b.group.name}", overlapWidth < 0.00001f || overlapHeight < 0.00001f)
            }
        }
    }

    @Test
    fun mirroredSlotsKeepBothRegionsAndFilteringDoesNotMoveThem() {
        val regions = RoomBodyPartLayout.regions(groups)
        val mirrored = regions.groupBy { it.group.name }.filterValues { it.size == 2 }
        assertEquals(setOf("ItemArms", "ItemHands", "ItemHandheld", "ItemDevices"), mirrored.keys)
        mirrored.forEach { (name, pair) ->
            assertEquals(0f, pair[0].x, 0.00001f)
            assertEquals(390f / 480f, pair[1].x, 0.00001f)
            assertEquals(pair, RoomBodyPartLayout.regions(groups.filter { it.name == name }))
        }
    }

    @Test
    fun headAndNoseShareAnExactEdgeAndRemainTouchSizedAtMinimumWidth() {
        val regions = RoomBodyPartLayout.regions(groups)
        val head = regions.single { it.group.name == "ItemHead" }
        val nose = regions.single { it.group.name == "ItemNose" }
        assertEquals(165f / 480f, head.x, 0.00001f)
        assertEquals(head.y + head.height, nose.y, 0.00001f)
        regions.forEach {
            assertTrue(it.width * 360f >= 48f)
            assertTrue(it.height * 750f >= 48f)
        }
    }
}
