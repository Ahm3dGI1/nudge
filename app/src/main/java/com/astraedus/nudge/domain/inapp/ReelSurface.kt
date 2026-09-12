package com.astraedus.nudge.domain.inapp

/**
 * WHERE a short-form video surface was reached from, as distinct from WHAT it is.
 *
 * `InAppDetector.Feature.REELS` answers "is the user looking at reels"; a rule matches on that and
 * nothing else. This answers the second question a "let me watch the one my friend sent me" rule
 * needs: is this an endless feed the user opened on purpose, or a single clip they were pointed at?
 *
 * Only Instagram populates this today. Everything else reports `null`, which every consumer must
 * read as "we do not know" — never as any particular surface. See
 * `docs/architecture/rules-and-features.md`.
 */
enum class ReelSurface {
    /**
     * The Reels TAB: the infinite-scroll feed reached from the bottom nav. Opening it is a
     * deliberate act, so a peek allowance never applies here.
     */
    REELS_TAB,

    /**
     * The full-screen reel PLAYER reached from somewhere else — a DM, a share link, a profile, or a
     * tap on a reel in the home feed. It starts on one specific clip, which is what makes a
     * bounded "watch this one, then stop" allowance expressible.
     */
    STANDALONE_PLAYER,

    /**
     * Instagram's HOME FEED. Not a reel player at all, but it is treated as REELS-equivalent by
     * default because it is an infinite scroll of the same material, and the interaction counter
     * has always counted it as such.
     */
    HOME_FEED
}
