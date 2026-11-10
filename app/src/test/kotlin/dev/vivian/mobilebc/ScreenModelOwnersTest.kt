package dev.vivian.mobilebc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenModelOwnersTest {
    @Test fun replacementInTheSameFrameKeepsTheModelAlive() = runTest {
        val owners = ScreenModelOwners(this)
        val model = Any()
        var closed = 0
        val releaseOld = owners.retain(model) { closed++ }
        releaseOld()
        val releaseNew = owners.retain(model) { closed++ }
        runCurrent()
        assertEquals(0, closed)
        releaseNew()
        runCurrent()
        assertEquals(1, closed)
    }

    @Test fun overlappingOwnersReleaseOnlyAfterTheLastExit() = runTest {
        val owners = ScreenModelOwners(this)
        val model = Any()
        var closed = 0
        val releaseFirst = owners.retain(model) { closed++ }
        val releaseSecond = owners.retain(model) { closed++ }
        releaseFirst()
        releaseFirst()
        runCurrent()
        assertEquals(0, closed)
        releaseSecond()
        runCurrent()
        assertEquals(1, closed)
    }

    @Test fun oldSessionReleaseDoesNotAffectNewInstance() = runTest {
        val oldOwners = ScreenModelOwners(this)
        val newOwners = ScreenModelOwners(this)
        val old = Any()
        val current = Any()
        val closed = mutableListOf<Any>()
        val releaseOld = oldOwners.retain(old) { closed += old }
        val releaseNew = newOwners.retain(current) { closed += current }
        releaseOld()
        runCurrent()
        assertEquals(listOf(old), closed)
        releaseNew()
        runCurrent()
        assertEquals(listOf(old, current), closed)
    }
}
