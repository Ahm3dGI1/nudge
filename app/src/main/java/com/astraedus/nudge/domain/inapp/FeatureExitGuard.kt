package com.astraedus.nudge.domain.inapp

import java.util.concurrent.ConcurrentHashMap

/**
 * Bounds how many times in a row a block may be enforced by backing out of a feature
 * ([com.astraedus.nudge.data.db.entity.BlockRule.exitFeatureOnBlock]) before giving up and letting
 * the block overlay do it instead.
 *
 * ## Why a guard at all
 *
 * "Press Back" is a REQUEST, not a result. Nothing guarantees the app honours it, and nothing
 * guarantees the screen we land on is not also blocked — backing out of the Reels tab lands on the
 * home feed, which a Reels rule may itself treat as reels. Without a bound, a surface that keeps
 * reporting as blocked produces Back, Back, Back on a loop: at best a thrashing screen, at worst
 * Back walks the user out of the app entirely and then out of whatever they opened next. That is
 * both more destructive than the overlay it replaced and completely invisible to the user.
 *
 * So a failure to escape degrades to the overlay, which is the behaviour this feature is a softer
 * alternative to. The fallback direction is the whole point: the worst case is the OLD behaviour,
 * never an unbounded stream of global Back actions.
 *
 * ## How the window works
 *
 * Attempts within [windowMs] of one another are consecutive. Reaching [maxConsecutiveExits] denies
 * further exits — but a denial deliberately does NOT refresh the timestamp, so the window keeps
 * ageing and the guard re-opens on its own once the block stops arriving. That matters because the
 * overlay swallows accessibility events while it is up, so nothing would otherwise be left to reset
 * it, and a permanently-closed guard would silently disable the feature until the process died.
 *
 * [onFeatureCleared] is the fast path back: the feature stopped blocking, so the exit worked.
 *
 * Keyed by package + feature, so being stuck on Reels never spends the budget for Shorts. The map is
 * bounded by the number of rules that opt in — a handful — and is a [ConcurrentHashMap] because the
 * evaluation coroutine writes it off the accessibility event thread.
 */
class FeatureExitGuard(
    private val maxConsecutiveExits: Int = 3,
    private val windowMs: Long = 6_000L
) {

    private data class Attempts(val count: Int, val lastAtMs: Long)

    private val attempts = ConcurrentHashMap<String, Attempts>()

    /** The key a package's feature is tracked under. */
    fun key(packageName: String, featureKey: String): String = "$packageName:$featureKey"

    /**
     * May we enforce this block by backing out of the feature? Records the attempt when it returns
     * true; when it returns false the caller must fall back to the block overlay.
     */
    fun allowExit(key: String, nowMs: Long): Boolean {
        val prior = attempts[key]
        val consecutive = when {
            prior == null -> 0
            nowMs - prior.lastAtMs > windowMs -> 0
            else -> prior.count
        }
        if (consecutive >= maxConsecutiveExits) return false
        attempts[key] = Attempts(consecutive + 1, nowMs)
        return true
    }

    /** The feature is no longer blocking, so the exit worked. Next time starts from a clean budget. */
    fun onFeatureCleared(key: String) {
        attempts.remove(key)
    }

    /** Test seam / global-disable reset. */
    fun reset() {
        attempts.clear()
    }
}
