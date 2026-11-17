package ch.overlandmap.map.ui.world

import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import ch.overlandmap.map.data.routes.TripPlannerState
import ch.overlandmap.map.planning.RoutePoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.hypot

/**
 * In planning mode, a drag that starts on the line of the trip being edited inserts a flag where it
 * began and keeps dragging it, re-routing live, as if the user had grabbed an existing flag.
 *
 * It watches the map's touches. A press that does not start on the trip line, a second finger, or a
 * press that ends without moving all stay with the map (a plain tap is handled by the map's click
 * listener, which also inserts a flag on the line). Once a single finger that started on the line
 * has moved past the touch slop, the map's own gesture is cancelled and this takes over.
 *
 * [onFlagMoved] reports where the dragged flag is while it follows the finger, and null once the
 * drag is over.
 */
internal class TripLineDrag(
    private val map: () -> MapLibreMap?,
    private val density: Float,
    private val routes: () -> OverlandingRoutes,
    private val planner: () -> TripPlannerState,
    private val actions: () -> OverlandingRouteActions,
    private val onFlagMoved: (flagId: String, at: LatLng?) -> Unit,
) {
    private var down: PointF? = null
    private var downTap: PlanTap? = null
    private var flagId: String? = null
    private var dragging = false

    /** The touch listener of the map view; true when the event was used here. */
    fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                val m = map() ?: return false
                if (!routes().planning) return false
                val trip = planner().activeTrip ?: return false
                val point = PointF(event.x, event.y)
                val segment = OverlandingRouteLayers.tripSegmentAt(m, point, density, trip) ?: return false
                val at = m.projection.fromScreenLocation(point)
                down = point
                downTap = PlanTap(segment, RoutePoint(at.longitude, at.latitude), onTrip = true)
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is a pinch or a rotation: the map's.
                if (!dragging) reset()
                return dragging
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    follow(event.x, event.y)
                    return true
                }
                val start = down ?: return false
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                if (event.pointerCount != 1 || hypot(event.x - start.x, event.y - start.y) <= slop) return false
                // Take over: the map forgets this gesture, and the flag is inserted where it began.
                val cancel = MotionEvent.obtain(event).also { it.action = MotionEvent.ACTION_CANCEL }
                view.onTouchEvent(cancel)
                cancel.recycle()
                dragging = true
                flagId = downTap?.let { actions().onInsertFlag(it) }
                follow(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasDragging = dragging
                if (wasDragging) finish(event.x, event.y)
                reset()
                return wasDragging
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                if (wasDragging) {
                    actions().onCancelPreview()
                    flagId?.let { onFlagMoved(it, null) }
                }
                reset()
                return wasDragging
            }
        }
        return dragging
    }

    private fun reset() {
        down = null
        downTap = null
        flagId = null
        dragging = false
    }

    private fun target(x: Float, y: Float): Pair<PlanTap, LatLng>? {
        val m = map() ?: return null
        val point = PointF(x, y)
        val at = m.projection.fromScreenLocation(point)
        val segment = OverlandingRouteLayers.nearestSegment(m, point, density) ?: return null
        return PlanTap(segment, RoutePoint(at.longitude, at.latitude)) to at
    }

    /** The flag follows the finger, and the route is recomputed as if it were dropped here. */
    private fun follow(x: Float, y: Float) {
        val id = flagId ?: return
        val m = map() ?: return
        onFlagMoved(id, m.projection.fromScreenLocation(PointF(x, y)))
        target(x, y)?.let { (tap, _) -> actions().onPreviewFlag(id, tap) }
    }

    private fun finish(x: Float, y: Float) {
        val id = flagId
        if (id == null) {
            actions().onCancelPreview()
            return
        }
        val tap = target(x, y)?.first
        if (tap != null) actions().onMoveFlag(id, tap) else actions().onCancelPreview()
        onFlagMoved(id, null)
    }
}
