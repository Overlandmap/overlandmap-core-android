package ch.overlandmap.map.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
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
        "cafe" to R.drawable.cafe,
        "camera" to R.drawable.camera,
        "camp_site" to R.drawable.camp_site,
        "checkpoint" to R.drawable.checkpoint,
        "cross_" to R.drawable.cross_,
        "ferry" to R.drawable.ferry,
        "fuel_" to R.drawable.fuel_,
        "historic_" to R.drawable.historic_,
        "hot_spring" to R.drawable.hot_spring,
        "information_" to R.drawable.information_,
        "intersection" to R.drawable.intersection,
        "marker_stroked" to R.drawable.marker_stroked,
        "mountain_pass" to R.drawable.mountain_pass,
        "police_" to R.drawable.police_,
        "religious" to R.drawable.religious,
        "road_block" to R.drawable.road_block,
        "viewpoint_" to R.drawable.viewpoint_,
        "warning" to R.drawable.warning,
        "water_crossing" to R.drawable.water_crossing,
        "waterfall" to R.drawable.waterfall,
        "swimming" to R.drawable.swimming,
    )

    /** Returns the drawable resource ID for a waypoint's maki icon. */
    fun drawableFor(o: WaypointType): Int = drawables[makiFor(o)] ?: drawables[DEFAULT]!!

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
        o.isWaterfall -> "waterfall"
        o.isSwimming -> "swimming"
        o.hasFuel -> "fuel_"
        o.isBridge -> "bridge_"
        o.isWaterCrossing -> "water_crossing"
        o.isBivouac -> "camp_site"
        o.isCafe -> "cafe"
        o.isFerry -> "ferry"
        o.isIntersection -> "intersection"
        else -> DEFAULT
    }

    /** Common badge width; four extra vertical pixels leave room around the circle. */
    private const val SIZE_PX = 30

    /** Map marker: category badge and a separate white name capsule. */
    fun named(context: Context, name: String, icon: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val label = name.trim()
        val labelWidth = if (label.isEmpty()) 0f else paint.measureText(label) + 18f
        val width = SIZE_PX + if (labelWidth > 0) 8 + kotlin.math.ceil(labelWidth).toInt() else 0
        return Bitmap.createBitmap(width, SIZE_PX + 4, Bitmap.Config.ARGB_8888).apply {
            density = DisplayMetrics.DENSITY_DEFAULT
            val canvas = Canvas(this)
            drawBadge(context, canvas, drawables[icon] ?: drawables.getValue(DEFAULT))
            if (labelWidth > 0) {
                paint.color = Color.WHITE
                canvas.drawRoundRect(RectF(38f, 2f, width.toFloat(), 32f), 15f, 15f, paint)
                paint.color = Color.BLACK
                val baseline = 17f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
                canvas.drawText(label, 47f, baseline, paint)
            }
        }
    }

    /** Consistent white circular background for every itinerary map icon. */
    private fun drawBadge(context: Context, canvas: Canvas, resource: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(15f, 17f, 14f, paint)
        paint.color = Color.rgb(160, 160, 160)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawCircle(15f, 17f, 14f, paint)
        ContextCompat.getDrawable(context, resource)?.mutate()?.let { icon ->
            icon.setBounds(6, 7, 24, 27)
            icon.draw(canvas)
        }
    }

    /** Every maki icon in a white badge, keyed by its image id for `style.addImage`. */
    fun bitmaps(context: Context): Map<String, Bitmap> =
        drawables.mapValues { (_, resource) ->
            Bitmap.createBitmap(SIZE_PX, SIZE_PX + 4, Bitmap.Config.ARGB_8888).apply {
                // A real density is required for Mapbox to compute the image scale.
                density = DisplayMetrics.DENSITY_DEFAULT
                drawBadge(context, Canvas(this), resource)
            }
        }
}
