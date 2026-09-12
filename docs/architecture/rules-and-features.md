# Rule capabilities and in-app feature detection

Covers what a *rule* can express beyond a plain block: schedules, in-app feature blocking
(Shorts/Reels/TikTok), grayscale, the user-editable overlay message pools, and the rule editor.
**Read before touching `domain/` rule models, `InAppDetector`, `NudgeMessages`, the rule editor, or the Settings screen.**

## Capabilities

- **Schedule-based rules** — day-of-week + time-of-day, overnight schedule support (spans midnight)
- **In-app feature blocking** — YouTube Shorts, Instagram Reels/Explore, TikTok detection via AccessibilityService
- **Grayscale mode** — force screen to grayscale (requires ADB: `adb shell pm grant com.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS`). Grayscale guide in Settings.
- **Rotating motivational messages** — shown on overlay screens when blocks trigger. **User-editable (v1.6.0)**: defaults live in `ui/overlay/NudgeMessages.kt` (delayTitles/delaySubtitles/hardBlockMessages); users override via Settings → Personalize → "Edit block messages" (`ui/screens/settings/MessagesEditorScreen.kt`), stored as 3 multiline strings in `NudgePreferences` (`customDelayTitles`/`customDelaySubtitles`/`customHardBlockMessages`, one message per line, empty = defaults). `NudgeMessages.resolvePool(customRaw, default)` is the pure resolver; `BlockOverlayActivity` reads the prefs once via `runBlocking{ first() }` before `setContent` (avoids a default→custom flash) and passes resolved pools into the overlay composables (which still `remember { pool.random() }`).
- **Instagram home feed detection** — `InAppDetector` detects Instagram's home feed (when Home tab is selected, no other tabs active) and treats it as REELS-equivalent. Home feed scrolling counts toward interaction counter and auto-kick the same as the Reels tab. **The single-reel allowance opts out of this** — see below.
- **Single-reel allowance ("watch the one you were sent, then stop")** — a per-rule opt-in on a REELS feature rule. See the section below.
- **Rule editor UX** — info tooltips on all sections, block mode descriptions, per-app rules summary with enable/disable
- **Settings** — version links to GitHub repo, source code & feedback link

## The single-reel allowance (`BlockRule.allowSingleReel`)

**Read before touching `InAppDetector.classifyInstagram`, `domain/inapp/`, or the scroll branch of
`NudgeAccessibilityService.onAccessibilityEvent`.**

### The problem it solves

A REELS rule is one switch answering two very different questions: *may I be pointed at a clip* and
*may I graze*. Blocking both is the right default and is what every rule written before DB v11 does.
But the reel a friend sends in a DM is a message, and hard-blocking it makes Instagram unusable for
messaging — the detector already had to be careful not to fire on a DM thread for exactly this
reason. `allowSingleReel` splits the two questions apart.

With it ON, a Reels rule enforces on:

| Surface | Enforces? |
|---|---|
| Reels tab (`clips_tab` selected) | **Always** |
| Full-screen player, before the first swipe | No — this is the peek |
| Full-screen player, after the first swipe | **Yes** |
| Home feed (`feed_tab` selected) | No |
| Anything the detector could not identify | **Yes** |

With it OFF (the default, and every pre-v11 rule) nothing changes: every row above enforces.

### The three pieces, and why they are separate

- **`InAppDetector.classifyInstagram`** (pure) — turns three tree observations into a `ReelSurface`.
- **`ReelPeek.suppresses`** (pure) — decides whether a given rule enforces on a given surface.
- **`ReelPeekSession`** (pure, service-owned) — tracks whether a peek is open and whether it is spent.

They are split because each one is a different kind of thing to get wrong, and a correct pure gate
that nothing calls is a failure this repo has shipped before — hence
`EvaluateBlockReelPeekTest`, which pins that the gate is actually WIRED.

### Ordering is the substance, and it is a regression risk

The clips containers (`clips_viewer_view_pager`, `clips_video_container`, `clips_media_component`)
are present on the **Reels tab too**. Before this feature, `detectInstagram` checked them FIRST,
because the 2026-08-08 fix needed to catch the modal player that has no bottom nav at all. Keeping
that order would have classified the Reels tab itself as a peek-eligible standalone player, i.e. a
hole straight through the rule. So the selected tab is now checked first, and
`InAppDetectorSurfaceTest` pins the whole ordering as values.

The modal player is identified by "clips containers AND no nav bar", never by "no tab reads as
selected" — those are different facts, and only the first one is evidence. A player with a nav bar we
cannot place falls back to `REELS_TAB`, i.e. it blocks.

### Which scroll spends a peek

`InAppDetector.isReelAdvanceScroll(viewId)`, fed from `AccessibilityEvent.getSource()`'s view id:

- a clips container → yes, this was a swipe;
- **null (unidentified or unreadable) → yes.** The standalone player IS a full-screen pager, so the
  likely unidentified scroll inside it is the swipe. A missed swipe is an unbounded reels session; a
  false positive costs one early block on a clip the user can re-open;
- anything else identified (the comment sheet's recycler) → no, so reading the replies on the clip
  you were sent does not block you.

`noteReelScroll` runs **before** `interactionHandler.handleViewScrolled`, which early-returns for any
package without the interaction counter switched on. The allowance is not a counter feature and must
not inherit that gate — a Reels rule with no counter would otherwise never spend its peek.

It reads only the source's **view id**, never text or content description: on this screen those are
the user's private messages and captions, the same rule that governs `dumpViewIdsForDiagnosis`.

### Which way each unknown fails

Every ambiguity resolves toward BLOCKING, because this repo's worst failure class is a blocker that
silently blocks nothing:

- an **unreadable node tree** never reaches `ReelPeekSession` at all, so a spent peek stays spent —
  otherwise endless scrolling would be a matter of swiping and waiting;
- **re-detecting the same player does not re-arm.** Detection re-runs about every two seconds, so a
  re-arming session would refresh the allowance faster than anyone can scroll;
- **leaving the app does not refund a spent peek.** "Swipe, get blocked, tab out, tab in" must not be
  a free reel. What legitimately refunds one is arriving at the player from a screen positively
  identified as something else — which is what lets a *second* reel in the same DM thread play.

The accepted cost: if the player's tree momentarily comes back without its containers mid-session,
the peek is refunded and the user gets one extra clip. One extra reel beats an unbounded feed.

### What it deliberately does NOT touch

- **Whole-app rules.** A peek suppresses the feature-scoped REELS rule only, so it can never become a
  route around "block Instagram entirely".
- **The daily budget.** A suppressed Reels rule does not take the app's `dailyLimitMinutes` with it.
- **Other features.** A rule scoped to `REELS,EXPLORE` is suppressed on the reels surface and still
  enforces on Explore.

### Strict Mode

Turning the allowance ON is a weakening and demands the unlock challenge. It is checked in TWO
places, because the flag lives on a FEATURE rule and only the app-level rule goes through
`RuleWeakening.isWeakening` in `UnifiedAppConfigViewModel.save()`:
`RuleWeakening` carries the dimension (for any caller that compares two rules), and
`UnifiedAppConfigViewModel.enablesSingleReel` is what the save path actually consults.

A general "did any feature override get weaker" answer is NOT implemented and is a known gap — it
needs a story for an override being DELETED, which can inherit a stronger app-level mode and so is
not always weakening.
