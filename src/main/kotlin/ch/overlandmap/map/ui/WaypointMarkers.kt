package ch.overlandmap.map.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import ch.overlandmap.map.R
import ch.overlandmap.map.model.WaypointType

/**
 * The maki-png marker icons (res/drawable-nodpi), keyed by the name stored in a
 * waypoint's `maki` field — which matches the png file name. Registered on the
 * map style once, then referenced per waypoint by that name.
 */
object WaypointMarkers {

    /** Shown when a waypoint has no `maki` (or an unknown one). */
    const val DEFAULT = "marker_stroked"

    /** maki name (= image id) → drawable. */
    private val drawables: Map<String, Int> = mapOf(
        "bridge_" to R.drawable.bridge_,
        "camera" to R.drawable.camera,
        "camp_site" to R.drawable.camp_site,
        "checkpoint" to R.drawable.checkpoint,
        "cross_" to R.drawable.cross_,
        "fuel_" to R.drawable.fuel_,
        "historic_" to R.drawable.historic_,
        "hot_spring" to R.drawable.hot_spring,
        "information_" to R.drawable.information_,
        "marker_stroked" to R.drawable.marker_stroked,
        "mountain_pass" to R.drawable.mountain_pass,
        "police_" to R.drawable.police_,
        "religious" to R.drawable.religious,
        "road_block" to R.drawable.road_block,
        "viewpoint_" to R.drawable.viewpoint_,
        "warning" to R.drawable.warning,
        "water_crossing" to R.drawable.water_crossing,
    )

    /**
     * The marker image id for any [WaypointType] (step or waypoint), derived
     * from its point-of-interest flags — a port of the Flutter app's
     * `translateToMaki`. The stored `maki` field is unreliable (often absent or
     * a generic "marker-stroked"), so the flags are the source of truth. Falls
     * back to [DEFAULT] when no flag applies. Every result is a bundled icon.
     */
    fun makiFor(o: WaypointType): String = when {
        o.isBorder -> "police_"
        o.isPoliceCheckpoint -> "checkpoint"
        o.isMountainPass -> "mountain_pass"
        o.isEmbassy -> "information_"
        o.isViewpoint -> "camera"
        o.isReligiousSite -> "religious"
        o.isHistoricalSite -> "historic_"
        o.isHotSpring -> "hot_spring"
        o.hasFuel -> "fuel_"
        o.isBridge -> "bridge_"
        o.isWaterCrossing -> "water_crossing"
        o.isBivouac -> "camp_site"
        else -> DEFAULT
    }

    /** Every maki icon as a bitmap keyed by its image id, for `style.addImage`. */
    fun bitmaps(context: Context): Map<String, Bitmap> =
        drawables.mapNotNull { (name, res) ->
            val drawable = ContextCompat.getDrawable(context, res) ?: return@mapNotNull null
            // Copy (don't mutate the cached resource bitmap) and give it a real
            // density: a drawable-nodpi bitmap reports DENSITY_NONE, which makes
            // Mapbox's addImage compute a zero scale and draw nothing.
            val bitmap = drawable.toBitmap().copy(Bitmap.Config.ARGB_8888, false).apply {
                density = DisplayMetrics.DENSITY_DEFAULT
            }
            name to bitmap
        }.toMap()
}
