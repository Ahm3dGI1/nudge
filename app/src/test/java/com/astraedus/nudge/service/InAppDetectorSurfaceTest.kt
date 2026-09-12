package com.astraedus.nudge.service

import com.astraedus.nudge.domain.inapp.ReelSurface
import com.astraedus.nudge.util.NudgeLogger
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Instagram surface a screen is, given what the accessibility tree showed.
 *
 * This is the ordering [InAppDetector.classifyInstagram] encodes, tested as values rather than
 * through a mocked node tree, because the ordering IS the substance: the clips containers are
 * present on the Reels tab too, so "check the player first" — which is what the code did before the
 * peek allowance existed — would classify the Reels tab itself as a peek-eligible standalone player
 * and put a hole straight through the rule.
 */
class InAppDetectorSurfaceTest {

    private val detector = InAppDetector(mockk<NudgeLogger>(relaxed = true))

    private fun classify(
        activeTab: String? = null,
        inClipsViewer: Boolean = false,
        hasBottomNav: Boolean = activeTab != null
    ) = detector.classifyInstagram(activeTab, inClipsViewer, hasBottomNav)

    // ── The Reels tab ───────────────────────────────────────────────────────

    /**
     * The tab's tree carries the clips containers as well, so the selected tab has to win. If this
     * ever reports STANDALONE_PLAYER, a peek covers the endless feed and the rule stops mattering.
     */
    @Test
    fun `the Reels tab is the feed even though the player containers are there`() {
        assertEquals(
            InAppDetector.Detection(InAppDetector.Feature.REELS, ReelSurface.REELS_TAB),
            classify(activeTab = "clips_tab", inClipsViewer = true)
        )
    }

    // ── The standalone player ───────────────────────────────────────────────

    /**
     * A reel from a DM or a share link runs in `com.instagram.modal.ModalActivity`, which has no
     * bottom nav at all. "Clips containers and no nav bar" is the whole signature.
     */
    @Test
    fun `the modal player with no nav bar is standalone`() {
        assertEquals(
            InAppDetector.Detection(InAppDetector.Feature.REELS, ReelSurface.STANDALONE_PLAYER),
            classify(activeTab = null, inClipsViewer = true, hasBottomNav = false)
        )
    }

    /** A reel tapped out of the home feed: the nav bar is still there, Home is still selected. */
    @Test
    fun `the player over the home feed is standalone`() {
        assertEquals(
            InAppDetector.Detection(InAppDetector.Feature.REELS, ReelSurface.STANDALONE_PLAYER),
            classify(activeTab = "feed_tab", inClipsViewer = true)
        )
    }

    /**
     * A nav bar is present but no tab reads as selected. We cannot tell the Reels tab from a player
     * opened over some other tab, and an allowance we cannot bound is an unbounded session — so this
     * enforces as the feed.
     */
    @Test
    fun `a player we cannot place is treated as the feed`() {
        assertEquals(
            InAppDetector.Detection(InAppDetector.Feature.REELS, ReelSurface.REELS_TAB),
            classify(activeTab = null, inClipsViewer = true, hasBottomNav = true)
        )
    }

    /** The player opened from a profile: same modal, reached from the profile tab. */
    @Test
    fun `the player over any other tab is not standalone unless it is the feed or modal`() {
        assertEquals(
            InAppDetector.Detection(InAppDetector.Feature.REELS, ReelSurface.REELS_TAB),
            classify(activeTab = "profile_tab", inClipsViewer = true)
        )
    }

    // ── Everything else keeps working ───────────────────────────────────────

    @Test
    fun `the home feed is reels-equivalent and says so`() {
        assertEquals(
            InAppDetector.Detection(InAppDetector.Feature.REELS, ReelSurface.HOME_FEED),
            classify(activeTab = "feed_tab")
        )
    }

    /** Explore carries no surface: a peek is a Reels concept and must not reach it. */
    @Test
    fun `Explore carries no surface`() {
        assertEquals(
            InAppDetector.Detection(InAppDetector.Feature.EXPLORE),
            classify(activeTab = "search_tab")
        )
    }

    @Test
    fun `the profile tab is not a feature`() {
        assertNull(classify(activeTab = "profile_tab"))
    }

    @Test
    fun `an unrecognised screen falls through to the text fallback`() {
        assertNull(classify())
    }

    // ── Which scrolls end a peek ────────────────────────────────────────────

    @Test
    fun `a scroll from the clips pager advances the reel`() {
        assertTrue(
            InAppDetector.isReelAdvanceScroll("com.instagram.android:id/clips_viewer_view_pager")
        )
    }

    /**
     * An unidentified scroll source counts. The standalone player IS a full-screen pager, so the
     * likely unidentified scroll inside it is the swipe this exists to catch; a missed swipe is an
     * unbounded reels session, while a false positive costs one early block on a re-openable clip.
     */
    @Test
    fun `an unreadable scroll source counts as a swipe`() {
        assertTrue(InAppDetector.isReelAdvanceScroll(null))
    }

    /**
     * Something layered over the player — the comment sheet — must not end the peek, or reading the
     * replies on the clip you were sent would block you.
     */
    @Test
    fun `a scroll from something else does not`() {
        assertFalse(InAppDetector.isReelAdvanceScroll("com.instagram.android:id/comment_thread_recycler_view"))
        assertFalse(InAppDetector.isReelAdvanceScroll("com.instagram.android:id/message_list"))
    }
}
