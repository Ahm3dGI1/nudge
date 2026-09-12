package com.astraedus.nudge.domain.inapp

/**
 * The "watch the one you were sent, then stop" allowance for a Reels rule.
 *
 * ## What it is for
 *
 * A REELS rule is one switch answering two very different questions: *may I be pointed at a clip*
 * and *may I graze*. Blocking both is correct by default and is what every rule written before this
 * did. But the reel a friend sends in a DM is a message, and blocking it makes Instagram unusable
 * for messaging — which is why the app's own detector already had to be careful not to fire on a DM
 * thread. `BlockRule.allowSingleReel` splits the two questions apart: the clip the user was pointed
 * at plays, and the swipe that turns it into a feed does not.
 *
 * ## The rules, and which way each one fails
 *
 * - It only ever bites on a **feature-scoped REELS rule**. A whole-app rule is answering a different
 *   question ("may I open Instagram at all") and is never suppressed here, so a peek can never be a
 *   route around a whole-app block.
 * - [ReelSurface.REELS_TAB] is **never** suppressed. Opening the Reels tab is the deliberate act the
 *   rule exists to stop, and it has no "one clip" to bound an allowance with.
 * - [ReelSurface.STANDALONE_PLAYER] is suppressed until the peek is SPENT — see [ReelPeekSession],
 *   which spends it on the first swipe.
 * - [ReelSurface.HOME_FEED] is suppressed outright. Allowing a friend's reel while blocking the feed
 *   it sits in would be incoherent, and the user opting into this is opting into a browsable feed.
 * - A **null** surface is not a surface. It means detection could not tell us where we are (an
 *   unsupported app, an Instagram build whose tree we do not recognise), and it enforces. That is
 *   the deliberate failure direction throughout this file: an unverifiable peek is an unbounded
 *   reels session, and this repo's worst failure class is a blocker that silently blocks nothing.
 */
object ReelPeek {

    /** The feature key a peek can apply to. Reels is the only surface with an entry point to bound. */
    const val FEATURE_KEY: String = "REELS"

    /**
     * True when [allowSingleReel] means this rule must NOT enforce on the surface in front of the
     * user right now, so the caller should drop it before the block engine ever sees it.
     *
     * @param allowSingleReel the rule's own `allowSingleReel` flag.
     * @param ruleFeatures the rule's `inAppFeatures`; null/empty = a whole-app rule, never suppressed.
     * @param detectedFeature the feature detection reported for the current screen.
     * @param surface where that feature was reached from; null = unknown, which enforces.
     * @param peekSpent whether the user has already swiped past the clip they were pointed at.
     */
    fun suppresses(
        allowSingleReel: Boolean,
        ruleFeatures: List<String>?,
        detectedFeature: String?,
        surface: ReelSurface?,
        peekSpent: Boolean
    ): Boolean {
        if (!allowSingleReel) return false
        if (detectedFeature != FEATURE_KEY) return false
        // Whole-app rules answer "may I open Instagram", not "may I watch reels". A peek must never
        // become a way past one.
        if (ruleFeatures.isNullOrEmpty()) return false
        if (FEATURE_KEY !in ruleFeatures) return false

        return when (surface) {
            ReelSurface.HOME_FEED -> true
            ReelSurface.STANDALONE_PLAYER -> !peekSpent
            // The Reels tab is the thing being blocked; an unknown surface is not evidence of
            // anything and must not buy an allowance.
            ReelSurface.REELS_TAB, null -> false
        }
    }
}
