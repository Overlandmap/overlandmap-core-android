package ch.overlandmap.map.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.ui.rememberUnitFormatter
import ch.overlandmap.map.weather.DayForecast
import ch.overlandmap.map.weather.HourForecast
import ch.overlandmap.map.weather.LocationForecast
import ch.overlandmap.map.weather.MonthlyClimate
import ch.overlandmap.map.weather.WeatherAttribution
import ch.overlandmap.map.weather.WeatherException
import ch.overlandmap.map.weather.WeatherFailure
import ch.overlandmap.map.weather.WeatherFormat
import ch.overlandmap.map.weather.WeatherRepository
import coil.compose.AsyncImage
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RainBlue = Color(0xFF1976D2)
private val SunRed = Color(0xFFD32F2F)

/**
 * The itinerary's Weather tab (iOS `WeatherTabView`): an on-demand forecast for the first and last
 * steps, the climate normals of a chosen step in a dual-axis chart, and the Apple Weather
 * attribution WeatherKit requires. Data comes from WeatherKit's REST API.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherTab(steps: List<ItineraryStep>, lang: String) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val repository = app.weatherRepository
    val units = rememberUnitFormatter().format
    val scope = rememberCoroutineScope()

    // Forecast
    var forecastLoading by remember { mutableStateOf(false) }
    var forecastError by remember { mutableStateOf<String?>(null) }
    var forecasts by remember { mutableStateOf<List<LocationForecast>?>(null) }
    var hourly by remember { mutableStateOf(false) }

    // Climate
    var selectedStep by remember { mutableIntStateOf(0) }
    var climate by remember { mutableStateOf<List<MonthlyClimate>?>(null) }
    var climateLoading by remember { mutableStateOf(false) }
    var climateError by remember { mutableStateOf<String?>(null) }
    var stepMenu by remember { mutableStateOf(false) }

    var attribution by remember { mutableStateOf<WeatherAttribution?>(null) }

    val noCoordinate = stringResource(R.string.weather_error_no_coordinate)
    val needsInternet = stringResource(R.string.weather_forecast_needs_internet)
    val unavailable = stringResource(R.string.weather_service_unavailable)
    val invalid = stringResource(R.string.error_loading_climate_data)
    fun message(e: Throwable) = when ((e as? WeatherException)?.failure) {
        WeatherFailure.NO_COORDINATE -> noCoordinate
        WeatherFailure.SERVICE -> unavailable
        WeatherFailure.INVALID_DATA -> invalid
        else -> needsInternet
    }

    LaunchedEffect(Unit) { attribution = repository.attribution() }

    LaunchedEffect(selectedStep, steps.size) {
        val step = steps.getOrNull(selectedStep) ?: return@LaunchedEffect
        val lat = step.lat
        val lon = step.lon
        if (lat == null || lon == null) {
            climateError = noCoordinate
            return@LaunchedEffect
        }
        climateLoading = true
        climateError = null
        try {
            climate = repository.climate(lat, lon)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            climate = null
            climateError = message(e)
        } finally {
            climateLoading = false
        }
    }

    fun loadForecast() {
        val first = steps.firstOrNull() ?: return
        scope.launch {
            forecastLoading = true
            forecastError = null
            try {
                val targets = buildList {
                    add(first)
                    // A one-step itinerary has a single column.
                    steps.lastOrNull()?.takeIf { steps.size > 1 && it.documentId != first.documentId }?.let(::add)
                }
                forecasts = targets.map { step ->
                    val lat = step.lat
                    val lon = step.lon
                    if (lat == null || lon == null) throw WeatherException(WeatherFailure.NO_COORDINATE)
                    repository.forecast(lat, lon, step.fullName(lang))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                forecastError = message(e)
            } finally {
                forecastLoading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ── Forecast ────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val loaded = forecasts
            when {
                loaded != null -> {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !hourly, onClick = { hourly = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text(stringResource(R.string.daily)) }
                        SegmentedButton(
                            selected = hourly, onClick = { hourly = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text(stringResource(R.string.hourly)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        loaded.forEach { forecast ->
                            ForecastColumn(forecast, hourly, units.temperatureCelsius, units.distanceMetric, lang, Modifier.weight(1f))
                        }
                    }
                }
                forecastLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> Button(onClick = ::loadForecast, modifier = Modifier.fillMaxWidth(), enabled = steps.isNotEmpty()) {
                    Icon(Icons.Filled.WbCloudy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  " + stringResource(R.string.weather_forecast))
                }
            }
            forecastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        HorizontalDivider()

        // ── Climate ─────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.climate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (steps.size > 1) {
                Box {
                    TextButton(onClick = { stepMenu = true }) {
                        Text(
                            steps.getOrNull(selectedStep)?.fullName(lang).orEmpty(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = stepMenu, onDismissRequest = { stepMenu = false }) {
                        steps.forEachIndexed { index, step ->
                            DropdownMenuItem(
                                text = { Text(step.fullName(lang), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { selectedStep = index; stepMenu = false },
                            )
                        }
                    }
                }
            }
            val data = climate
            when {
                climateLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                data != null -> {
                    ClimateChart(data, units.temperatureCelsius, units.distanceMetric, lang)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendDot(SunRed, stringResource(R.string.weather_legend_max))
                        LegendDot(SunRed.copy(alpha = 0.5f), stringResource(R.string.weather_legend_min))
                        LegendDot(RainBlue.copy(alpha = 0.4f), if (units.distanceMetric) "mm" else "in")
                    }
                }
                climateError != null -> Text(climateError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        AttributionFooter(attribution)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ── Forecast columns ────────────────────────────────────────────────────────

@Composable
private fun ForecastColumn(
    forecast: LocationForecast,
    hourly: Boolean,
    celsius: Boolean,
    metric: Boolean,
    lang: String,
    modifier: Modifier,
) {
    val locale = remember(lang) { Locale.forLanguageTag(lang) }
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE", locale) }
    val hourFormat = remember(locale) { DateTimeFormatter.ofPattern("HH:mm", locale) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(forecast.stepName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hourly) {
            forecast.hourly.take(24).forEach { h: HourForecast ->
                ForecastRow(
                    condition = h.condition,
                    label = Instant.ofEpochMilli(h.dateMs).atOffset(forecast.offset).format(hourFormat),
                    labelWidth = 44,
                    value = WeatherFormat.temp(h.temperatureCelsius, celsius),
                    precipMm = h.precipitationMm, metric = metric,
                )
            }
        } else {
            forecast.daily.forEach { d: DayForecast ->
                // The rollup starts at local midnight, shown in the site's own zone.
                ForecastRow(
                    condition = d.condition,
                    label = Instant.ofEpochMilli(d.dateMs).atOffset(forecast.offset).format(dayFormat),
                    labelWidth = 34,
                    value = WeatherFormat.tempRange(d.lowCelsius, d.highCelsius, celsius),
                    precipMm = d.precipitationMm, metric = metric,
                )
            }
        }
    }
}

@Composable
private fun ForecastRow(condition: String, label: String, labelWidth: Int, value: String, precipMm: Double, metric: Boolean) {
    val tint = when (WeatherFormat.tone(condition)) {
        WeatherFormat.Tone.RAIN -> RainBlue
        WeatherFormat.Tone.SUN -> SunRed
        WeatherFormat.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(conditionIcon(condition), contentDescription = condition, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(labelWidth.dp), maxLines = 1)
        Text(value, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1)
        Text(
            if (WeatherFormat.hasPrecip(precipMm)) WeatherFormat.precip(precipMm, metric) else "",
            style = MaterialTheme.typography.labelSmall,
            color = RainBlue,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
    }
}

/** A Material icon for a WeatherKit condition code (iOS shows the matching SF Symbol). */
internal fun conditionIcon(code: String): ImageVector = when (code) {
    "Clear", "MostlyClear", "Hot" -> Icons.Filled.WbSunny
    "PartlyCloudy", "SunShowers", "SunFlurries" -> Icons.Filled.CloudQueue
    "MostlyCloudy", "Cloudy" -> Icons.Filled.Cloud
    "Drizzle", "Rain", "HeavyRain", "FreezingDrizzle", "FreezingRain" -> Icons.Filled.WaterDrop
    "IsolatedThunderstorms", "ScatteredThunderstorms", "StrongStorms", "Thunderstorms",
    "Hurricane", "TropicalStorm" -> Icons.Filled.Thunderstorm
    "Snow", "HeavySnow", "Flurries", "Blizzard", "BlowingSnow", "Frigid", "WintryMix", "Sleet" -> Icons.Filled.AcUnit
    "Hail" -> Icons.Filled.Grain
    "Fog", "Haze", "Smoky", "Dust" -> Icons.Filled.Dehaze
    "Breezy", "Windy" -> Icons.Filled.Air
    else -> Icons.Filled.Cloud
}

// ── Climate chart ───────────────────────────────────────────────────────────

/**
 * Twelve months: the min–max temperature band with its two lines (left axis) and the precipitation
 * bars (right axis), the bars scaled into the temperature range exactly as the iOS chart does.
 */
@Composable
private fun ClimateChart(data: List<MonthlyClimate>, celsius: Boolean, metric: Boolean, lang: String) {
    val measurer = rememberTextMeasurer()
    val locale = remember(lang) { Locale.forLanguageTag(lang) }
    val monthNames = remember(locale) {
        (1..12).map { java.time.Month.of(it).getDisplayName(java.time.format.TextStyle.SHORT, locale) }
    }
    val axisStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val precipStyle = TextStyle(fontSize = 10.sp, color = RainBlue)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val range = remember(data, celsius) { WeatherFormat.chartTempRange(data, celsius) }
    val precipMax = remember(data) { maxOf(data.maxOf { it.precipitationMm }, 10.0) }

    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val left = 42.dp.toPx()
        val right = 48.dp.toPx()
        val bottom = 18.dp.toPx()
        val top = 6.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val span = range.endInclusive - range.start
        fun yOf(display: Double) = top + h - ((display - range.start) / span * h).toFloat()
        fun xOf(i: Int) = left + w * (i + 0.5f) / 12f

        // Grid and both axes' labels: five ticks across the temperature range.
        val ticks = 5
        for (t in 0..ticks) {
            val value = range.start + span * t / ticks
            val y = yOf(value)
            drawLine(grid, Offset(left, y), Offset(left + w, y), strokeWidth = 1f)
            val tempLabel = measurer.measure("${Math.round(value)}${if (celsius) "°C" else "°F"}", axisStyle)
            drawText(tempLabel, topLeft = Offset(left - tempLabel.size.width - 4.dp.toPx(), y - tempLabel.size.height / 2f))
            val precip = t.toDouble() / ticks * WeatherFormat.displayPrecip(precipMax, metric)
            val precipLabel = measurer.measure("${Math.round(precip)}${if (metric) " mm" else " in"}", precipStyle)
            drawText(precipLabel, topLeft = Offset(left + w + 4.dp.toPx(), y - precipLabel.size.height / 2f))
        }

        // Precipitation bars, scaled into the temperature range.
        val barWidth = w / 12f * 0.6f
        data.forEachIndexed { i, m ->
            val scaled = range.start + WeatherFormat.displayPrecip(m.precipitationMm, metric) /
                WeatherFormat.displayPrecip(precipMax, metric) * span
            val y = yOf(scaled)
            drawRect(RainBlue.copy(alpha = 0.2f), Offset(xOf(i) - barWidth / 2, y), androidx.compose.ui.geometry.Size(barWidth, yOf(range.start) - y))
        }

        // Band between min and max.
        val band = Path().apply {
            data.forEachIndexed { i, m ->
                val p = Offset(xOf(i), yOf(WeatherFormat.displayTemp(m.maxTempCelsius, celsius)))
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            for (i in data.indices.reversed()) {
                lineTo(xOf(i), yOf(WeatherFormat.displayTemp(data[i].minTempCelsius, celsius)))
            }
            close()
        }
        drawPath(band, SunRed.copy(alpha = 0.15f))

        fun line(color: Color, pick: (MonthlyClimate) -> Double) {
            val path = Path()
            data.forEachIndexed { i, m ->
                val y = yOf(WeatherFormat.displayTemp(pick(m), celsius))
                if (i == 0) path.moveTo(xOf(i), y) else path.lineTo(xOf(i), y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            data.forEachIndexed { i, m ->
                drawCircle(color, 3.dp.toPx(), Offset(xOf(i), yOf(WeatherFormat.displayTemp(pick(m), celsius))))
            }
        }
        line(SunRed) { it.maxTempCelsius }
        line(SunRed.copy(alpha = 0.5f)) { it.minTempCelsius }

        // Every other month, as on iOS.
        for (i in data.indices step 2) {
            val label = measurer.measure(monthNames[data[i].month - 1], axisStyle)
            drawText(label, topLeft = Offset(xOf(i) - label.size.width / 2f, size.height - label.size.height))
        }
    }
}

// ── Attribution ─────────────────────────────────────────────────────────────

/**
 * Apple requires the Apple Weather mark and a link to its legal page wherever WeatherKit data is
 * shown. The logo variant matching the current theme is loaded from Apple; until then (or when it
 * can't be) the text mark stands in.
 */
@Composable
private fun AttributionFooter(attribution: WeatherAttribution?) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val logo = if (dark) attribution?.darkLogoUrl else attribution?.lightLogoUrl
    val legal = attribution?.legalPageUrl ?: WeatherRepository.LEGAL_PAGE
    Column(
        Modifier.fillMaxWidth().clickable {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, legal.toUri())
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = stringResource(R.string.weather_apple_attribution),
                modifier = Modifier.height(16.dp),
            )
        } else {
            Text(stringResource(R.string.weather_apple_attribution), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            stringResource(R.string.weather_data_sources),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
