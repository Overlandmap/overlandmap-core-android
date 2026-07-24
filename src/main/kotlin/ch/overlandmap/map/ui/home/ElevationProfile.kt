package ch.overlandmap.map.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.TrackPoint
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val FillColor = Color(0xFF8B7B5B)
private val LineColor = Color(0xFF6F6042)
private val CursorColor = Color(0xFF1A1A1A)

/** The geographic point under the profile cursor, reported while dragging. */
data class ElevationCursor(val lat: Double, val lon: Double)

/** One resampled, smoothed profile sample: distance/elevation (metres) and its position. */
private data class ProfilePoint(
    val distanceM: Double,
    val eleM: Double,
    val lat: Double,
    val lon: Double,
)

/**
 * Interactive elevation profile of [track]: a filled line of altitude (Y, in
 * feet or metres per settings) over distance (X). The geometry is resampled
 * evenly by distance and smoothed. Press-and-hold anywhere shows a vertical
 * cursor with the altitude and distance under the finger; releasing hides it.
 */
@Composable
fun ElevationProfile(
    track: Track,
    useMiles: Boolean,
    useFeet: Boolean,
    modifier: Modifier = Modifier,
    onCursor: (ElevationCursor?) -> Unit = {},
) {
    val samples = remember(track.documentId) { buildProfile(track) }
    if (samples.size < 2) return

    // Everything is plotted in display units so the Y axis reads in the chosen unit.
    fun disp(eleM: Double) = if (useFeet) eleM * 3.28084 else eleM
    val minD = samples.minOf { disp(it.eleM) }
    val maxD = samples.maxOf { disp(it.eleM) }
    if (maxD - minD < 1.0) return // flat or missing elevation — nothing useful to show

    val step = niceStep((maxD - minD) / 4.0)
    val axisMin = floor(minD / step) * step
    val axisMax = ceil(maxD / step) * step
    val ticks = remember(axisMin, axisMax, step) {
        generateSequence(axisMin) { it + step }.takeWhile { it <= axisMax + step * 0.5 }.toList()
    }
    val total = samples.last().distanceM

    val density = LocalDensity.current
    val labelPx = with(density) { 12.sp.toPx() }
    val readoutPx = with(density) { 13.sp.toPx() }
    val labelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.toArgb()

    var cursorX by remember { mutableStateOf<Float?>(null) }
    val currentOnCursor by rememberUpdatedState(onCursor)

    Canvas(
        modifier = modifier.pointerInput(samples) {
            val leftPx = 52.dp.toPx()
            awaitEachGesture {
                fun report(xPos: Float) {
                    cursorX = xPos
                    val plotW = size.width - leftPx
                    if (plotW <= 0f) return
                    val t = ((xPos - leftPx) / plotW).coerceIn(0f, 1f)
                    val idx = (t * (samples.size - 1)).roundToInt().coerceIn(0, samples.lastIndex)
                    currentOnCursor(ElevationCursor(samples[idx].lat, samples[idx].lon))
                }
                val down = awaitFirstDown(requireUnconsumed = false)
                report(down.position.x)
                do {
                    val event = awaitPointerEvent()
                    // Consume so the enclosing vertical scroll doesn't steal the hold.
                    event.changes.forEach { if (it.pressed) it.consume() }
                    event.changes.firstOrNull { it.pressed }?.let { report(it.position.x) }
                } while (event.changes.any { it.pressed })
                cursorX = null
                currentOnCursor(null)
            }
        },
    ) {
        val gutter = 52.dp.toPx()
        val topPad = 20.dp.toPx()
        val bottomPad = 6.dp.toPx()
        val left = gutter
        val right = size.width
        val top = topPad
        val bottom = size.height - bottomPad
        val plotW = right - left
        val plotH = bottom - top
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        fun x(distanceM: Double) = left + (distanceM / total).toFloat() * plotW
        fun y(dispEle: Double) =
            bottom - ((dispEle - axisMin) / (axisMax - axisMin)).toFloat() * plotH

        // Filled area under the profile.
        val area = Path().apply {
            moveTo(left, bottom)
            samples.forEach { lineTo(x(it.distanceM), y(disp(it.eleM))) }
            lineTo(right, bottom)
            close()
        }
        drawPath(area, FillColor)

        // The profile line on top.
        val line = Path().apply {
            samples.forEachIndexed { i, p ->
                val px = x(p.distanceM)
                val py = y(disp(p.eleM))
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(line, LineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))

        // Y axis line and tick labels (numbers only, unit implied).
        drawLine(Color(labelColor), Offset(left, top), Offset(left, bottom), strokeWidth = 1.5.dp.toPx())
        val axisPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = labelColor
            textSize = labelPx
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val tickPaintLen = 4.dp.toPx()
        ticks.forEach { tick ->
            val ty = y(tick)
            if (ty in top - 1..bottom + 1) {
                drawLine(Color(labelColor), Offset(left - tickPaintLen, ty), Offset(left, ty), strokeWidth = 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(
                    tick.roundToInt().toString(),
                    left - tickPaintLen - 4.dp.toPx(),
                    ty + labelPx / 3f,
                    axisPaint,
                )
            }
        }

        // Press-and-hold cursor with altitude + distance readout.
        cursorX?.let { rawX ->
            val cx = rawX.coerceIn(left, right)
            val t = ((cx - left) / plotW).coerceIn(0f, 1f)
            val idx = (t * (samples.size - 1)).roundToInt().coerceIn(0, samples.lastIndex)
            val sample = samples[idx]
            drawLine(CursorColor, Offset(cx, top), Offset(cx, bottom), strokeWidth = 1.5.dp.toPx())

            val altText = UserPreferences.formatElevationM(sample.eleM.roundToInt(), useFeet)
            val distText = UserPreferences.formatDistanceKm(sample.distanceM / 1000.0, useMiles)
            val nearRight = cx > left + plotW * 0.75f
            val readoutPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = labelColor
                textSize = readoutPx
                textAlign = if (nearRight) android.graphics.Paint.Align.RIGHT
                else android.graphics.Paint.Align.LEFT
            }
            val tx = if (nearRight) cx - 5.dp.toPx() else cx + 5.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(altText, tx, top + readoutPx * 0.9f, readoutPaint)
            drawContext.canvas.nativeCanvas.drawText(distText, tx, top + readoutPx * 2.1f, readoutPaint)
        }
    }
}

/**
 * Decodes [track], accumulates distance with the haversine formula, resamples
 * to a fixed number of points evenly spaced by distance, then smooths the
 * elevation with a small moving average.
 */
private fun buildProfile(track: Track): List<ProfilePoint> {
    val pts = track.coordinates()
    if (pts.size < 2) return emptyList()

    val raw = ArrayList<ProfilePoint>(pts.size)
    var dist = 0.0
    raw.add(ProfilePoint(0.0, pts[0].ele, pts[0].lat, pts[0].lon))
    for (i in 1 until pts.size) {
        dist += haversineMeters(pts[i - 1], pts[i])
        raw.add(ProfilePoint(dist, pts[i].ele, pts[i].lat, pts[i].lon))
    }
    val total = raw.last().distanceM
    if (total <= 0.0) return emptyList()

    val n = 200
    val resampled = ArrayList<ProfilePoint>(n + 1)
    var j = 0
    for (k in 0..n) {
        val d = total * k / n
        while (j < raw.size - 2 && raw[j + 1].distanceM < d) j++
        val a = raw[j]
        val b = raw[minOf(j + 1, raw.size - 1)]
        val span = b.distanceM - a.distanceM
        val t = if (span > 0.0) ((d - a.distanceM) / span).coerceIn(0.0, 1.0) else 0.0
        resampled.add(
            ProfilePoint(
                distanceM = d,
                eleM = a.eleM + (b.eleM - a.eleM) * t,
                lat = a.lat + (b.lat - a.lat) * t,
                lon = a.lon + (b.lon - a.lon) * t,
            ),
        )
    }
    return smooth(resampled, window = 5)
}

/** Moving-average smoothing of the elevation over a [window] of samples. */
private fun smooth(points: List<ProfilePoint>, window: Int): List<ProfilePoint> {
    if (points.size <= window) return points
    val half = window / 2
    return points.mapIndexed { i, p ->
        val from = maxOf(0, i - half)
        val to = minOf(points.lastIndex, i + half)
        var sum = 0.0
        for (k in from..to) sum += points[k].eleM
        p.copy(eleM = sum / (to - from + 1))
    }
}

private const val EARTH_RADIUS_M = 6_371_000.0

private fun haversineMeters(a: TrackPoint, b: TrackPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * kotlin.math.asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** A "nice" axis step (1/2/5 × 10ⁿ) at least as large as [rough]. */
private fun niceStep(rough: Double): Double {
    if (rough <= 0.0) return 1.0
    val mag = 10.0.pow(floor(log10(rough)))
    val norm = rough / mag
    val nice = when {
        norm < 1.5 -> 1.0
        norm < 3.0 -> 2.0
        norm < 7.0 -> 5.0
        else -> 10.0
    }
    return nice * mag
}
