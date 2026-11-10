package dev.vivian.mobilebc.feature.characterprofile

import dev.vivian.mobilebc.feature.rooms.R
import org.junit.Assert.*
import org.junit.Test

class ProfileRelationDetailsTest {
    @Test fun mapsOwnershipAndLovershipStagesSeparately() {
        assertEquals(R.string.character_relation_trial, relationStageLabel(true, 0))
        assertEquals(R.string.character_relation_owned, relationStageLabel(true, 1))
        assertEquals(R.string.character_relation_dating, relationStageLabel(false, 0))
        assertEquals(R.string.character_relation_engaged, relationStageLabel(false, 1))
        assertEquals(R.string.character_relation_married, relationStageLabel(false, 2))
        assertEquals(R.string.character_relation_stage_unknown, relationStageLabel(true, null))
        assertEquals(R.string.character_relation_stage_unknown, relationStageLabel(false, 99))
    }

    @Test fun durationUsesServerStartAndRejectsMissingOrFutureTimestamps() {
        val start = 1_700_000_000_000L
        assertEquals(1501L, relationDurationMinutes(start, start + 1501 * 60_000))
        assertEquals(0L, relationDurationMinutes(start, start + 59_999))
        assertNull(relationDurationMinutes(null, start))
        assertNull(relationDurationMinutes(0, start))
        assertNull(relationDurationMinutes(-1, start))
        assertNull(relationDurationMinutes(start + 1, start))
    }
}
