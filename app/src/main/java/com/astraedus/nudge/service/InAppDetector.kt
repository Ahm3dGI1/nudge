package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.inapp.ReelSurface
import com.astraedus.nudge.util.NudgeLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects in-app features (Reels, Shorts, Explore) by inspecting the accessibility tree.
 *
 * Detection is best-effort -- apps change their UI frequently. When detection fails
 * we return null (no feature detected) rather than crashing, so the service falls back
 * to whole-app rule evaluation.
 */
@Singleton
class InAppDetector @Inject constructor(
    private val logger: NudgeLogger
) : InAppDetectorApi {

    /**
     * Signatures of surfaces already reported by [dumpViewIdsForDiagnosis], so each unrecognised
     * screen is logged once per process rather than once per accessibility event. Debug builds
     * only; bounded in practice by the handful of distinct screens these apps have.
     */
    private val loggedUnknownSurfaces = mutableSetOf<String>()

    /** Last time the debug harvest actually walked the tree; see [dumpViewIdsForDiagnosis]. */
    @Volatile
    private var lastDiagnosticMs = 0L

    enum class Feature(val displayName: String, val key: String) {
        REELS("Instagram Reels", "REELS"),
        SHORTS("YouTube Shorts", "SHORTS"),
        EXPLORE("Instagram Explore", "EXPLORE"),
        TIKTOK_FEED("TikTok Feed", "TIKTOK_FEED")
    }

    /**
     * What detection found: the [Feature] a rule matches on, and — for Instagram — the [ReelSurface]
     * it was reached from.
     *
     * The two are separate on purpose. Rules have always matched on the feature key alone and still
     * do; the surface answers a second question only the "watch the one you were sent" allowance
     * asks ([com.astraedus.nudge.domain.inapp.ReelPeek]), and is null for every app and every
     * surface that cannot answer it. A null surface must be read as "unknown", never as a value.
     */
    data class Detection(
        val feature: Feature,
        val surface: ReelSurface? = null
    )

    companion object {
        /**
         * The one package whose surfaces are classified into a [ReelSurface]. Named because the
         * accessibility service has to ask the same question about a scroll event, and a bare
         * literal in two files is a pair that can drift.
         */
        const val INSTAGRAM_PACKAGE = "com.instagram.android"

        /** Packages that support in-app feature detection. */
        val SUPPORTED_PACKAGES = setOf(
            INSTAGRAM_PACKAGE,
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )

        /** Cap for the debug-only view-id harvest; keeps the walk off the hot path's budget. */
        private const val DIAGNOSTIC_NODE_LIMIT = 800

        /** Minimum gap between debug harvest walks. Bounds the cost to ~1 tree walk per interval. */
        private const val DIAGNOSTIC_THROTTLE_MS = 5_000L

        /**
         * Containers unique to Instagram's full-screen reel player, harvested from a real device
         * (Galaxy S24 / Android 16) while watching a reel opened from a DM.
         *
         * Checked instead of the bottom-nav tabs because the player is hosted in a modal activity
         * with no nav bar. Verified absent from the home feed (which shows inline video under
         * `media_group` / `carousel_video_media_group`) and from a DM thread, so these do not
         * over-match ordinary browsing.
         *
         * More than one is listed because the player's tree varies between entry points — the
         * DM-opened variant additionally carries a reply bar. Any single match is sufficient.
         */
        private val INSTAGRAM_CLIPS_VIEWER_IDS = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_media_component"
        )

        /**
         * Instagram's bottom-navigation tabs, in the order [findActiveInstagramTab] probes them.
         *
         * Their mere PRESENCE is load-bearing separately from which one is selected: the reel player
         * opened from a DM or a share link is hosted in `com.instagram.modal.ModalActivity`, which
         * has no nav bar at all, so "clips containers and no tabs" is how a standalone player is
         * told apart from the Reels tab. See [classifyInstagram].
         */
        private val INSTAGRAM_TAB_IDS = listOf(
            "com.instagram.android:id/feed_tab",
            "com.instagram.android:id/clips_tab",
            "com.instagram.android:id/search_tab",
            "com.instagram.android:id/profile_tab"
        )

        /**
         * Scrollable containers whose scroll events mean "the user moved to the NEXT REEL", as
         * opposed to scrolling something layered over the player (the comment sheet, a caption).
         * Consumed by the accessibility service to spend a peek — see
         * [com.astraedus.nudge.domain.inapp.ReelPeekSession].
         *
         * Only `clips_viewer_view_pager` is confirmed from a device capture; the other two are
         * plausible siblings and cost nothing if Instagram never uses them, because an id that does
         * not exist simply never matches. What actually carries this decision is the null branch of
         * [isReelAdvanceScroll] — a swipe whose source we cannot identify still counts.
         */
        private val INSTAGRAM_CLIPS_SCROLLABLE_IDS = setOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_viewer_recycler_view",
            "com.instagram.android:id/clips_video_container"
        )

        /**
         * True when a scroll event coming from the view identified by [viewId] should be read as a
         * swipe to the next reel.
         *
         * A null [viewId] — an unidentified or unreadable scroll source — counts. The standalone
         * player IS a full-screen pager, so the overwhelmingly likely unidentified scroll inside it
         * is the swipe this exists to catch, and the failure direction has to be the safe one: a
         * missed swipe is an unbounded reels session, while a false positive costs the user one
         * early block on a clip they can re-open. Anything that IS identified and is not a clips
         * container (the comment sheet's recycler, say) does not count.
         */
        fun isReelAdvanceScroll(viewId: String?): Boolean =
            viewId == null || viewId in INSTAGRAM_CLIPS_SCROLLABLE_IDS
    }

    /** True if any of [viewIds] resolves in [root]. Nodes are recycled before returning. */
    private fun findsAnyViewId(root: AccessibilityNodeInfo, viewIds: List<String>): Boolean {
        for (id in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                recycleNodes(nodes)
                return true
            }
            recycleNodes(nodes)
        }
        return false
    }

    /**
     * Attempt to detect which in-app feature is active for the given package, and — for
     * Instagram — which surface it was reached from.
     *
     * @return The [Detection], or null if no specific feature is detected (user is in a non-feature
     *   part of the app, or detection failed).
     */
    override fun detect(packageName: String, rootNode: AccessibilityNodeInfo?): Detection? {
        if (rootNode == null) {
            logger.d("feature detection skipped package=$packageName reason=null_root")
            return null
        }
        return try {
            val detection = when (packageName) {
                INSTAGRAM_PACKAGE -> detectInstagram(rootNode)
                "com.google.android.youtube" -> detectYouTube(rootNode)?.let { Detection(it) }
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" ->
                    Detection(Feature.TIKTOK_FEED)
                else -> null
            }
            if (detection == null) dumpViewIdsForDiagnosis(packageName, rootNode)
            logger.d(
                "feature detection result package=$packageName " +
                    "feature=${detection?.feature} surface=${detection?.surface}"
            )
            detection
        } catch (e: Exception) {
            logger.w("feature detection failed package=$packageName", e)
            null
        }
    }

    /**
     * DIAGNOSTIC (debug builds only): log the distinct view IDs present when detection found
     * nothing, so an undetected surface can be identified from logcat.
     *
     * Exists because the usual external tools cannot see these surfaces: `uiautomator dump` waits
     * for an idle window and a continuously playing reel/short never idles, so it hangs and gets
     * Killed; `dumpsys activity top` times out on the same screens. The accessibility tree this
     * service already walks has no such constraint.
     *
     * Reads ONLY `viewIdResourceName` — never text or contentDescription, which on these screens
     * would be the user's private messages and captions. Bounded to [DIAGNOSTIC_NODE_LIMIT] nodes,
     * matching the bounded-harvest convention used by the Strict Mode escape guard.
     */
    private fun dumpViewIdsForDiagnosis(packageName: String, root: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        // Throttle the WALK, not just the logging. Detection fails on a firehose of content-change
        // events — ~800 times in three minutes of measured Instagram use — and each call would
        // otherwise BFS up to DIAGNOSTIC_NODE_LIMIT nodes on the accessibility hot path. A new
        // surface stays on screen for far longer than this interval, so nothing is missed.
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticMs < DIAGNOSTIC_THROTTLE_MS) return
        lastDiagnosticMs = now

        val ids = LinkedHashSet<String>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited < DIAGNOSTIC_NODE_LIMIT) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { ids += it }
            for (i in 0 until node.childCount) {
                queue += node.getChild(i) ?: continue
            }
        }
        // Log each DISTINCT surface once. Detection runs on a firehose of content-change events —
        // a measured ~800 failed detections in three minutes of Instagram use — so logging every
        // miss buries the signal. What matters is "which surfaces do we not recognise", and that
        // set is tiny.
        val signature = ids.joinToString(",")
        if (!loggedUnknownSurfaces.add(signature)) return
        logger.d("undetected surface package=$packageName nodes=$visited viewIds=$signature")
    }

    private fun detectInstagram(root: AccessibilityNodeInfo): Detection? {
        // Use resource IDs for reliable tab detection. Instagram's bottom nav tabs:
        //   feed_tab (Home), clips_tab (Reels), search_tab (Search/Explore), profile_tab (Profile)
        // The tab FrameLayout itself has selected=false, but its child tab_icon ImageView
        // has selected=true for the active tab.
        val activeTab = findActiveInstagramTab(root)
        val inClipsViewer = findsAnyViewId(root, INSTAGRAM_CLIPS_VIEWER_IDS)
        // Asked separately from [activeTab]: "no tab reads as selected" and "there is no nav bar at
        // all" are different facts, and only the second one identifies the modal player.
        val hasBottomNav = activeTab != null || findsAnyViewId(root, INSTAGRAM_TAB_IDS)
        logger.d(
            "instagram surface activeTab=$activeTab clipsViewer=$inClipsViewer nav=$hasBottomNav"
        )

        // Fallback: text-based detection for older Instagram versions. It carries no surface — a
        // screen we could not identify structurally cannot tell us where it was reached from.
        return classifyInstagram(activeTab, inClipsViewer, hasBottomNav)
            ?: detectInstagramByText(root)?.let { Detection(it) }
    }

    /**
     * Pure classification of an Instagram screen from three observations, extracted so the ORDERING
     * — which is the whole substance of this — is unit-testable without an accessibility tree.
     *
     * Every branch fails toward blocking:
     *
     * 1. **Reels tab selected** wins over everything. Its tree carries the clips containers too, so
     *    checking the player first (which is what this code did before the peek allowance existed)
     *    would classify the Reels tab itself as a standalone player and make the tab peek-eligible
     *    — a hole straight through the rule.
     * 2. **Clips containers with no nav bar** is the modal player: a reel opened from a DM, a share
     *    link or a profile. `com.instagram.modal.ModalActivity` has no bottom nav, which is exactly
     *    why tab-based detection could not see it even in principle before 2026-08-08, and reels
     *    scrolled forever past a HARD_BLOCK.
     * 3. **Clips containers over the home feed** (nav bar present, Home still selected) is a reel
     *    tapped out of the feed — the other entry point a peek covers.
     * 4. **Clips containers we cannot place** (a nav bar is there but no tab reads as selected) is
     *    treated as the Reels tab. Unverifiable is not an allowance: a peek we cannot bound is an
     *    unbounded reels session.
     *
     * Returns null when nothing recognisable was found, so the caller can fall back to the
     * text-based detection older Instagram builds need.
     */
    internal fun classifyInstagram(
        activeTab: String?,
        inClipsViewer: Boolean,
        hasBottomNav: Boolean
    ): Detection? = when {
        activeTab == "clips_tab" -> Detection(Feature.REELS, ReelSurface.REELS_TAB)
        inClipsViewer && !hasBottomNav -> Detection(Feature.REELS, ReelSurface.STANDALONE_PLAYER)
        inClipsViewer && activeTab == "feed_tab" ->
            Detection(Feature.REELS, ReelSurface.STANDALONE_PLAYER)
        inClipsViewer -> Detection(Feature.REELS, ReelSurface.REELS_TAB)
        activeTab == "search_tab" -> Detection(Feature.EXPLORE)
        // Home feed = reels-equivalent: an infinite scroll of the same material, and what the
        // interaction counter has always counted. A peek allowance is what opts out of it.
        activeTab == "feed_tab" -> Detection(Feature.REELS, ReelSurface.HOME_FEED)
        else -> null
    }

    /**
     * Find which Instagram bottom nav tab is active by checking resource IDs.
     * Returns the tab ID suffix (e.g. "feed_tab", "clips_tab") or null if not found.
     */
    private fun findActiveInstagramTab(root: AccessibilityNodeInfo): String? {
        // Derived from INSTAGRAM_TAB_IDS rather than a second hand-written list: the same four tabs
        // now answer two questions (which one is selected, and whether a nav bar exists at all), and
        // two copies of the set could only ever drift.
        for (viewId in INSTAGRAM_TAB_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes.isNotEmpty()) {
                for (node in nodes) {
                    if (isTabActive(node)) {
                        recycleNodes(nodes)
                        return viewId.substringAfterLast('/')
                    }
                }
                recycleNodes(nodes)
            }
        }
        return null
    }

    /**
     * Check if a tab node is active by looking for selected=true on the node
     * itself or any of its descendants (up to 3 levels deep).
     */
    private fun isTabActive(node: AccessibilityNodeInfo): Boolean {
        if (node.isSelected) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isSelected) return true
            // Check grandchildren too
            for (j in 0 until child.childCount) {
                val grandchild = child.getChild(j) ?: continue
                if (grandchild.isSelected) return true
            }
        }
        return false
    }

    /** Fallback text-based detection for older Instagram versions. */
    private fun detectInstagramByText(root: AccessibilityNodeInfo): Feature? {
        val reelsNodes = root.findAccessibilityNodeInfosByText("Reels")
        if (reelsNodes.isNotEmpty()) {
            for (node in reelsNodes) {
                if (node.isSelected || isInSelectedTab(node)) {
                    recycleNodes(reelsNodes)
                    return Feature.REELS
                }
            }
        }
        recycleNodes(reelsNodes)

        val exploreNodes = root.findAccessibilityNodeInfosByText("Explore")
        if (exploreNodes.isNotEmpty()) {
            for (node in exploreNodes) {
                if (node.isSelected || isInSelectedTab(node)) {
                    recycleNodes(exploreNodes)
                    return Feature.EXPLORE
                }
            }
        }
        recycleNodes(exploreNodes)

        return null
    }

    private fun detectYouTube(root: AccessibilityNodeInfo): Feature? {
        // Method 1: Check if Shorts tab is selected (user navigated via bottom tab)
        val shortsNodes = root.findAccessibilityNodeInfosByText("Shorts")
        if (shortsNodes.isNotEmpty()) {
            for (node in shortsNodes) {
                if (node.isSelected || isInSelectedTab(node) || hasSelectedChild(node)) {
                    recycleNodes(shortsNodes)
                    return Feature.SHORTS
                }
            }
        }
        recycleNodes(shortsNodes)

        // Method 2: Check for Shorts player container (user tapped a Short from home feed)
        val reelRecycler = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/reel_recycler"
        )
        if (reelRecycler.isNotEmpty()) {
            recycleNodes(reelRecycler)
            return Feature.SHORTS
        }

        // Method 3: Check for reel player page (another common Shorts container ID)
        val reelPlayer = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/reel_player_page_container"
        )
        if (reelPlayer.isNotEmpty()) {
            recycleNodes(reelPlayer)
            return Feature.SHORTS
        }

        return null
    }

    /**
     * Walk up the parent chain to check if any ancestor is marked as selected.
     * This handles cases where the tab text itself is not selected but its container is.
     */
    private fun isInSelectedTab(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isSelected) return true
            val next = current.parent
            current = next
            depth++
        }
        return false
    }

    /**
     * Check if any immediate child of the node is selected.
     * Instagram sets selected=true on the child tab_icon ImageView, not the
     * parent FrameLayout that carries the content-description.
     */
    private fun hasSelectedChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isSelected) return true
        }
        return false
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Exception) {
                // Already recycled -- ignore
            }
        }
    }
}
