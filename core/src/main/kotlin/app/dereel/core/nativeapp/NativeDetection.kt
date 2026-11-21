package app.dereel.core.nativeapp

import app.dereel.core.model.Platform
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.status.LayoutRecognition

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val isEmpty: Boolean get() = width == 0 || height == 0

    /** The smallest rectangle containing both. */
    fun union(other: Bounds): Bounds =
        Bounds(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}

/**
 * The minimal view of an accessibility node a detector may use (SEC-03). It deliberately omits
 * node text. Detectors match structural identifiers and never read message content.
 */
interface UiNode {
    /** Fully qualified resource name, for example "com.example:id/pager". */
    val viewId: String?
    val isVisibleToUser: Boolean
    val isSelected: Boolean
    val bounds: Bounds
    val childCount: Int
    fun child(index: Int): UiNode?

    /**
     * Descendants with the given fully qualified view ID, searched inside the target app's process
     * in one call. Returns null when the implementation cannot search. The detector then falls
     * back to a bounded traversal. A traversal costs one IPC per node, served on the target app's
     * UI thread, so the lookup is strongly preferred on devices (NFR-05).
     */
    fun findByViewId(fullId: String): List<UiNode>? = null
}

/**
 * A versioned set of structural anchors for one native app (FR-39). Identifiers are short names
 * qualified with [packageName] at match time. Each rule set has a compatibility profile. Until a
 * profile records device verification, the rule set is treated as unverified (FR-38).
 */
data class NativeRuleSet(
    val platform: Platform,
    val version: String,
    val packageName: String,
    /** Containers of a full-screen short-video viewer or feed. */
    val shortVideoContainerIds: Set<String>,
    /** Tabs that, when selected, mean the short-video feed is showing. */
    val shortVideoTabIds: Set<String> = emptySet(),
    /** Navigation anchors showing the layout is recognized. Kept uncovered where possible (FR-07). */
    val navigationIds: Set<String> = emptySet(),
    /**
     * True when the app has no single navigation-bar anchor, only one anchor per tab. The
     * navigation region is then the rectangle around all visible tab anchors. Otherwise it is
     * the largest single anchor.
     */
    val navigationIsSplit: Boolean = false,
    /** Other anchors proving the layout is recognized. */
    val knownScreenIds: Set<String> = emptySet(),
    /** A container must fill at least this fraction of the window, so inline previews do not count. */
    val minContainerFraction: Float = 0.6f,
) {
    fun qualified(id: String): String = "$packageName:id/$id"
}

data class Detection(
    val surface: SurfaceKind,
    /** Region to cover if Dereel shows a blocking cover. */
    val contentRegion: Bounds?,
    /** Navigation region to keep uncovered. */
    val navigationRegion: Bounds?,
    val layoutRecognized: Boolean,
    /** Which anchor matched, for diagnostics. Never contains screen text. */
    val matchedAnchor: String?,
)

/**
 * Identifies supported short-video surfaces from structural anchors (FR-06). It never blocks
 * a whole app because of its package name. Anchors are found by in-process view-ID lookup where
 * available. Otherwise a bounded traversal is used, so a large tree cannot stall the service.
 */
class NativeSurfaceDetector(
    val rules: NativeRuleSet,
    private val maxNodes: Int = 1_500,
    private val maxDepth: Int = 48,
) {
    private val containerIds = rules.shortVideoContainerIds.map(rules::qualified).toSet()
    private val tabIds = rules.shortVideoTabIds.map(rules::qualified).toSet()
    private val navIds = rules.navigationIds.map(rules::qualified).toSet()
    private val knownIds = rules.knownScreenIds.map(rules::qualified).toSet()
    private val allIds = containerIds + tabIds + navIds + knownIds

    fun detect(root: UiNode, window: Bounds): Detection {
        val anchors = lookupAnchors(root) ?: traverseAnchors(root)
        var container: Bounds? = null
        var containerAnchor: String? = null
        var selectedTab: String? = null
        var navRegion: Bounds? = null
        var recognized = false
        val minArea = (window.area * rules.minContainerFraction).toLong()

        for ((id, node) in anchors) {
            recognized = true
            when (id) {
                in containerIds -> {
                    val b = node.bounds
                    if (b.area >= minArea && b.area > (container?.area ?: -1L)) {
                        container = b
                        containerAnchor = id.substringAfter(":id/")
                    }
                }
                in tabIds -> if (node.isSelected) selectedTab = id.substringAfter(":id/")
                in navIds -> {
                    val b = node.bounds
                    val current = navRegion
                    if (!b.isEmpty) {
                        navRegion = when {
                            current == null -> b
                            rules.navigationIsSplit -> current.union(b)
                            b.area > current.area -> b
                            else -> current
                        }
                    }
                }
            }
        }

        val isShortVideo = container != null || selectedTab != null
        val region = when {
            container != null -> container
            selectedTab != null -> Bounds(window.left, window.top, window.right, navRegion?.top ?: window.bottom)
            else -> null
        }
        return Detection(
            surface = if (isShortVideo) SurfaceKind.SHORT_VIDEO else SurfaceKind.OTHER,
            contentRegion = region?.let { excludeNavigation(it, navRegion) },
            navigationRegion = navRegion,
            layoutRecognized = recognized,
            matchedAnchor = containerAnchor ?: selectedTab,
        )
    }

    /** Visible anchor nodes found by in-process lookup, or null if the node cannot search. */
    private fun lookupAnchors(root: UiNode): List<Pair<String, UiNode>>? {
        val result = ArrayList<Pair<String, UiNode>>()
        for (id in allIds) {
            val found = root.findByViewId(id) ?: return null
            found.filter { it.isVisibleToUser }.forEach { result += id to it }
        }
        return result
    }

    /** Bounded breadth-first traversal collecting visible anchor nodes. */
    private fun traverseAnchors(root: UiNode): List<Pair<String, UiNode>> {
        val result = ArrayList<Pair<String, UiNode>>()
        val queue = ArrayDeque<Pair<UiNode, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (!node.isVisibleToUser) continue
            node.viewId?.takeIf { it in allIds }?.let { result += it to node }
            if (depth < maxDepth) {
                for (i in 0 until node.childCount) node.child(i)?.let { queue.addLast(it to depth + 1) }
            }
        }
        return result
    }

    /** Leaves a bottom navigation bar uncovered so other tabs stay reachable (FR-07). */
    private fun excludeNavigation(region: Bounds, nav: Bounds?): Bounds {
        if (nav == null) return region
        val overlapsBottom = nav.top in (region.top + 1) until region.bottom
        return if (overlapsBottom) region.copy(bottom = nav.top) else region
    }
}

enum class EnforcementAction {
    /** Leave the surface with one Back action. */
    BACK,

    /** Cover the content region and offer a manual exit. */
    COVER,

    /** Wait: a Back action was just taken and the cooldown has not elapsed. */
    WAIT,

    /** The cover is already shown for this episode. */
    KEEP,
}

/**
 * Bounds automated navigation (FR-10):
 * - at most one Back action per detection episode;
 * - at least [cooldownMs] before a fresh state check;
 * - if the surface is still there after the cooldown, and still there at a second check
 *   [confirmMs] later, the episode switches to a cover instead of pressing Back again. The second
 *   check matters because apps can keep a short-video view attached and reported visible for a
 *   moment after Back has already left it (seen with YouTube's tab transitions);
 * - at most [maxBacksPerWindow] automatic Back actions in [windowMs], so an app that re-opens a
 *   short-video surface by itself cannot cause a Back loop.
 */
class DenyEpisodeController(
    private val cooldownMs: Long = 1_000,
    private val confirmMs: Long = 500,
    private val maxBacksPerWindow: Int = 3,
    private val windowMs: Long = 30_000,
) {
    init {
        require(cooldownMs >= 1_000) { "FR-10 requires at least a one-second cooldown" }
    }

    private var episodeActive = false
    private var backAtMs: Long? = null
    private var stillPresentSinceMs: Long? = null
    private var coverShown = false
    private val recentBacks = ArrayDeque<Long>()

    val inEpisode: Boolean get() = episodeActive

    fun onDenied(nowMs: Long): EnforcementAction {
        while (recentBacks.isNotEmpty() && nowMs - recentBacks.first() > windowMs) recentBacks.removeFirst()
        if (!episodeActive) {
            episodeActive = true
            coverShown = false
            stillPresentSinceMs = null
            if (recentBacks.size < maxBacksPerWindow) {
                backAtMs = nowMs
                recentBacks.addLast(nowMs)
                return EnforcementAction.BACK
            }
            backAtMs = null
            coverShown = true
            return EnforcementAction.COVER
        }
        if (coverShown) return EnforcementAction.KEEP
        val back = backAtMs
        if (back != null && nowMs - back < cooldownMs) return EnforcementAction.WAIT
        val since = stillPresentSinceMs
        if (since == null) {
            stillPresentSinceMs = nowMs
            return EnforcementAction.WAIT
        }
        if (nowMs - since < confirmMs) return EnforcementAction.WAIT
        coverShown = true
        return EnforcementAction.COVER
    }

    /** When the next fresh check is due, if the episode is waiting for one. */
    fun freshCheckDueAt(): Long? {
        if (!episodeActive || coverShown) return null
        stillPresentSinceMs?.let { return it + confirmMs }
        return backAtMs?.plus(cooldownMs)
    }

    fun onSurfaceGone() {
        episodeActive = false
        backAtMs = null
        stillPresentSinceMs = null
        coverShown = false
    }
}

/**
 * Detects an unsupported app layout (FR-37) without guessing too early. A visit counts as
 * unrecognized only if it lasted at least [minVisitMs] with at least [minSnapshots] snapshots and
 * no anchor at all. [visitsToFlag] such visits in a row are needed before status changes.
 */
class LayoutRecognitionTracker(
    private val minVisitMs: Long = 15_000,
    private val minSnapshots: Int = 5,
    private val visitsToFlag: Int = 2,
) {
    private var visitStartMs: Long? = null
    private var snapshots = 0
    private var recognizedThisVisit = false
    private var unrecognizedVisits = 0

    var state: LayoutRecognition = LayoutRecognition.UNKNOWN
        private set

    fun onVisitStart(nowMs: Long) {
        visitStartMs = nowMs
        snapshots = 0
        recognizedThisVisit = false
    }

    fun onSnapshot(recognized: Boolean) {
        snapshots++
        if (recognized) {
            recognizedThisVisit = true
            unrecognizedVisits = 0
            state = LayoutRecognition.RECOGNIZED
        }
    }

    fun onVisitEnd(nowMs: Long): LayoutRecognition {
        val start = visitStartMs ?: return state
        visitStartMs = null
        val longEnough = nowMs - start >= minVisitMs && snapshots >= minSnapshots
        if (!recognizedThisVisit && longEnough) {
            unrecognizedVisits++
            if (unrecognizedVisits >= visitsToFlag) state = LayoutRecognition.NOT_RECOGNIZED
        }
        return state
    }
}
