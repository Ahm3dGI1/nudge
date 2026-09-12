package com.astraedus.nudge.domain.model

data class BlockRuleData(
    val id: Long,
    val packageName: String?,
    val groupId: Long?,
    val mode: BlockMode,
    val delaySeconds: Int,
    val dailyLimitMinutes: Int?,
    val enabled: Boolean,
    val scheduleDays: List<Int>? = null,        // 1=Mon..7=Sun
    val scheduleStartMinute: Int? = null,
    val scheduleEndMinute: Int? = null,
    val inAppFeatures: List<String>? = null,
    val grayscale: Boolean = false,
    val webDomains: String? = null,             // comma-separated: "instagram.com,www.instagram.com"
    /**
     * "Watch the one you were sent, then stop" for a REELS rule. See
     * [com.astraedus.nudge.data.db.entity.BlockRule.allowSingleReel] and
     * [com.astraedus.nudge.domain.inapp.ReelPeek].
     */
    val allowSingleReel: Boolean = false
)
