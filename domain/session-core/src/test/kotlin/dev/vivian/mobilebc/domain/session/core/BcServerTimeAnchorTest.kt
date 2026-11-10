package dev.vivian.mobilebc.domain.session.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcServerTimeAnchorTest {
    @Test
    fun timeIsUnavailableBeforeTheServerClockHasBeenAnchored() {
        assertNull(BcSessionSnapshot().estimatedServerTimeMillis(nowMonotonicNanos = 5_000_000_000L))
    }

    @Test
    fun advancesTheServerEpochByElapsedMonotonicTime() {
        val session = anchored(epochMillis = 1_700_000_000_123L, receivedAtMonotonicNanos = 5_000_000_000L)

        assertEquals(1_700_000_001_357L, session.estimatedServerTimeMillis(nowMonotonicNanos = 6_234_567_890L))
        assertEquals(1_700_000_000_123L, session.estimatedServerTimeMillis(nowMonotonicNanos = 5_000_000_000L))
    }

    @Test
    fun partialMillisecondsCannotMakeAnOwnerRuleExpireEarly() {
        val session = anchored(epochMillis = 1_700_000_000_123L, receivedAtMonotonicNanos = 10_000_000L)

        assertEquals(1_700_000_000_123L, session.estimatedServerTimeMillis(nowMonotonicNanos = 10_999_999L))
        assertEquals(1_700_000_000_124L, session.estimatedServerTimeMillis(nowMonotonicNanos = 11_000_000L))
        assertEquals(1_700_000_000_124L, session.estimatedServerTimeMillis(nowMonotonicNanos = 11_999_999L))
        assertEquals(1_700_000_000_125L, session.estimatedServerTimeMillis(nowMonotonicNanos = 12_000_000L))
    }

    @Test
    fun aNegativeServerEpochCannotProvideAClockEstimate() {
        val session = anchored(epochMillis = -1L, receivedAtMonotonicNanos = 10_000_000L)

        assertNull(session.estimatedServerTimeMillis(nowMonotonicNanos = 10_000_000L))
        assertNull(session.estimatedServerTimeMillis(nowMonotonicNanos = 11_000_000L))
    }

    @Test
    fun aClockSampleEarlierThanTheAnchorIsUnavailable() {
        val session = anchored(epochMillis = 1_700_000_000_123L, receivedAtMonotonicNanos = 10_000_000L)

        assertNull(session.estimatedServerTimeMillis(nowMonotonicNanos = 9_999_999L))
        assertNull(session.estimatedServerTimeMillis(nowMonotonicNanos = 9_000_000L))
    }

    @Test
    fun monotonicClocksCanHaveANegativeOrigin() {
        val session = anchored(epochMillis = 1_700_000_000_123L, receivedAtMonotonicNanos = -9_000_000_000L)

        assertEquals(1_700_000_001_123L, session.estimatedServerTimeMillis(nowMonotonicNanos = -8_000_000_000L))
    }

    @Test
    fun aMonotonicCounterCrossingTheSignedBoundaryStillMeasuresElapsedTime() {
        val session = anchored(
            epochMillis = 1_700_000_000_123L,
            receivedAtMonotonicNanos = Long.MAX_VALUE - 500_000L,
        )

        assertEquals(
            1_700_000_000_124L,
            session.estimatedServerTimeMillis(nowMonotonicNanos = Long.MIN_VALUE + 499_999L),
        )
    }

    @Test
    fun epochOverflowIsUnavailableInsteadOfWrappingIntoThePast() {
        val nearLimit = anchored(epochMillis = Long.MAX_VALUE - 1L, receivedAtMonotonicNanos = 0L)
        val atLimit = anchored(epochMillis = Long.MAX_VALUE, receivedAtMonotonicNanos = 0L)

        assertEquals(Long.MAX_VALUE, nearLimit.estimatedServerTimeMillis(nowMonotonicNanos = 1_000_000L))
        assertNull(nearLimit.estimatedServerTimeMillis(nowMonotonicNanos = 2_000_000L))
        assertEquals(Long.MAX_VALUE, atLimit.estimatedServerTimeMillis(nowMonotonicNanos = 999_999L))
        assertNull(atLimit.estimatedServerTimeMillis(nowMonotonicNanos = 1_000_000L))
    }

    private fun anchored(epochMillis: Long, receivedAtMonotonicNanos: Long): BcSessionSnapshot =
        BcSessionSnapshot(serverTimeAnchor = BcServerTimeAnchor(epochMillis, receivedAtMonotonicNanos))
}
