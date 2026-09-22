package app.keyweb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Keyweb locks itself when nobody is using it.
 *
 * The clock is driven by hand, because the case that matters is the one where
 * the app ran nothing at all: backgrounded, the phone asleep in a pocket.
 */
class IdleLockTest {
    private var clock = 1_000_000L
    private val idle = IdleClock { clock }

    @Test
    fun locksOnceTheTimeHasPassedUntouched() {
        clock += 14 * 60_000L
        assertFalse(idle.expired(LockAfter.FIFTEEN))
        clock += 60_000L
        assertTrue(idle.expired(LockAfter.FIFTEEN))
    }

    @Test
    fun staysOpenWhileSomeoneKeepsUsingIt() {
        repeat(60) {
            clock += 60_000L
            idle.touch()
            assertFalse(idle.expired(LockAfter.FIVE))
        }
    }

    @Test
    fun aLongSleepCountsInFull() {
        // Nothing ticked while the app was away; the first look afterwards
        // must see the whole absence.
        clock += 3 * 60 * 60_000L
        assertTrue(idle.expired(LockAfter.HOUR))
        assertFalse(idle.expired(LockAfter.FOUR_HOURS))
    }

    @Test
    fun theSettingFallsBackToFifteenMinutes() {
        assertEquals(LockAfter.FIFTEEN, LockAfter.fromMinutes(null))
        assertEquals(LockAfter.FIFTEEN, LockAfter.fromMinutes(-1))
        assertEquals(LockAfter.FIFTEEN, LockAfter.fromMinutes(7))
        assertEquals(LockAfter.HOUR, LockAfter.fromMinutes(60))
    }

    @Test
    fun saysTheTimeTheWayTheWebDoes() {
        assertEquals("5 minutes", LockAfter.FIVE.words)
        assertEquals("1 hour", LockAfter.HOUR.words)
        assertEquals("4 hours", LockAfter.FOUR_HOURS.words)
    }
}
