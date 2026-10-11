package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ReelsBlockerAccessibilityService
 *
 * Core logic that enforces all five blocking rules described in the spec:
 *
 *  Rule 1 – BOTTOM NAV: tap on the Reels tab → GLOBAL_ACTION_BACK
 *  Rule 2 – HOME FEED:  Reel opens from the main feed → GLOBAL_ACTION_BACK
 *  Rule 3 – PROFILES:   Reel opened from a profile → allowed (watch once)
 *  Rule 4 – DMs:        Reel opened from a DM → allowed (watch once)
 *  Rule 5 – SWIPE:      Any scroll inside an active Reel view (from profile/DM)
 *                        → GLOBAL_ACTION_BACK
 *
 * ─── How we identify Instagram's internal UI ──────────────────────────────────
 *
 * Instagram does not expose stable public IDs, so we rely on a combination of
 * resource-ID fragments that have been stable across recent versions (as of 2025-
 * 2026).  These IDs are checked with `contains()` rather than exact equality so
 * that minor version changes (e.g. a numeric suffix) don't break the match.
 *
 * IDs observed in the wild (may need updating if Instagram refactors):
 *
 *   Bottom nav tabs
 *     Home    → "tab_home"       / "tab_icon_home"
 *     Search  → "tab_search"     / "tab_icon_search"
 *     Reels   → "clips_tab"      / "tab_icon_clips"   ← BLOCK TAPS HERE
 *     Shop    → "tab_shopping"
 *     Profile → "tab_profile"    / "tab_icon_profile"
 *
 *   Reel / Clips player
 *     Container  → "clips_viewer_root"  / "unified_reel_view"
 *     Video view → "reel_viewer_pager"  / "clips_video"
 *
 *   Context indicators (which surface opened the Reel)
 *     Home feed card → "media_group"     / "carousel_media_group"
 *                       inside a "feed_timeline"
 *     Profile grid  → "profile_main_content_view"  (parent when opening)
 *     DM thread     → "direct_thread_fragment"     / "ig_direct_thread"
 *
 * ─── State machine ────────────────────────────────────────────────────────────
 *
 *  IDLE            – Instagram is open but no Reel is playing
 *  REEL_ALLOWED    – A Reel is playing and swiping is blocked (profile or DM entry)
 *
 * We purposely keep the state minimal; we don't track REEL_BLOCKED state because
 * the action (GLOBAL_ACTION_BACK) is immediate and the state resets on its own.
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PKG = "com.instagram.android"

        // ── Bottom-nav Reels tab ──────────────────────────────────────────────
        // Instagram has used several IDs across versions; we match any of them.
        private val REELS_TAB_ID_FRAGMENTS = listOf(
            "clips_tab",
            "tab_icon_clips",
            "reels_tab",
            "tab_clips"
        )

        // ── Reel / Clips player container IDs ────────────────────────────────
        private val REEL_PLAYER_ID_FRAGMENTS = listOf(
            "clips_viewer_root",
            "unified_reel_view",
            "reel_viewer_root",
            "clips_swipe_refresh",
            "reel_viewer_pager",
            "clips_video_container"
        )

        // ── Feed-origin indicators (parent context when a Reel starts) ────────
        // If ANY of these IDs are present in the window hierarchy *before* the
        // Reel player appears, we know the entry came from the main feed.
        private val FEED_ORIGIN_ID_FRAGMENTS = listOf(
            "feed_timeline",
            "home_feed_container",
            "clips_home_unit",          // Reels preview row inside home feed
            "feed_surface_controller"
        )

        // ── Profile-origin indicators ─────────────────────────────────────────
        private val PROFILE_ORIGIN_ID_FRAGMENTS = listOf(
            "profile_main_content_view",
            "profile_tab_icon",
            "user_profile_header_list_view",
            "profile_grid_view",
            "media_grid_view"
        )

        // ── DM-origin indicators ──────────────────────────────────────────────
        private val DM_ORIGIN_ID_FRAGMENTS = listOf(
            "direct_thread_fragment",
            "ig_direct_thread",
            "direct_thread_row",
            "direct_message_container",
            "thread_fragment"
        )

        // Back-action cooldown (ms) — prevents double-firing
        private const val BACK_COOLDOWN_MS = 600L
    }

    // ─── State ────────────────────────────────────────────────────────────────

    /** True while the user is watching a Reel that arrived from a profile or DM. */
    @Volatile private var reelIsAllowedAndActive = false

    /** Window class name of the last seen Instagram Activity. */
    @Volatile private var lastWindowClassName = ""

    /** Tracks which "surface" opened the current Reel. */
    private enum class ReelOrigin { UNKNOWN, FEED, PROFILE, DM }
    @Volatile private var reelOrigin = ReelOrigin.UNKNOWN

    /** Timestamp of the most recent GLOBAL_ACTION_BACK we fired. */
    @Volatile private var lastBackActionMs = 0L

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")

        // Dynamically configure the service (mirrors the static XML, but lets
        // us adjust at runtime if needed).
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            packageNames = arrayOf(INSTAGRAM_PKG)
            notificationTimeout = 100
        }

        // Make sure the foreground service is running
        val intent = Intent(this, BlockerForegroundService::class.java)
        startForegroundService(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        reelIsAllowedAndActive = false
    }

    // ─── Main event handler ───────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != INSTAGRAM_PKG) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChange(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChange(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event)
        }
    }

    // ─── Rule 1: Bottom-nav Reels tab tap ─────────────────────────────────────

    private fun handleClick(event: AccessibilityEvent) {
        val sourceId = event.source?.viewIdResourceName ?: return
        if (REELS_TAB_ID_FRAGMENTS.any { sourceId.contains(it, ignoreCase = true) }) {
            Log.d(TAG, "Rule 1 — Reels tab tapped, firing BACK. (sourceId=$sourceId)")
            triggerBack("Rule1-ReelsTab")
        }
    }

    // ─── Rule 2 / 3 / 4: Reel player appearing — determine origin ────────────

    private fun handleWindowStateChange(event: AccessibilityEvent) {
        lastWindowClassName = event.className?.toString() ?: ""

        // When a new window opens, check if it's a Reel player
        val root = rootInActiveWindow ?: return
        try {
            if (isReelPlayerVisible(root)) {
                val detectedOrigin = detectReelOrigin(root)
                reelOrigin = detectedOrigin
                Log.d(TAG, "Reel player detected — origin=$detectedOrigin")

                when (detectedOrigin) {
                    ReelOrigin.FEED -> {
                        // Rule 2: opened from home feed → block immediately
                        Log.d(TAG, "Rule 2 — Reel from feed, firing BACK.")
                        reelIsAllowedAndActive = false
                        triggerBack("Rule2-FeedReel")
                    }
                    ReelOrigin.PROFILE, ReelOrigin.DM -> {
                        // Rules 3 & 4: allowed entry point — arm the swipe blocker
                        Log.d(TAG, "Rules 3/4 — Reel from ${detectedOrigin.name}, allowing (swipe blocker armed).")
                        reelIsAllowedAndActive = true
                    }
                    ReelOrigin.UNKNOWN -> {
                        // Ambiguous origin — default to blocking (safe)
                        Log.d(TAG, "Rule 2 fallback — unknown origin, firing BACK.")
                        reelIsAllowedAndActive = false
                        triggerBack("Rule2-UnknownOrigin")
                    }
                }
            } else {
                // Reel player not visible → reset state
                if (reelIsAllowedAndActive) {
                    Log.d(TAG, "Reel player no longer visible — resetting state.")
                }
                reelIsAllowedAndActive = false
                reelOrigin = ReelOrigin.UNKNOWN
            }
        } finally {
            root.recycle()
        }
    }

    private fun handleContentChange(event: AccessibilityEvent) {
        // Re-evaluate on content changes for cases where the Reel loads
        // asynchronously after the window state event.
        val root = rootInActiveWindow ?: return
        try {
            if (!reelIsAllowedAndActive && isReelPlayerVisible(root)) {
                val detectedOrigin = detectReelOrigin(root)
                if (detectedOrigin == ReelOrigin.FEED || detectedOrigin == ReelOrigin.UNKNOWN) {
                    Log.d(TAG, "Rule 2 (content change) — Reel from ${detectedOrigin.name}, firing BACK.")
                    triggerBack("Rule2-ContentChange")
                } else if (detectedOrigin == ReelOrigin.PROFILE || detectedOrigin == ReelOrigin.DM) {
                    reelOrigin = detectedOrigin
                    reelIsAllowedAndActive = true
                }
            }
        } finally {
            root.recycle()
        }
    }

    // ─── Rule 5: Swipe blocker ────────────────────────────────────────────────

    private fun handleScroll(event: AccessibilityEvent) {
        if (!reelIsAllowedAndActive) return

        // Only act on scroll events whose source node is part of a Reel player
        val sourceId = event.source?.viewIdResourceName ?: ""
        val isReelScroll = REEL_PLAYER_ID_FRAGMENTS.any { sourceId.contains(it, ignoreCase = true) }
                || isReelScrollByClassName(event.source)

        if (isReelScroll) {
            Log.d(TAG, "Rule 5 — Scroll inside Reel (origin=${reelOrigin.name}), firing BACK.")
            reelIsAllowedAndActive = false   // prevent re-entry loop
            triggerBack("Rule5-ReelSwipe")
        }
    }

    /**
     * Fallback: check if the source node's class looks like a ViewPager / RecyclerView
     * that is a direct child of a known Reel container, even when the resource ID
     * isn't available (happens when Instagram uses ProGuard-obfuscated class names).
     */
    private fun isReelScrollByClassName(source: AccessibilityNodeInfo?): Boolean {
        if (source == null) return false
        val parent = source.parent ?: return false
        return try {
            val parentId = parent.viewIdResourceName ?: ""
            REEL_PLAYER_ID_FRAGMENTS.any { parentId.contains(it, ignoreCase = true) }
        } finally {
            parent.recycle()
        }
    }

    // ─── View-hierarchy helpers ───────────────────────────────────────────────

    /**
     * Returns true if the Reel player UI is currently visible in the given tree.
     */
    private fun isReelPlayerVisible(root: AccessibilityNodeInfo): Boolean {
        return REEL_PLAYER_ID_FRAGMENTS.any { fragment ->
            root.findAccessibilityNodeInfosByViewId(
                "$INSTAGRAM_PKG:id/$fragment"
            ).isNotEmpty()
        } || containsNodeWithIdFragment(root, REEL_PLAYER_ID_FRAGMENTS)
    }

    /**
     * Determines how the current Reel was opened by inspecting the full window
     * hierarchy for contextual sibling/ancestor nodes.
     */
    private fun detectReelOrigin(root: AccessibilityNodeInfo): ReelOrigin {
        // DM check — highest specificity first
        if (containsNodeWithIdFragment(root, DM_ORIGIN_ID_FRAGMENTS)) {
            return ReelOrigin.DM
        }
        if (containsNodeWithIdFragment(root, PROFILE_ORIGIN_ID_FRAGMENTS)) {
            return ReelOrigin.PROFILE
        }
        if (containsNodeWithIdFragment(root, FEED_ORIGIN_ID_FRAGMENTS)) {
            return ReelOrigin.FEED
        }

        // Secondary heuristic: inspect the back-stack via window class names.
        // Instagram's profile Activity typically contains "Profile" in its name;
        // the home feed Activity contains "MainTabActivity" or "FeedFragment".
        return when {
            lastWindowClassName.contains("Profile", ignoreCase = true) -> ReelOrigin.PROFILE
            lastWindowClassName.contains("Direct", ignoreCase = true)  -> ReelOrigin.DM
            lastWindowClassName.contains("MainTab", ignoreCase = true) -> ReelOrigin.FEED
            lastWindowClassName.contains("Feed", ignoreCase = true)    -> ReelOrigin.FEED
            else -> ReelOrigin.UNKNOWN
        }
    }

    /**
     * Depth-first search of the accessibility tree looking for any node whose
     * viewIdResourceName contains one of the given [fragments].
     *
     * This is more reliable than [findAccessibilityNodeInfosByViewId] for partial
     * matches (Instagram sometimes appends version-specific numeric suffixes).
     */
    private fun containsNodeWithIdFragment(
        root: AccessibilityNodeInfo,
        fragments: List<String>,
        depth: Int = 0
    ): Boolean {
        if (depth > 12) return false  // cap traversal depth for performance

        val id = root.viewIdResourceName
        if (id != null && fragments.any { id.contains(it, ignoreCase = true) }) {
            return true
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            try {
                if (containsNodeWithIdFragment(child, fragments, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    // ─── Back-action helper ───────────────────────────────────────────────────

    private fun triggerBack(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBackActionMs < BACK_COOLDOWN_MS) {
            Log.d(TAG, "Back cooldown active — skipping ($reason)")
            return
        }
        lastBackActionMs = now
        Log.i(TAG, "GLOBAL_ACTION_BACK fired — reason=$reason")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
}
