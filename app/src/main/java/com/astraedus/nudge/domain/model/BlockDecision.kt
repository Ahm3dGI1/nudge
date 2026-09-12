package com.astraedus.nudge.domain.model

sealed class BlockDecision {
    data object Allow : BlockDecision()
    data class Block(
        val mode: BlockMode,
        val delaySeconds: Int = 0,
        val grayscale: Boolean = false,
        val ruleName: String? = null,
        val dailyTimeRemainingMs: Long? = null,
        val dailyLimitMinutes: Int? = null,
        /**
         * Stop the user by backing OUT OF THE FEATURE rather than putting the block overlay up.
         *
         * Set only when the winning rule is scoped to the feature that was actually detected — see
         * [com.astraedus.nudge.domain.engine.BlockEngine]. A whole-app block has no feature to leave,
         * so this is always false for one, and the enforcing code can therefore trust it without
         * re-deriving the condition.
         */
        val exitFeature: Boolean = false
    ) : BlockDecision()
}
