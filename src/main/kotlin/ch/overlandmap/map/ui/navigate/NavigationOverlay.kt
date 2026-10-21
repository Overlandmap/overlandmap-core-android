package ch.overlandmap.map.ui.navigate

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import ch.overlandmap.map.R
import ch.overlandmap.map.navigation.CrossTrackSide
import ch.overlandmap.map.navigation.NavigationMode
import ch.overlandmap.map.navigation.NavigationState
import ch.overlandmap.map.navigation.RoadStep
import ch.overlandmap.map.units.UnitFormatter
import ch.overlandmap.map.ui.rememberUnitFormatter
import kotlin.math.max

private val PanelShape = RoundedCornerShape(22.dp)
private val OnTrackLight = Color(0.3f, 0.95f, 0.4f)
private val OnTrackDark = Color(0.15f, 0.7f, 0.25f)
private val OffTrackLight = Color(1.0f, 0.35f, 0.35f)
private val OffTrackDark = Color(0.75f, 0.15f, 0.15f)
private val HeadingYellow = Color(0xFFFFEB3B)

/**
 * The panel over half of the navigation map (iOS `NavigationOverlayView`): the target step, its
 * distance and a compass whose needle points the way, or, while the start is far away, the road
 * directions. It takes no touches: every gesture goes to the map.
 *
 * Its size is the caller's; the content is stacked the same way in portrait and landscape.
 */
@Composable
fun NavigationOverlay(state: NavigationState, cameraBearing: Double, modifier: Modifier = Modifier) {
    val offTrack = state.isOffTrack
    LaunchedEffect(state.isNavigating, state.mode, state.steps.size) {
        Log.d("NavDebug", "HUD state: navigating=${state.isNavigating} mode=${state.mode} steps=${state.steps.size} location=${state.currentLocation != null}")
    }
    Box(
        modifier
            .shadow(8.dp, PanelShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .background(Color.Black.copy(alpha = 0.75f), PanelShape)
            .border(
                width = if (offTrack) 2.dp else 1.dp,
                color = if (offTrack) Color.Red.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.18f),
                shape = PanelShape,
            )
            .padding(16.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (state.mode == NavigationMode.ROAD_TO_START) RoadDirections(state) else BearingInstructions(state, cameraBearing)
        }
    }
}

// ── Itinerary mode ──────────────────────────────────────────────────────────

@Composable
private fun BearingInstructions(state: NavigationState, cameraBearing: Double) {
    val units = rememberUnitFormatter()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight * 1.2f
        LaunchedEffect(maxWidth, maxHeight, state.currentTargetIndex, state.distanceToTargetMeters != null) {
            Log.d(
                "NavDebug",
                "HUD content: ${maxWidth.value.toInt()}x${maxHeight.value.toInt()} dp, layout=${if (wide) "wide" else "tall"}, " +
                    "step=${state.currentTargetStep?.name(state.language)} index=${state.currentTargetIndex}/${state.steps.size}, " +
                    "distance=${state.distanceToTargetMeters?.toInt()} m, rel=${state.relativeBearingToTarget(cameraBearing)}, " +
                    "heading=${state.deviceHeading(cameraBearing)}",
            )
        }
        if (wide) {
            // A wide panel (portrait): the text on the left, and the dial as tall as the panel on the right.
            val side = min(maxHeight, maxWidth * 0.6f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepHeader(state, units)
                    ShrinkingText(text = units.navigationDistance(state.distanceToTargetMeters).ifEmpty { "--" }, modifier = Modifier.fillMaxWidth())
                }
                FillingCompass(state, units, cameraBearing, Modifier.size(side))
            }
        } else {
            // A tall panel (landscape): step and distance on top, the dial filling the rest.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepHeader(state, units)
                ShrinkingText(
                    text = units.navigationDistance(state.distanceToTargetMeters).ifEmpty { "--" },
                    modifier = Modifier.fillMaxWidth(),
                )
                FillingCompass(state, units, cameraBearing, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The target step's number, name and elevation. */
@Composable
private fun StepHeader(state: NavigationState, units: UnitFormatter) {
    val step = state.currentTargetStep ?: return
    val elevation = step.ele?.let { units.meters(it.toDouble()) }?.takeIf { it.isNotEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${state.currentTargetIndex + 1}.",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Color.White.copy(alpha = 0.2f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Text(
            step.name(state.language),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (elevation != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Icon(Icons.Filled.Terrain, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(elevation, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Very large, heavy digits on one line; the text shrinks to 70% rather than wrapping or being
 * cut. (No rounded design on Android: the default family stands in.)
 */
@Composable
private fun ShrinkingText(text: String, modifier: Modifier = Modifier, size: Int = 34, align: TextAlign = TextAlign.Start) {
    var scale by remember(text) { mutableFloatStateOf(1f) }
    Text(
        text,
        modifier = modifier,
        fontSize = (size * scale).sp,
        fontWeight = FontWeight.ExtraBold,
        style = TextStyle(fontFeatureSettings = "tnum"),
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { if (it.didOverflowWidth && scale > 0.7f) scale = max(0.7f, scale - 0.05f) },
    )
}

/**
 * The dial as large as the space allows (a square fitted into it), with the drift badge and the
 * off-track badge laid over the corners of that square, which a circle leaves empty.
 */
@Composable
private fun FillingCompass(state: NavigationState, units: UnitFormatter, cameraBearing: Double, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth, maxHeight)
        val relative = state.relativeBearingToTarget(cameraBearing)
        Box(Modifier.size(side)) {
            // Nothing is drawn until a bearing is known; until the first fix, say what is awaited.
            if (relative == null && state.currentLocation == null) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                    Text(
                        stringResource(R.string.navigation_waiting_gps),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            if (relative != null) {
                CompassDial(
                    relativeBearing = relative,
                    ringHeading = state.deviceHeading(cameraBearing),
                    // The direction-of-travel status, which is only re-evaluated while moving.
                    isOnTrack = !state.isOffTrack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val cross = state.crossTrackDistanceMeters
            if (state.crossTrackSide == CrossTrackSide.LEFT && cross != null) {
                CrossTrackBadge(CrossTrackSide.LEFT, cross, units, Modifier.align(Alignment.BottomStart))
            }
            if (state.crossTrackSide == CrossTrackSide.RIGHT && cross != null) {
                CrossTrackBadge(CrossTrackSide.RIGHT, cross, units, Modifier.align(Alignment.BottomEnd))
            }
            if (state.isOffTrack) OffTrackBadge(Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun CrossTrackBadge(side: CrossTrackSide, distance: Double, units: UnitFormatter, modifier: Modifier) {
    val text = units.navigationDistance(distance).ifEmpty { "${distance.toInt()} m" }
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
            .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Left of the track: it is to the right.
        Icon(
            if (side == CrossTrackSide.LEFT) Icons.Filled.ArrowForward else Icons.Filled.ArrowBack,
            contentDescription = null, tint = HeadingYellow, modifier = Modifier.size(18.dp),
        )
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun OffTrackBadge(modifier: Modifier) {
    Row(
        modifier.background(Color.Red.copy(alpha = 0.85f), CircleShape).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = HeadingYellow, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.off_track), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// ── Road directions ─────────────────────────────────────────────────────────

@Composable
private fun RoadDirections(state: NavigationState) {
    val units = rememberUnitFormatter()
    val step = state.currentRoadStep
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White.copy(alpha = 0.85f))
            Text(stringResource(R.string.directions), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f))
        }
        Spacer(Modifier.weight(1f))

        if (state.isCalculatingRoadRoute) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Text(stringResource(R.string.calculating_route), fontSize = 14.sp)
            }
        } else if (step != null) {
            Text(
                step.instruction,
                fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            state.distanceToCurrentRoadStepMeters?.let { distance ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(maneuverIcon(step), contentDescription = null, modifier = Modifier.size(32.dp))
                    Text(
                        units.navigationDistance(distance),
                        fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        state.distanceToStartPointMeters?.let { total ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.SportsScore, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.7f))
                Text(units.navigationDistance(total), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The arrow for a road maneuver's modifier. */
internal fun maneuverIcon(step: RoadStep): ImageVector = maneuverIcon(step.maneuver.modifier)

internal fun maneuverIcon(modifier: String?): ImageVector = when (modifier) {
    "left", "sharp left" -> Icons.Filled.TurnLeft
    "slight left" -> Icons.Filled.NorthWest
    "right", "sharp right" -> Icons.Filled.TurnRight
    "slight right" -> Icons.Filled.NorthEast
    "uturn" -> Icons.Filled.UTurnLeft
    else -> Icons.Filled.ArrowUpward
}

// ── Compass dial ────────────────────────────────────────────────────────────

/** The dial was designed at this diameter; every fixed size is multiplied by `side / DESIGN_SIDE` (at least 0.75). */
private const val DESIGN_SIDE_DP = 160f

/**
 * An angle that follows [target] along the shortest way round (so 359° to 1° turns by 2°, not 358°)
 * with a 0.2 s ease-in-out.
 */
@Composable
private fun animatedAngle(target: Float): Float {
    val animatable = remember { Animatable(target) }
    LaunchedEffect(target) {
        val current = animatable.value
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        animatable.animateTo(current + delta, tween(durationMillis = 200, easing = FastOutSlowInEasing))
    }
    return animatable.value
}

/**
 * The compass: the ring (N in red) turns against [ringHeading] so N stays at the real north; a
 * fixed yellow mark on top means "straight ahead"; the single-half needle swings by
 * [relativeBearing], green on track and red off it.
 */
@Composable
fun CompassDial(relativeBearing: Double, ringHeading: Double?, isOnTrack: Boolean, modifier: Modifier = Modifier) {
    val ringAngle = animatedAngle(-(ringHeading ?: 0.0).toFloat())
    val needleAngle = animatedAngle(relativeBearing.toFloat())
    val measurer = rememberTextMeasurer()
    val needleLight = if (isOnTrack) OnTrackLight else OffTrackLight
    val needleDark = if (isOnTrack) OnTrackDark else OffTrackDark

    Canvas(modifier) {
        val side = size.minDimension
        val k = max(0.75f, side / density / DESIGN_SIDE_DP)
        val center = Offset(size.width / 2, size.height / 2)
        val radius = side / 2 - 12.dp.toPx() * k

        // 1. The rotating ring with its ticks and N, E, S, W.
        rotate(ringAngle, center) {
            drawCircle(Color.White.copy(alpha = 0.08f), radius = side / 2, center = center)
            drawCircle(Color.White.copy(alpha = 0.3f), radius = side / 2 - 1.dp.toPx() * k, center = center, style = Stroke(2.dp.toPx() * k))
            for (i in 0 until 12) {
                val cardinal = i % 3 == 0
                rotate(i * 30f, center) {
                    drawLine(
                        Color.White.copy(alpha = if (cardinal) 0.75f else 0.35f),
                        Offset(center.x, center.y - radius),
                        Offset(center.x, center.y - radius + (if (cardinal) 8 else 4).dp.toPx() * k),
                        strokeWidth = (if (cardinal) 2 else 1).dp.toPx() * k,
                    )
                }
            }
            fun label(text: String, size: Float, weight: FontWeight, color: Color, dx: Float, dy: Float) {
                val layout = measurer.measure(text, TextStyle(fontSize = (size * k).sp, fontWeight = weight, color = color))
                drawText(layout, topLeft = Offset(center.x + dx - layout.size.width / 2f, center.y + dy - layout.size.height / 2f))
            }
            val inset = 15.dp.toPx() * k
            label("N", 15f, FontWeight.Black, Color.Red, 0f, -radius + inset)
            label("E", 13f, FontWeight.Bold, Color.White, radius - inset, 0f)
            label("S", 13f, FontWeight.Bold, Color.White.copy(alpha = 0.85f), 0f, radius - inset)
            label("W", 13f, FontWeight.Bold, Color.White, -radius + inset, 0f)
        }

        // 2. The fixed mark on top: straight ahead.
        val markWidth = 9.dp.toPx() * k
        val markTop = 2.dp.toPx() * k
        drawPath(
            Path().apply {
                moveTo(center.x - markWidth / 2, markTop)
                lineTo(center.x + markWidth / 2, markTop)
                lineTo(center.x, markTop + 7.dp.toPx() * k)
                close()
            },
            HeadingYellow,
        )

        // 3. The single-half needle toward the target: tip at 16% of the height, half base 7% of the width.
        rotate(needleAngle, center) {
            val tipY = center.y - side / 2 + side * 0.16f
            val halfBase = side * 0.07f
            drawPath(
                Path().apply { moveTo(center.x, tipY); lineTo(center.x, center.y); lineTo(center.x - halfBase, center.y); close() },
                needleLight,
            )
            drawPath(
                Path().apply { moveTo(center.x, tipY); lineTo(center.x + halfBase, center.y); lineTo(center.x, center.y); close() },
                needleDark,
            )
        }

        // 4. The pivot hub.
        drawCircle(Color.Black.copy(alpha = 0.3f), radius = 8.dp.toPx() * k, center = center.copy(y = center.y + 1.dp.toPx() * k))
        drawCircle(Color.White, radius = 7.dp.toPx() * k, center = center)
        drawCircle(Color.Black.copy(alpha = 0.85f), radius = 3.dp.toPx() * k, center = center)
    }
}
