package com.astraedus.nudge.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "block_rules")
data class BlockRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String? = null,
    val groupId: Long? = null,
    val mode: String,
    val delaySeconds: Int = 15,
    val dailyLimitMinutes: Int? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    // Schedule-based rules (Feature 1)
    val scheduleDays: String? = null,       // comma-separated: "1,2,3,4,5" (1=Mon..7=Sun). null = every day
    val scheduleStartMinute: Int? = null,   // minutes from midnight. 540 = 9:00 AM. null = no schedule
    val scheduleEndMinute: Int? = null,     // minutes from midnight. 1020 = 5:00 PM. null = no schedule
    // In-app feature blocking (Feature 2)
    val inAppFeatures: String? = null,      // comma-separated: "REELS,SHORTS". null = block whole app
    // Grayscale mode (Feature 3)
    val grayscale: Boolean = false,
    // Interaction counter overlay
    val showCounter: Boolean = false,
    // Auto-kick: send user to home screen after this many scrolls (null = disabled)
    val autoKickAfter: Int? = null,
    // Show remaining daily time as overlay
    val showTimeRemaining: Boolean = false,
    // Cooldown after auto-kick in seconds (0 = no cooldown)
    val autoKickCooldownSeconds: Int = 60,
    // Web domain blocking (comma-separated: "instagram.com,www.instagram.com")
    val webDomains: String? = null,
    /**
     * Block mode used for [webDomains], INDEPENDENT of the app-level [mode].
     *
     * NULL = inherit [mode] (the historical behaviour, and what every rule written before this
     * column existed carries). A non-null value wins, which is what makes "don't block the app,
     * do block the site" expressible: with [mode] = NONE the app opens freely while the website
     * still enforces. Resolution lives in
     * [com.astraedus.nudge.domain.model.WebBlockMode.resolve] — never read this field raw.
     */
    val webBlockMode: String? = null,
    // Time-based auto-kick: send user to home screen after this many minutes of foreground time in
    // one session (null = disabled). Independent of [autoKickAfter]; whichever fires first kicks.
    val autoKickAfterMinutes: Int? = null,
    /**
     * "Watch the one you were sent, then stop", for a REELS feature rule.
     *
     * False (the default, and what every rule written before this column existed carries) means a
     * Reels rule blocks every reel surface, which is the historical behaviour. True narrows it to
     * the surfaces that are actually a FEED: the Reels tab always, and a full-screen player once the
     * user swipes past the clip they arrived on. A reel opened from a DM, a share link or the home
     * feed plays once, and the home feed itself stays open.
     *
     * Inert on any rule that is not feature-scoped to REELS. Resolution lives in
     * [com.astraedus.nudge.domain.inapp.ReelPeek.suppresses] — never read this field raw.
     */
    val allowSingleReel: Boolean = false
)
