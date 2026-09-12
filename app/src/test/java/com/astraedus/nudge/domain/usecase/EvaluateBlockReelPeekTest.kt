package com.astraedus.nudge.domain.usecase

import com.astraedus.nudge.data.db.entity.BlockRule
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ContentFilter
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.BlockEngine
import com.astraedus.nudge.domain.engine.RuleEvaluator
import com.astraedus.nudge.domain.engine.ScheduleEvaluator
import com.astraedus.nudge.domain.inapp.ReelSurface
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The "watch the one you were sent, then stop" allowance, end to end through the use case — rule
 * rows in, [BlockDecision] out.
 *
 * [com.astraedus.nudge.domain.inapp.ReelPeekTest] pins the decision itself; this pins that the
 * decision is actually WIRED, that it is applied before the block engine sees the rules, and that
 * the surrounding rules (whole-app, daily budget) are unaffected by it. The three are separate
 * because a correct pure gate that nothing calls is exactly the failure this repo has shipped
 * before.
 */
class EvaluateBlockReelPeekTest {

    private val pkg = "com.instagram.android"

    private lateinit var blockRuleRepository: BlockRuleRepository
    private lateinit var usageRepository: UsageRepository
    private lateinit var useCase: EvaluateBlockUseCase

    @Before
    fun setUp() {
        blockRuleRepository = mockk()
        usageRepository = mockk()

        every { blockRuleRepository.getAllGroups() } returns flowOf(emptyList())

        useCase = EvaluateBlockUseCase(
            blockRuleRepository = blockRuleRepository,
            usageRepository = usageRepository,
            blockEngine = BlockEngine(ScheduleEvaluator()),
            ruleEvaluator = RuleEvaluator(),
            preferences = mockk<NudgePreferences>(),
            contentFilter = mockk<ContentFilter>()
        )
    }

    private fun reelsRule(allowSingleReel: Boolean) = BlockRule(
        id = 1L,
        packageName = pkg,
        mode = BlockMode.HARD_BLOCK.name,
        enabled = true,
        inAppFeatures = "REELS",
        allowSingleReel = allowSingleReel
    )

    private fun withRules(vararg rules: BlockRule) {
        every { blockRuleRepository.getEnabledRules() } returns flowOf(rules.toList())
    }

    private suspend fun evaluate(
        surface: ReelSurface?,
        peekSpent: Boolean = false
    ): BlockDecision = useCase.invoke(
        packageName = pkg,
        detectedFeature = "REELS",
        reelSurface = surface,
        reelPeekSpent = peekSpent
    )

    // ── With the allowance on ───────────────────────────────────────────────

    @Test
    fun `a reel from a DM is allowed until the first swipe`() = runTest {
        withRules(reelsRule(allowSingleReel = true))

        assertEquals(
            BlockDecision.Allow,
            evaluate(ReelSurface.STANDALONE_PLAYER, peekSpent = false)
        )
        assertTrue(
            "the swipe must block",
            evaluate(ReelSurface.STANDALONE_PLAYER, peekSpent = true) is BlockDecision.Block
        )
    }

    @Test
    fun `the home feed opens`() = runTest {
        withRules(reelsRule(allowSingleReel = true))

        assertEquals(BlockDecision.Allow, evaluate(ReelSurface.HOME_FEED))
    }

    @Test
    fun `the Reels tab still hard-blocks`() = runTest {
        withRules(reelsRule(allowSingleReel = true))

        val decision = evaluate(ReelSurface.REELS_TAB)

        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.HARD_BLOCK, (decision as BlockDecision.Block).mode)
    }

    /**
     * The peek suppresses the REELS rule, not the app. A user who also blocks Instagram itself must
     * still hit that block — otherwise turning this on would quietly open an app they closed.
     */
    @Test
    fun `a whole-app block still applies underneath the allowance`() = runTest {
        withRules(
            reelsRule(allowSingleReel = true),
            BlockRule(
                id = 2L,
                packageName = pkg,
                mode = BlockMode.DELAY.name,
                delaySeconds = 15,
                enabled = true
            )
        )

        val decision = evaluate(ReelSurface.STANDALONE_PLAYER)

        assertTrue(decision is BlockDecision.Block)
        assertEquals(BlockMode.DELAY, (decision as BlockDecision.Block).mode)
    }

    /**
     * A suppressed Reels rule must not take the app's daily budget with it: "let me watch the one
     * my friend sent" is not "give me unlimited Instagram".
     */
    @Test
    fun `the daily budget is still spent while a peek is open`() = runTest {
        withRules(
            reelsRule(allowSingleReel = true),
            BlockRule(
                id = 2L,
                packageName = pkg,
                mode = BlockMode.NONE.name,
                dailyLimitMinutes = 30,
                enabled = true
            )
        )
        every { usageRepository.getDailyForegroundTimeMs(pkg) } returns 40 * 60_000L

        val decision = evaluate(ReelSurface.STANDALONE_PLAYER)

        assertTrue("an exhausted budget must still block", decision is BlockDecision.Block)
    }

    // ── With the allowance off: nothing changes ─────────────────────────────

    @Test
    fun `every surface blocks when the rule has not opted in`() = runTest {
        withRules(reelsRule(allowSingleReel = false))

        listOf(
            ReelSurface.STANDALONE_PLAYER,
            ReelSurface.HOME_FEED,
            ReelSurface.REELS_TAB,
            null
        ).forEach { surface ->
            assertTrue(
                "surface $surface must block",
                evaluate(surface) is BlockDecision.Block
            )
        }
    }

    /**
     * A surface detection could not identify is not an allowance. This is the case an older or
     * reshuffled Instagram build lands in, and it has to keep blocking.
     */
    @Test
    fun `an unknown surface blocks even with the allowance on`() = runTest {
        withRules(reelsRule(allowSingleReel = true))

        assertTrue(evaluate(surface = null) is BlockDecision.Block)
    }
}
