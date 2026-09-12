package com.astraedus.nudge.domain.inapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suppression matrix for the "watch the one you were sent, then stop" allowance.
 *
 * Every case here is a decision about whether a Reels rule enforces, so every one of them is a way
 * the feature could become a hole through a block the user asked for. The cases that matter most are
 * the negatives: the Reels tab, a whole-app rule, and an unknown surface must all still enforce.
 */
class ReelPeekTest {

    private val reelsRule = listOf("REELS")

    private fun suppresses(
        allowSingleReel: Boolean = true,
        ruleFeatures: List<String>? = listOf("REELS"),
        detectedFeature: String? = "REELS",
        surface: ReelSurface? = ReelSurface.STANDALONE_PLAYER,
        peekSpent: Boolean = false
    ) = ReelPeek.suppresses(
        allowSingleReel = allowSingleReel,
        ruleFeatures = ruleFeatures,
        detectedFeature = detectedFeature,
        surface = surface,
        peekSpent = peekSpent
    )

    // ── The allowance itself ────────────────────────────────────────────────

    @Test
    fun `a reel opened from a DM plays while the peek is unspent`() {
        assertTrue(suppresses(surface = ReelSurface.STANDALONE_PLAYER, peekSpent = false))
    }

    @Test
    fun `the first swipe ends it`() {
        assertFalse(suppresses(surface = ReelSurface.STANDALONE_PLAYER, peekSpent = true))
    }

    @Test
    fun `the home feed is browsable`() {
        assertTrue(suppresses(surface = ReelSurface.HOME_FEED))
    }

    /** The feed is not a clip, so nothing about it is spendable — it stays open regardless. */
    @Test
    fun `the home feed does not depend on whether a peek was spent`() {
        assertTrue(suppresses(surface = ReelSurface.HOME_FEED, peekSpent = true))
    }

    // ── The things it must never reach ──────────────────────────────────────

    /**
     * The Reels TAB is the deliberate act the rule exists to stop, and it has no single clip to
     * bound an allowance with. If this ever returns true the feature blocks nothing at all.
     */
    @Test
    fun `the Reels tab is never allowed`() {
        assertFalse(suppresses(surface = ReelSurface.REELS_TAB))
        assertFalse(suppresses(surface = ReelSurface.REELS_TAB, peekSpent = true))
    }

    /**
     * Unverifiable is a third state, not a permission. A surface we could not identify means we
     * cannot tell a bounded peek from an unbounded session, so it enforces.
     */
    @Test
    fun `an unknown surface enforces`() {
        assertFalse(suppresses(surface = null))
    }

    /**
     * A whole-app rule answers "may I open Instagram at all". A peek must never become a route
     * around one, or turning this on would open the app for a user who blocked the whole thing.
     */
    @Test
    fun `a whole-app rule is never suppressed`() {
        assertFalse(suppresses(ruleFeatures = null))
        assertFalse(suppresses(ruleFeatures = emptyList()))
    }

    /** A rule scoped to some other feature has nothing to do with reels. */
    @Test
    fun `a rule scoped to another feature is untouched`() {
        assertFalse(suppresses(ruleFeatures = listOf("EXPLORE")))
    }

    /** A rule covering several features still gets its Reels half suppressed. */
    @Test
    fun `a multi-feature rule is suppressed on its Reels half`() {
        assertTrue(suppresses(ruleFeatures = listOf("REELS", "EXPLORE")))
    }

    /**
     * ...but not on its other half. The Explore surface reports EXPLORE, and a Reels allowance must
     * not quietly unblock Explore as a side effect.
     */
    @Test
    fun `a multi-feature rule still enforces on Explore`() {
        assertFalse(
            suppresses(
                ruleFeatures = listOf("REELS", "EXPLORE"),
                detectedFeature = "EXPLORE",
                surface = null
            )
        )
    }

    @Test
    fun `nothing is suppressed when the rule has not opted in`() {
        ReelSurface.entries.forEach { surface ->
            assertFalse(
                "surface $surface must enforce when allowSingleReel is off",
                suppresses(allowSingleReel = false, surface = surface)
            )
        }
    }

    /** Detection off (a whole-app foreground evaluation) must not be read as a reel surface. */
    @Test
    fun `no detected feature means no suppression`() {
        assertFalse(suppresses(detectedFeature = null))
    }

    @Test
    fun `another app's feature is not a reel`() {
        assertFalse(suppresses(ruleFeatures = reelsRule, detectedFeature = "SHORTS"))
    }
}
