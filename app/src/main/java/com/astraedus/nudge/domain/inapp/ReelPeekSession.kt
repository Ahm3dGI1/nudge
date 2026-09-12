package com.astraedus.nudge.domain.inapp

/**
 * Tracks the single reel a [ReelPeek] allowance covers: whether one is currently open, and whether
 * the user has already swiped past it.
 *
 * Pure state, no Android and no clock — every transition is driven by something the accessibility
 * service observed, so the whole thing is JVM-testable and the failure directions are visible in one
 * place. Confined to the accessibility event thread (which is where every caller runs); nothing here
 * is synchronised.
 *
 * ## The three transitions, and why each is shaped the way it is
 *
 * - **Arm** — a readable screen showing [ReelSurface.STANDALONE_PLAYER] when we were not already in
 *   one. Arriving at the player *from outside it* is what a fresh clip looks like, so this is what
 *   grants a new peek. Re-detecting the same player does NOT re-arm; otherwise the 2-second
 *   detection cadence would hand out a new allowance every two seconds and the feature would allow
 *   everything.
 * - **Spend** — [onReelScroll]. One swipe turns "the clip I was sent" into "a feed", which is the
 *   exact thing the rule is there to stop. There is no counting: the allowance is one clip.
 * - **Disarm** — [onSurfaceDetected] with anything else. A readable screen that is not the player
 *   (a DM thread, the home feed, the Reels tab, a profile) means the user left it, so the next
 *   arrival is a new clip and earns a new peek. This is what lets a second reel in the same DM
 *   thread play after the first one was spent.
 *
 * ## What deliberately does NOT change anything
 *
 * An UNREADABLE screen. The service simply does not call in when it could not read the node tree,
 * and this class has no timer that could expire an armed peek on its own. A spent peek therefore
 * stays spent until a surface is positively identified as something else — because "we lost sight of
 * the tree for a moment" must never read as "the user left the player", which would refund the peek
 * and make endless scrolling a matter of swiping and waiting.
 *
 * The accepted cost of that direction: if the player's own tree momentarily comes back without its
 * containers mid-session, the peek is refunded and the user gets one extra clip. One extra reel is a
 * far smaller failure than an unbounded feed.
 */
class ReelPeekSession {

    /** True while we believe a standalone reel player is on screen. */
    var isInPlayer: Boolean = false
        private set

    /**
     * True when the peek covering the currently-open clip has been used up. Meaningless (and always
     * false) while [isInPlayer] is false, since arming resets it.
     */
    var isSpent: Boolean = false
        private set

    /**
     * Report the surface a readable screen was identified as. Pass null for "detection ran and found
     * no reel player" — that is a real observation and disarms. Do NOT call at all when the node
     * tree could not be read; see the class doc.
     *
     * @return true if this call armed a FRESH peek, for logging.
     */
    fun onSurfaceDetected(surface: ReelSurface?): Boolean {
        if (surface != ReelSurface.STANDALONE_PLAYER) {
            isInPlayer = false
            isSpent = false
            return false
        }
        if (isInPlayer) return false
        isInPlayer = true
        isSpent = false
        return true
    }

    /**
     * The user scrolled inside the reel player. Spends the peek.
     *
     * A no-op when no player is open, so scrolling the home feed or a DM thread can never spend an
     * allowance that is not being used.
     *
     * @return true if this call spent a live peek, for logging.
     */
    fun onReelScroll(): Boolean {
        if (!isInPlayer || isSpent) return false
        isSpent = true
        return true
    }

    /**
     * Drop everything: no player open, no peek spent.
     *
     * Deliberately NOT called when the user switches away from the app. Leaving and coming back is
     * the obvious way to try to refund a spent peek ("swipe, get blocked, tab out, tab in, free
     * reel"), and a spent peek must survive it. What legitimately refunds one is arriving at the
     * player from a screen we positively identified as something else — see [onSurfaceDetected].
     *
     * The caller is the master toggle going off, where every other piece of live enforcement state
     * is neutralized too: a disabled Nudge must not come back holding a stale "already swiped".
     */
    fun reset() {
        isInPlayer = false
        isSpent = false
    }
}
