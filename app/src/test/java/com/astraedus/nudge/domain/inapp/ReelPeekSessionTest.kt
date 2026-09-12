package com.astraedus.nudge.domain.inapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arm / spend / disarm transitions of a peek.
 *
 * These are the bypass tests. Every "the peek is refunded" case below is one swipe away from being
 * an unbounded reels session, which is this repo's worst failure class — a blocker that silently
 * blocks nothing — so the negatives (re-detection does not re-arm, an unreadable screen changes
 * nothing) matter more than the happy path.
 */
class ReelPeekSessionTest {

    private val session = ReelPeekSession()

    @Test
    fun `a fresh session holds no peek`() {
        assertFalse(session.isInPlayer)
        assertFalse(session.isSpent)
    }

    @Test
    fun `arriving at the player arms a peek`() {
        assertTrue(session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER))

        assertTrue(session.isInPlayer)
        assertFalse(session.isSpent)
    }

    @Test
    fun `a swipe spends it`() {
        session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER)

        assertTrue(session.onReelScroll())
        assertTrue(session.isSpent)
    }

    /**
     * The load-bearing one. Detection re-runs roughly every two seconds for as long as the player is
     * open; if each pass re-armed, the allowance would refresh itself faster than anyone can scroll
     * and the feature would allow everything.
     */
    @Test
    fun `re-detecting the same player does not refund the peek`() {
        session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER)
        session.onReelScroll()

        repeat(5) { assertFalse(session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER)) }

        assertTrue("a re-detected player must stay spent", session.isSpent)
    }

    @Test
    fun `a second swipe changes nothing`() {
        session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER)
        session.onReelScroll()

        assertFalse("already spent", session.onReelScroll())
        assertTrue(session.isSpent)
    }

    /**
     * Leaving the player for a screen we positively identified is what earns the next clip its own
     * peek — a second reel in the same DM thread must play.
     */
    @Test
    fun `leaving the player and coming back arms a new peek`() {
        session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER)
        session.onReelScroll()

        // Back in the DM thread: a readable Instagram screen that is not a reel player.
        session.onSurfaceDetected(null)
        assertFalse(session.isInPlayer)

        assertTrue(session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER))
        assertFalse(session.isSpent)
    }

    @Test
    fun `every non-player surface disarms`() {
        listOf(ReelSurface.REELS_TAB, ReelSurface.HOME_FEED, null).forEach { surface ->
            session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER)

            assertFalse("$surface must not arm", session.onSurfaceDetected(surface))
            assertFalse("$surface must disarm", session.isInPlayer)
        }
    }

    /**
     * Scrolling the home feed or a DM thread must not be able to spend an allowance that is not in
     * use — otherwise the reel opened next would be blocked on arrival.
     */
    @Test
    fun `scrolling outside the player spends nothing`() {
        assertFalse(session.onReelScroll())
        assertFalse(session.isSpent)

        session.onSurfaceDetected(ReelSurface.HOME_FEED)

        assertFalse(session.onReelScroll())
        assertFalse(session.isSpent)
    }

    /**
     * The service simply does not call in when it could not read the node tree, and this class has
     * no timer of its own, so a spent peek survives any amount of silence. If it did not, endless
     * scrolling would be a matter of swiping and waiting.
     */
    @Test
    fun `a spent peek survives silence`() {
        session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER)
        session.onReelScroll()

        // No calls at all: an unreadable window, a burst of events we ignored, time passing.

        assertTrue(session.isSpent)
        assertTrue(session.isInPlayer)
    }

    @Test
    fun `reset drops everything`() {
        session.onSurfaceDetected(ReelSurface.STANDALONE_PLAYER)
        session.onReelScroll()

        session.reset()

        assertFalse(session.isInPlayer)
        assertFalse(session.isSpent)
    }
}
