package ch.overlandmap.map.navigation

import kotlin.math.max

/** An axis-aligned rectangle in map pixels (or dp), top-left origin. Like `CGRect.contains`, the right/bottom edges are outside. */
data class ScreenRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2

    fun contains(x: Double, y: Double): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Shrunk by [dx] on the left and right and [dy] on top and bottom; empty (contains nothing) when it collapses. */
    fun inset(dx: Double, dy: Double): ScreenRect {
        val l = left + dx
        val r = right - dx
        val t = top + dy
        val b = bottom - dy
        return if (l > r || t > b) ScreenRect(0.0, 0.0, 0.0, 0.0) else ScreenRect(l, t, r, b)
    }
}

/**
 * Where the navigation map keeps the user, and when it moves the camera to put them back there
 * (iOS `AutoCenterZone`).
 *
 * The HUD covers half of the map, the top half in portrait and the left half in landscape, so the
 * part of the map the user can see is the other half: the bottom half in portrait, the right half
 * in landscape. The user is centred in that visible half, and the camera only moves again once
 * they have drifted to within [MARGIN_FRACTION] of the map size from one of its edges.
 */
object AutoCenterZone {
    /** How close to an edge of the visible half the user may get, as a fraction of the map's width or height. */
    const val MARGIN_FRACTION = 0.10

    /** A re-centre animates for about this long; fixes arriving meanwhile must not start another. */
    const val RECENTER_COOLDOWN_MS = 1000L

    /**
     * The part of a map of the given size that the HUD leaves visible, in the map's own coordinates:
     * the bottom half in portrait, the right half in landscape. The HUD can be larger than half the
     * map (it is at least 320 dp tall, and sits below the top bar), so [hudBottom] (portrait) and
     * [hudRight] (landscape), the edges of the HUD in map pixels, push the visible area further when
     * they go past the middle. The user must never be kept under the HUD.
     */
    fun visibleRect(mapWidth: Double, mapHeight: Double, landscape: Boolean, hudBottom: Double = 0.0, hudRight: Double = 0.0): ScreenRect =
        if (landscape) ScreenRect(max(mapWidth / 2, hudRight).coerceAtMost(mapWidth), 0.0, mapWidth, mapHeight)
        else ScreenRect(0.0, max(mapHeight / 2, hudBottom).coerceAtMost(mapHeight), mapWidth, mapHeight)

    /** The part of the visible area in which the user is left alone. */
    fun stableRect(
        mapWidth: Double, mapHeight: Double, landscape: Boolean, hudBottom: Double = 0.0, hudRight: Double = 0.0,
    ): ScreenRect =
        visibleRect(mapWidth, mapHeight, landscape, hudBottom, hudRight).inset(mapWidth * MARGIN_FRACTION, mapHeight * MARGIN_FRACTION)

    /** Whether a user drawn at ([x], [y]) has left the stable area, so the camera should bring them back to the centre. */
    fun needsRecentering(
        x: Double, y: Double, mapWidth: Double, mapHeight: Double, landscape: Boolean,
        hudBottom: Double = 0.0, hudRight: Double = 0.0,
    ): Boolean = !stableRect(mapWidth, mapHeight, landscape, hudBottom, hudRight).contains(x, y)
}

/** The sizes of the navigation screen's panels (dp). */
object NavigationLayout {
    const val HUD_TOP_PADDING = 8.0
    const val HUD_EDGE_PADDING = 16.0

    /** Space between the landscape HUD and the middle of the screen. */
    const val HUD_GAP = 8.0
    const val MIN_HUD_HEIGHT = 320.0
    const val HUD_HEIGHT_FRACTION = 0.48

    /** Wider than tall, read from the laid-out window rather than the device orientation. */
    fun isLandscape(width: Double, height: Double): Boolean = width > height

    /** Portrait: the HUD covers the top of the screen. */
    fun hudHeight(height: Double): Double = max(MIN_HUD_HEIGHT, height * HUD_HEIGHT_FRACTION)

    /** Landscape: the HUD fills the left half. */
    fun landscapeHudWidth(width: Double): Double = max(0.0, width / 2 - HUD_EDGE_PADDING - HUD_GAP)
}
