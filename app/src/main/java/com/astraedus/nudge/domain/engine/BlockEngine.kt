package com.astraedus.nudge.domain.engine

import com.astraedus.nudge.domain.model.ActiveRule
import com.astraedus.nudge.domain.model.BlockDecision
import com.astraedus.nudge.domain.model.BlockMode
import com.astraedus.nudge.domain.logging.NudgeLog
import javax.inject.Inject

class BlockEngine @Inject constructor(
    private val scheduleEvaluator: ScheduleEvaluator,
    private val logger: NudgeLog = NudgeLog.NoOp
) {

    /**
     * Evaluate whether a package should be blocked based on active rules and daily usage.
     *
     * @param detectedFeature If non-null, feature-scoped rules whose [ActiveRule.inAppFeatures]
     *   list contains this feature will be considered. Whole-app rules are also considered unless
     *   [includeWholeAppRulesForFeature] is false.
     *
     * Priority: HARD_BLOCK > time budget exceeded > DELAY > BREATHING > Allow
     *
     * [BlockMode.NONE] deliberately matches none of the block branches below, so a rule carrying
     * it yields Allow. It still participates in the time-budget check, which keys off
     * `dailyLimitMinutes` rather than the mode — an app-level NONE rule with a daily limit means
     * "don't gate this app, but stop me after N minutes", and its counter/overlay settings still
     * apply. This is what lets a feature-scoped rule (Shorts, Reels) block while its host app
     * stays open.
     */
    fun evaluate(
        packageName: String,
        activeRules: List<ActiveRule>,
        dailyUsageMs: Long,
        detectedFeature: String? = null,
        includeWholeAppRulesForFeature: Boolean = true
    ): BlockDecision {
        logger.d(
            "evaluate package=$packageName rules=${activeRules.size} " +
                "dailyUsageMs=$dailyUsageMs detectedFeature=$detectedFeature " +
                "includeWholeAppRulesForFeature=$includeWholeAppRulesForFeature"
        )

        val applicableRules = activeRules
            .filter { it.enabled }
            .filter { scheduleEvaluator.isActiveNow(it) }
            .filter { rule ->
                if (detectedFeature != null) {
                    // In-app detection active: match rules that target this feature
                    // and, unless suppressed by passthrough, whole-app rules.
                    val features = rule.inAppFeatures
                    detectedFeature in (features ?: emptyList()) ||
                        (includeWholeAppRulesForFeature && (features == null || features.isEmpty()))
                } else {
                    // No in-app detection: only apply whole-app rules
                    rule.inAppFeatures == null || rule.inAppFeatures.isEmpty()
                }
            }

        if (applicableRules.isEmpty()) {
            logger.d("allow package=$packageName reason=no_applicable_rules")
            return BlockDecision.Allow
        }

        // Compute daily time remaining from the minimum daily limit among applicable rules
        val minDailyLimit = applicableRules.mapNotNull { it.dailyLimitMinutes }.minOrNull()
        val dailyTimeRemainingMs = if (minDailyLimit != null) {
            (minDailyLimit.toLong() * 60L * 1000L - dailyUsageMs).coerceAtLeast(0L)
        } else null

        // Check whether any applicable rule wants grayscale
        val wantsGrayscale = applicableRules.any { it.grayscale }

        /**
         * Whether THIS rule's block should be enforced by backing out of the feature rather than by
         * the block overlay.
         *
         * Deliberately per-WINNING-rule, not `applicableRules.any { … }` like grayscale above:
         * grayscale is an ambient effect any applicable rule may ask for, but this decides how ONE
         * block is carried out, and the rule that did not win has no say in it. Taking `any` here
         * would let a soft Reels rule downgrade the enforcement of a whole-app hard block that
         * happened to be active at the same time.
         *
         * Requires the rule to be scoped to the feature that was actually DETECTED. A whole-app rule
         * has no feature to leave — backing out of an app is just leaving it — and a rule scoped to
         * some other feature is not what is being enforced here. That check lives here rather than
         * at the call site so [BlockDecision.Block.exitFeature] can be trusted as-is by the service.
         */
        fun exitFeatureFor(rule: ActiveRule): Boolean =
            rule.exitFeatureOnBlock &&
                detectedFeature != null &&
                detectedFeature in (rule.inAppFeatures ?: emptyList())

        // Check for unconditional HARD_BLOCK (no daily limit)
        val unconditionalHardBlockRule = applicableRules.firstOrNull {
            it.mode == BlockMode.HARD_BLOCK && it.dailyLimitMinutes == null
        }
        if (unconditionalHardBlockRule != null) {
            logger.i("block package=$packageName reason=unconditional_hard_block grayscale=$wantsGrayscale")
            return BlockDecision.Block(
                BlockMode.HARD_BLOCK,
                grayscale = wantsGrayscale,
                ruleName = unconditionalHardBlockRule.ruleName,
                dailyTimeRemainingMs = dailyTimeRemainingMs,
                dailyLimitMinutes = minDailyLimit,
                exitFeature = exitFeatureFor(unconditionalHardBlockRule)
            )
        }

        // Check if any time budget is exceeded
        val timeBudgetRule = applicableRules.firstOrNull { rule ->
            rule.dailyLimitMinutes != null &&
                dailyUsageMs >= rule.dailyLimitMinutes.toLong() * 60L * 1000L
        }
        if (timeBudgetRule != null) {
            logger.i("block package=$packageName reason=time_budget_exceeded grayscale=$wantsGrayscale")
            val budgetRuleName = timeBudgetRule.ruleName?.let { "$it (limit reached)" }
            // No `exitFeature` here, even if the rule carries the flag. An exhausted daily budget is
            // a statement about the whole app's allowance for the day, not about one surface, and
            // backing out of the feature would leave the user in an app they have run out of time
            // for — free to spend the rest of the day in every other part of it. The overlay is the
            // right stop for a budget, and it is the stop every budget has always used.
            return BlockDecision.Block(
                BlockMode.HARD_BLOCK,
                grayscale = wantsGrayscale,
                ruleName = budgetRuleName,
                dailyTimeRemainingMs = dailyTimeRemainingMs,
                dailyLimitMinutes = minDailyLimit
            )
        }

        // Check for DELAY rules
        val delayRule = applicableRules.firstOrNull { it.mode == BlockMode.DELAY }
        if (delayRule != null) {
            logger.i(
                "block package=$packageName reason=delay_rule " +
                    "delaySeconds=${delayRule.delaySeconds} grayscale=$wantsGrayscale"
            )
            return BlockDecision.Block(
                BlockMode.DELAY,
                delayRule.delaySeconds,
                wantsGrayscale,
                ruleName = delayRule.ruleName,
                dailyTimeRemainingMs = dailyTimeRemainingMs,
                dailyLimitMinutes = minDailyLimit,
                exitFeature = exitFeatureFor(delayRule)
            )
        }

        // Check for BREATHING rules
        val breathingRule = applicableRules.firstOrNull { it.mode == BlockMode.BREATHING }
        if (breathingRule != null) {
            logger.i(
                "block package=$packageName reason=breathing_rule " +
                    "delaySeconds=${breathingRule.delaySeconds} grayscale=$wantsGrayscale"
            )
            return BlockDecision.Block(
                BlockMode.BREATHING,
                breathingRule.delaySeconds,
                wantsGrayscale,
                ruleName = breathingRule.ruleName,
                dailyTimeRemainingMs = dailyTimeRemainingMs,
                dailyLimitMinutes = minDailyLimit,
                exitFeature = exitFeatureFor(breathingRule)
            )
        }

        logger.d("allow package=$packageName reason=no_matching_block_mode")
        return BlockDecision.Allow
    }
}
