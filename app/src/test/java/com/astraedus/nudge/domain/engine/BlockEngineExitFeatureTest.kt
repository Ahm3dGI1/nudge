package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which blocks may be enforced by backing out of the feature instead of by the block overlay.
 *
 * The engine is where the "only for the feature that was actually detected" condition lives, so the
 * service can trust [BlockDecision.Block.exitFeature] without re-deriving anything. These tests are
 * mostly about the cases that must NOT carry it — each one is a way a rule could end up softening a
 * block it has no business softening.
 */
class BlockEngineExitFeatureTest {

    private val engine = BlockEngine(ScheduleEvaluator())
    private val pkg = "com.instagram.android"

    private fun reelsRule(
        mode: BlockMode = BlockMode.HARD_BLOCK,
        exitFeatureOnBlock: Boolean = true,
        dailyLimitMinutes: Int? = null
    ) = ActiveRule(
        mode = mode,
        delaySeconds = 15,
        dailyLimitMinutes = dailyLimitMinutes,
        enabled = true,
        inAppFeatures = listOf("REELS"),
        exitFeatureOnBlock = exitFeatureOnBlock
    )

    private fun evaluate(
        rules: List<ActiveRule>,
        feature: String? = "REELS",
        dailyUsageMs: Long = 0L
    ) = engine.evaluate(
        packageName = pkg,
        activeRules = rules,
        dailyUsageMs = dailyUsageMs,
        detectedFeature = feature
    )

    private fun block(decision: BlockDecision): BlockDecision.Block {
        assertTrue("expected a block, got $decision", decision is BlockDecision.Block)
        return decision as BlockDecision.Block
    }

    // ── carried ─────────────────────────────────────────────────────────────

    @Test
    fun `a feature rule that opted in carries exitFeature`() {
        assertTrue(block(evaluate(listOf(reelsRule()))).exitFeature)
    }

    @Test
    fun `it applies to delay and breathing too, not just hard block`() {
        listOf(BlockMode.DELAY, BlockMode.BREATHING).forEach { mode ->
            val decision = block(evaluate(listOf(reelsRule(mode = mode))))
            assertEquals(mode, decision.mode)
            assertTrue("$mode must carry exitFeature", decision.exitFeature)
        }
    }

    // ── not carried ─────────────────────────────────────────────────────────

    @Test
    fun `a rule that did not opt in never carries it`() {
        assertFalse(block(evaluate(listOf(reelsRule(exitFeatureOnBlock = false)))).exitFeature)
    }

    /**
     * A whole-app rule has no feature to back out of — backing out of an app is just leaving it —
     * so the flag is inert on one even if somehow set.
     */
    @Test
    fun `a whole-app rule never carries it`() {
        val wholeApp = ActiveRule(
            mode = BlockMode.HARD_BLOCK,
            delaySeconds = 15,
            dailyLimitMinutes = null,
            enabled = true,
            inAppFeatures = null,
            exitFeatureOnBlock = true
        )

        assertFalse(block(evaluate(listOf(wholeApp))).exitFeature)
    }

    /** With no feature detected there is nothing to leave, so a feature rule cannot soften anything. */
    @Test
    fun `no detected feature means no exitFeature`() {
        val decision = evaluate(listOf(reelsRule()), feature = null)

        // A feature-scoped rule does not even apply without detection.
        assertEquals(BlockDecision.Allow, decision)
    }

    /** A rule scoped to a DIFFERENT feature is not the one being enforced. */
    @Test
    fun `a rule scoped to another feature does not soften this one`() {
        val explore = ActiveRule(
            mode = BlockMode.HARD_BLOCK,
            delaySeconds = 15,
            dailyLimitMinutes = null,
            enabled = true,
            inAppFeatures = listOf("EXPLORE"),
            exitFeatureOnBlock = true
        )
        val reelsHard = reelsRule(exitFeatureOnBlock = false)

        assertFalse(block(evaluate(listOf(explore, reelsHard))).exitFeature)
    }

    /**
     * The losing rule has no say in how the winning one is enforced. A soft Reels rule must not
     * downgrade a whole-app hard block that happens to be active at the same time — that would make
     * "block Instagram" mean "back out of Reels".
     */
    @Test
    fun `a soft feature rule cannot downgrade a whole-app hard block`() {
        val wholeAppHard = ActiveRule(
            mode = BlockMode.HARD_BLOCK,
            delaySeconds = 15,
            dailyLimitMinutes = null,
            enabled = true,
            inAppFeatures = null
        )
        val softReels = reelsRule(mode = BlockMode.DELAY, exitFeatureOnBlock = true)

        val decision = block(evaluate(listOf(wholeAppHard, softReels)))

        assertEquals(BlockMode.HARD_BLOCK, decision.mode)
        assertFalse("the whole-app block must stay a full block", decision.exitFeature)
    }

    /**
     * An exhausted daily budget is a statement about the whole app's allowance for the day, not
     * about one surface. Backing out of the feature would leave the user free to spend the rest of
     * the day everywhere else in an app they have run out of time for.
     */
    @Test
    fun `an exhausted daily budget always uses the overlay`() {
        val budgeted = reelsRule(dailyLimitMinutes = 30, exitFeatureOnBlock = true)

        val decision = block(
            evaluate(listOf(budgeted), dailyUsageMs = 40 * 60_000L)
        )

        assertEquals(BlockMode.HARD_BLOCK, decision.mode)
        assertFalse("a spent budget is not a feature-shaped stop", decision.exitFeature)
    }
}
