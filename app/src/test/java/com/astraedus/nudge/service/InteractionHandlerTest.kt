package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.logging.NudgeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InteractionHandlerTest {

    private lateinit var tracker: InteractionTracker
    private lateinit var overlayManager: FakeCounterOverlayManager
    private lateinit var inAppDetector: FakeInAppDetector
    private lateinit var timeRemainingHandler: FakeTimeRemainingHandler
    private lateinit var counterCache: CounterCacheRefresher
    private lateinit var handler: InteractionHandler

    /**
     * How many times the kick asked to send the user home. The Android "how" (global action, HOME
     * intent) is injected by the service, so the policy under test stays JVM-pure.
     */
    private var goHomeCount = 0

    @Before
    fun setUp() {
        tracker = InteractionTracker()
        overlayManager = FakeCounterOverlayManager()
        inAppDetector = FakeInAppDetector()
        timeRemainingHandler = FakeTimeRemainingHandler()
        counterCache = CounterCacheRefresher()
        goHomeCount = 0

        handler = InteractionHandler(
            interactionTracker = tracker,
            counterOverlayManager = overlayManager,
            inAppDetector = inAppDetector,
            timeRemainingHandler = timeRemainingHandler,
            counterCache = counterCache,
            logger = NudgeLog.NoOp,
            autoKickExecutor = AutoKickExecutor(
                interactionTracker = tracker,
                counterOverlayManager = overlayManager,
                counterCache = counterCache,
                logger = NudgeLog.NoOp,
                goHome = { goHomeCount++ }
            )
        )
    }

    private fun enablePackage(
        packageName: String,
        autoKickAfter: Int? = null,
        showTimeRemaining: Boolean = false,
        dailyLimitMinutes: Int? = null,
        autoKickCooldownSeconds: Int = 60,
        showCounter: Boolean = true
    ) {
        val entry = CounterCacheEntry(
            showCounter = showCounter,
            autoKickAfter = autoKickAfter,
            showTimeRemaining = showTimeRemaining,
            dailyLimitMinutes = dailyLimitMinutes,
            autoKickCooldownSeconds = autoKickCooldownSeconds
        )
        enablePackages(mapOf(packageName to entry))
    }

    private fun enablePackages(packages: Map<String, CounterCacheEntry>) {
        kotlinx.coroutines.runBlocking {
            counterCache.forceRefresh { packages }
        }
    }

    // --- handleViewClicked tests ---

    @Test
    fun `handleViewClicked increments session count for non-supported packages`() {
        enablePackage("com.example.notes")

        handler.handleViewClicked("com.example.notes")

        assertEquals(1, tracker.getSessionCount("com.example.notes"))
        assertEquals(1, overlayManager.lastSessionCount)
        assertEquals("taps", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewClicked skips supported packages - Instagram`() {
        enablePackage("com.instagram.android")

        handler.handleViewClicked("com.instagram.android")

        assertEquals(0, tracker.getSessionCount("com.instagram.android"))
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewClicked skips supported packages - YouTube`() {
        enablePackage("com.google.android.youtube")

        handler.handleViewClicked("com.google.android.youtube")

        assertEquals(0, tracker.getSessionCount("com.google.android.youtube"))
    }

    @Test
    fun `handleViewClicked skips supported packages - TikTok`() {
        enablePackage("com.zhiliaoapp.musically")

        handler.handleViewClicked("com.zhiliaoapp.musically")

        assertEquals(0, tracker.getSessionCount("com.zhiliaoapp.musically"))
    }

    @Test
    fun `handleViewClicked respects debounce - rapid clicks ignored`() {
        enablePackage("com.example.notes")

        // First click goes through
        handler.handleViewClicked("com.example.notes")
        // Second click immediately after (within 300ms debounce) -- ignored
        handler.handleViewClicked("com.example.notes")

        assertEquals(1, tracker.getSessionCount("com.example.notes"))
        assertEquals(1, overlayManager.lastSessionCount)
    }

    @Test
    fun `handleViewClicked does nothing when package not in cache`() {
        handler.handleViewClicked("com.example.notes")

        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewClicked shows overlay on first interaction`() {
        enablePackage("com.example.notes")

        handler.handleViewClicked("com.example.notes")

        assertTrue(overlayManager.visible)
        assertEquals("taps", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewClicked calls timeRemainingHandler maybeUpdate`() {
        enablePackage("com.example.notes")

        handler.handleViewClicked("com.example.notes")

        assertEquals("com.example.notes", timeRemainingHandler.lastMaybeUpdatePackage)
    }

    // --- handleViewScrolled tests ---

    @Test
    fun `handleViewScrolled increments count for supported packages with detected feature`() {
        enablePackage("com.instagram.android")
        // Pre-set activeReelLabel to skip AccessibilityNodeInfo detection in JVM tests
        handler.activeReelLabel = "reels"

        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals(1, tracker.getSessionCount("com.instagram.android"))
        assertEquals("reels", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewScrolled skips non-supported packages`() {
        enablePackage("com.example.notes")

        handler.handleViewScrolled("com.example.notes") { null }

        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewScrolled does nothing when no feature detected`() {
        enablePackage("com.instagram.android")
        inAppDetector.featureToReturn = null

        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals(0, tracker.getSessionCount("com.instagram.android"))
    }

    @Test
    fun `handleViewScrolled returns early for EXPLORE feature`() {
        enablePackage("com.instagram.android")
        inAppDetector.featureToReturn = InAppDetector.Feature.EXPLORE

        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals(0, tracker.getSessionCount("com.instagram.android"))
    }

    @Test
    fun `handleViewScrolled detects YouTube Shorts via cached label`() {
        enablePackage("com.google.android.youtube")
        handler.activeReelLabel = "shorts"

        handler.handleViewScrolled("com.google.android.youtube") { null }

        assertEquals(1, tracker.getSessionCount("com.google.android.youtube"))
        assertEquals("shorts", overlayManager.lastShowLabel)
    }

    @Test
    fun `handleViewScrolled detects TikTok feed via cached label`() {
        enablePackage("com.zhiliaoapp.musically")
        handler.activeReelLabel = "videos"

        handler.handleViewScrolled("com.zhiliaoapp.musically") { null }

        assertEquals(1, tracker.getSessionCount("com.zhiliaoapp.musically"))
        assertEquals("videos", overlayManager.lastShowLabel)
    }

    @Test
    fun `activeReelLabel caches feature label and skips detection on subsequent scrolls`() {
        enablePackage("com.instagram.android")

        // Simulate that feature was previously detected by setting the cache
        handler.activeReelLabel = "reels"

        // Scroll should use cached label without calling detectFeature
        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals("reels", handler.activeReelLabel)
        assertEquals(1, tracker.getSessionCount("com.instagram.android"))
        // Detector was never called (rootNodeProvider returns null but label was cached)
        assertEquals(0, inAppDetector.detectCallCount)
    }

    @Test
    fun `handleViewScrolled does nothing when package not in cache`() {
        handler.handleViewScrolled("com.instagram.android") { null }

        assertEquals(0, tracker.getSessionCount("com.instagram.android"))
    }

    // --- onAppChanged tests ---

    @Test
    fun `onAppChanged resets interaction tracker state`() {
        enablePackage("com.example.notes")
        handler.handleViewClicked("com.example.notes")
        assertEquals(1, tracker.getSessionCount("com.example.notes"))

        handler.onAppChanged("com.example.other")

        assertEquals(0, tracker.getSessionCount("com.example.other"))
    }

    // --- counter not shown when session is zero ---

    @Test
    fun `counterNotShownOnAppEntryWhenSessionCountIsZero`() {
        enablePackage("com.example.notes")

        // Enter the app for the first time -- session count is 0
        handler.onAppChanged("com.example.notes")

        // Counter overlay should NOT be shown (session count is 0)
        assertFalse(overlayManager.visible)
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `counter shown on app entry when session count is positive`() {
        enablePackage("com.example.notes")

        // Record some interactions first
        handler.onAppChanged("com.example.notes")
        handler.handleViewClicked("com.example.notes")
        handler.handleViewClicked("com.example.notes")
        assertTrue(overlayManager.visible)

        // Switch away (hide overlay), then come back
        overlayManager.visible = false
        overlayManager.lastShowLabel = null
        handler.onAppChanged("com.example.other")

        // Return to notes -- session count persists (within expiry), overlay shows
        handler.onAppChanged("com.example.notes")

        assertTrue(overlayManager.visible)
        assertEquals("taps", overlayManager.lastShowLabel)
    }

    // --- counter is gated on showCounter, not on cache membership ---

    @Test
    fun `a package tracked only for a time-based kick gets no counter overlay`() {
        // Regression guard for the split between "this package is tracked" and "the user wants the
        // counter". A time-based auto-kick puts a package in the cache with showCounter = false;
        // if the interaction paths keyed off cache membership, enabling "kick after 30 minutes"
        // would silently switch on a floating tap counter nobody asked for.
        enablePackage("com.example.notes", showCounter = false, autoKickAfter = null)

        handler.handleViewClicked("com.example.notes")
        handler.handleContentChanged("com.example.notes")

        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertFalse(overlayManager.visible)
        assertNull(overlayManager.lastShowLabel)
    }

    @Test
    fun `session bookkeeping still runs for a package without a counter`() {
        // ...but the session boundary itself must still be tracked, because the time-based trigger
        // depends on it.
        enablePackage("com.example.notes", showCounter = false)

        handler.onAppChanged("com.example.notes")
        tracker.setSessionUsageBaseline("com.example.notes", 5_000L)
        handler.onAppChanged("com.example.other")

        assertFalse(overlayManager.visible)
        assertEquals(5_000L, tracker.getSessionUsageBaseline("com.example.notes"))
    }

    // --- auto-kick goes through the shared executor ---

    @Test
    fun `interaction auto-kick sends home, arms the cooldown and resets the session`() {
        enablePackage(
            "com.example.notes",
            autoKickAfter = 1,
            autoKickCooldownSeconds = 90
        )

        handler.handleViewClicked("com.example.notes")

        assertEquals(1, goHomeCount)
        assertEquals(0, tracker.getSessionCount("com.example.notes"))
        assertTrue(tracker.isInCooldown("com.example.notes"))
        assertFalse(overlayManager.visible)
    }

    @Test
    fun `interaction auto-kick with no cooldown configured arms none`() {
        enablePackage(
            "com.example.notes",
            autoKickAfter = 1,
            autoKickCooldownSeconds = 0
        )

        handler.handleViewClicked("com.example.notes")

        assertEquals(1, goHomeCount)
        assertFalse(tracker.isInCooldown("com.example.notes"))
    }

    @Test
    fun `no auto-kick below the interaction threshold`() {
        enablePackage("com.example.notes", autoKickAfter = 5)

        handler.handleViewClicked("com.example.notes")

        assertEquals(0, goHomeCount)
        assertEquals(1, tracker.getSessionCount("com.example.notes"))
    }

    // --- hideCounter tests ---

    @Test
    fun `hideCounter hides overlay when visible`() {
        enablePackage("com.example.notes")
        handler.handleViewClicked("com.example.notes")
        assertTrue(overlayManager.visible)

        handler.hideCounter()

        assertFalse(overlayManager.visible)
    }

    @Test
    fun `hideCounter does nothing when overlay not visible`() {
        handler.hideCounter()
        assertFalse(overlayManager.visible)
    }

    // --- Test doubles ---

    private class FakeCounterOverlayManager : CounterOverlayManagerApi {
        var visible = false
        var lastShowLabel: String? = null
        var lastSessionCount: Int = 0
        var lastDailyTotal: Int = 0
        var hideCount = 0

        override fun isVisible(): Boolean = visible
        override fun show(label: String) {
            visible = true
            lastShowLabel = label
        }
        override fun updateCount(sessionCount: Int, dailyTotal: Int) {
            lastSessionCount = sessionCount
            lastDailyTotal = dailyTotal
        }
        override fun hide() {
            visible = false
            hideCount++
        }
    }

    private class FakeInAppDetector : InAppDetectorApi {
        var featureToReturn: InAppDetector.Feature? = null
        var detectCallCount: Int = 0

        // Overrides `detect`, the one abstract member, rather than `detectFeature` -- the counter
        // still sees every call, because the feature-only accessor delegates here.
        override fun detect(
            packageName: String,
            rootNode: AccessibilityNodeInfo?
        ): InAppDetector.Detection? {
            detectCallCount++
            return featureToReturn?.let { InAppDetector.Detection(it) }
        }
    }

    private class FakeTimeRemainingHandler : TimeRemainingHandlerApi {
        var lastMaybeUpdatePackage: String? = null
        var resetDebounceCalled = false
        var hideCalled = false

        override fun maybeUpdate(packageName: String) {
            lastMaybeUpdatePackage = packageName
        }
        override fun resetDebounce() { resetDebounceCalled = true }
        override fun hide() { hideCalled = true }
    }
}
