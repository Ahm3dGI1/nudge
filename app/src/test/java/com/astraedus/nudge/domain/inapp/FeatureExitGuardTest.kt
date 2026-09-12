package com.astraedus.nudge.domain.inapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bound on "back out of the feature" enforcement.
 *
 * The failure this prevents is the loud one: `GLOBAL_ACTION_BACK` is a request, not a result, and a
 * surface that keeps reporting as blocked would otherwise produce Back on a loop until the user is
 * walked out of the app and then out of whatever they opened next. Every test here is really about
 * the fallback DIRECTION — when in doubt, hand the job to the block overlay, which is the behaviour
 * this feature is a softer alternative to.
 */
class FeatureExitGuardTest {

    private val guard = FeatureExitGuard(maxConsecutiveExits = 3, windowMs = 6_000L)
    private val key = "com.instagram.android:REELS"

    @Test
    fun `the first exit is allowed`() {
        assertTrue(guard.allowExit(key, nowMs = 0L))
    }

    @Test
    fun `consecutive exits are allowed up to the budget, then denied`() {
        assertTrue(guard.allowExit(key, 0L))
        assertTrue(guard.allowExit(key, 500L))
        assertTrue(guard.allowExit(key, 1_000L))

        assertFalse("the fourth in one window must fall back to the overlay", guard.allowExit(key, 1_500L))
    }

    /**
     * A denial must NOT refresh the window. The overlay swallows accessibility events while it is
     * up, so nothing else would be left to reset the guard — a self-refreshing denial would disable
     * the feature silently until the process died.
     */
    @Test
    fun `the window keeps ageing while exits are denied`() {
        repeat(3) { guard.allowExit(key, it * 100L) }
        assertFalse(guard.allowExit(key, 1_000L))
        assertFalse(guard.allowExit(key, 3_000L))

        // 6s after the last ALLOWED attempt (t=200), not after the denials.
        assertTrue("the guard must re-open on its own", guard.allowExit(key, 6_500L))
    }

    @Test
    fun `a gap longer than the window starts a fresh budget`() {
        repeat(3) { guard.allowExit(key, it * 100L) }
        assertFalse(guard.allowExit(key, 400L))

        assertTrue(guard.allowExit(key, 10_000L))
        assertTrue(guard.allowExit(key, 10_100L))
        assertTrue(guard.allowExit(key, 10_200L))
        assertFalse(guard.allowExit(key, 10_300L))
    }

    /**
     * The fast path back. Without it, three swipes spread over a long session would permanently
     * downgrade the rule to the overlay it was configured not to use.
     */
    @Test
    fun `clearing the feature hands the budget back immediately`() {
        repeat(3) { guard.allowExit(key, it * 100L) }
        assertFalse(guard.allowExit(key, 400L))

        guard.onFeatureCleared(key)

        assertTrue(guard.allowExit(key, 500L))
    }

    /** Being stuck on Reels must not spend the budget for Shorts. */
    @Test
    fun `budgets are per feature`() {
        val shorts = "com.google.android.youtube:SHORTS"
        repeat(3) { guard.allowExit(key, it * 100L) }
        assertFalse(guard.allowExit(key, 400L))

        assertTrue(guard.allowExit(shorts, 400L))
    }

    @Test
    fun `key is package and feature`() {
        assertTrue(guard.key("com.instagram.android", "REELS").contains("com.instagram.android"))
        assertTrue(guard.key("com.instagram.android", "REELS").contains("REELS"))
        assertTrue(
            "different features must not collide",
            guard.key("p", "REELS") != guard.key("p", "EXPLORE")
        )
    }

    @Test
    fun `reset drops every budget`() {
        repeat(3) { guard.allowExit(key, it * 100L) }
        assertFalse(guard.allowExit(key, 400L))

        guard.reset()

        assertTrue(guard.allowExit(key, 400L))
    }
}
