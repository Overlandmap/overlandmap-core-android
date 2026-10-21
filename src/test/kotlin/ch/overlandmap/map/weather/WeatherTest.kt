package ch.overlandmap.map.weather

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

class WeatherTest {

    @get:Rule val tmp = TemporaryFolder()

    private val forecastJson = """
        {"forecastDaily":{"days":[
          {"forecastStart":"2026-07-22T19:00:00Z","conditionCode":"Rain","temperatureMax":21.5,"temperatureMin":9.0,
           "precipitationAmount":3.2,"daytimeForecast":{"windSpeed":14.0}},
          {"forecastStart":"2026-07-23T19:00:00Z","conditionCode":"Clear","temperatureMax":25.0,"temperatureMin":11.0},
          {"conditionCode":"Clear","temperatureMax":1,"temperatureMin":0}
        ]},
         "forecastHourly":{"hours":[
          {"forecastStart":"2026-07-23T10:00:00Z","conditionCode":"PartlyCloudy","temperature":18.2,"precipitationAmount":0.0,"windSpeed":9.5}
        ]}}
    """.trimIndent()

    private fun months(): String = (1..12).joinToString(prefix = """{"metadata":{"latitude":1.0},"months":[""", postfix = "]}") { m ->
        """{"month":$m,"temperature":{"min":${m - 10.5},"max":${m + 10.0}},"precipitation":{"averageAmount":${m * 2.5},"probability":30}}"""
    }

    @Test
    fun forecastParsesDaysAndHoursAndSkipsIncompleteOnes() {
        val f = WeatherParsing.forecast(forecastJson, "Step 1", ZoneOffset.ofHours(5))
        assertEquals("Step 1", f.stepName)
        assertEquals(2, f.daily.size) // the day without a start is dropped
        assertEquals("Rain", f.daily[0].condition)
        assertEquals(21.5, f.daily[0].highCelsius, 0.0)
        assertEquals(9.0, f.daily[0].lowCelsius, 0.0)
        assertEquals(3.2, f.daily[0].precipitationMm, 0.0)
        assertEquals(14.0, f.daily[0].windKmh, 0.0)
        assertEquals(0.0, f.daily[1].precipitationMm, 0.0)
        assertEquals(1, f.hourly.size)
        assertEquals(18.2, f.hourly[0].temperatureCelsius, 0.0)
        assertEquals(java.time.Instant.parse("2026-07-23T10:00:00Z").toEpochMilli(), f.hourly[0].dateMs)
    }

    @Test
    fun forecastLimitsMatchIos() {
        val days = (1..14).joinToString(",") { """{"forecastStart":"2026-07-${"%02d".format(it)}T00:00:00Z","conditionCode":"Clear","temperatureMax":1,"temperatureMin":0}""" }
        assertEquals(10, WeatherParsing.forecast("""{"forecastDaily":{"days":[$days]}}""", "s", ZoneOffset.UTC).daily.size)
    }

    @Test
    fun malformedForecastIsInvalidData() {
        try {
            WeatherParsing.forecast("not json", "s", ZoneOffset.UTC)
            fail()
        } catch (e: WeatherException) {
            assertEquals(WeatherFailure.INVALID_DATA, e.failure)
        }
    }

    @Test
    fun timezonesAreWholeHourOffsetsFromTheLongitude() {
        assertEquals(ZoneOffset.ofHours(5), WeatherParsing.offsetFor(72.0))
        assertEquals(ZoneOffset.ofHours(-5), WeatherParsing.offsetFor(-75.0))
        assertEquals(ZoneOffset.ofHours(0), WeatherParsing.offsetFor(3.0))
        assertEquals("Etc/GMT-5", WeatherParsing.timezoneName(ZoneOffset.ofHours(5)))
        assertEquals("Etc/GMT+5", WeatherParsing.timezoneName(ZoneOffset.ofHours(-5)))
        assertEquals("Etc/GMT", WeatherParsing.timezoneName(ZoneOffset.UTC))
        assertEquals(ZoneOffset.ofHours(12), WeatherParsing.offsetFor(179.9))
    }

    @Test
    fun monthlyClimateNeedsTwelveMonths() {
        val climate = WeatherParsing.monthlyClimate(months())
        assertEquals(12, climate.size)
        assertEquals(-9.5, climate[0].minTempCelsius, 1e-9)
        assertEquals(11.0, climate[0].maxTempCelsius, 1e-9)
        assertEquals(30.0, climate[11].precipitationMm, 1e-9)
        try {
            WeatherParsing.monthlyClimate("""{"months":[{"month":1,"temperature":{"min":0,"max":1}}]}""")
            fail()
        } catch (e: WeatherException) {
            assertEquals(WeatherFailure.INVALID_DATA, e.failure)
        }
    }

    @Test
    fun climateCacheRoundTrips() {
        val climate = WeatherParsing.monthlyClimate(months())
        val json = WeatherParsing.encodeClimate(41.2, 69.3, climate, 1L)
        assertEquals(climate, WeatherParsing.decodeClimate(json))
        assertNull(WeatherParsing.decodeClimate("{}"))
        assertEquals("climate_41.20_69.30.json", WeatherParsing.cacheKey(41.2, 69.3))
    }

    @Test
    fun attributionReadsTheLegalPageAndTheLogos() {
        val a = WeatherParsing.attribution(
            """{"legalPageURL":"https://weatherkit.apple.com/legal-attribution.html","logoLight@1x":"l1","logoLight@2x":"l2","logoDark@1x":"d1"}"""
        )
        assertNotNull(a)
        assertEquals("l2", a!!.lightLogoUrl)
        assertEquals("d1", a.darkLogoUrl)
        assertNull(WeatherParsing.attribution("""{"serviceName":"x"}"""))
    }

    @Test
    fun jwtExpiryComesFromTheClaim() {
        fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val token = "${b64("{}")}.${b64("""{"exp":1900000000}""")}.sig"
        assertEquals(1900000000L, WeatherParsing.jwtExpiry(token))
        assertNull(WeatherParsing.jwtExpiry("garbage"))
    }

    @Test
    fun formattingFollowsTheUnitPreferences() {
        assertEquals("22°", WeatherFormat.temp(21.5, true))
        assertEquals("71°", WeatherFormat.temp(21.5, false))
        assertEquals("9°/22°", WeatherFormat.tempRange(9.0, 21.5, true))
        assertEquals("3 mm", WeatherFormat.precip(3.2, true))
        assertEquals("0.1 in", WeatherFormat.precip(3.2, false))
        assertTrue(WeatherFormat.hasPrecip(0.2))
        assertTrue(!WeatherFormat.hasPrecip(0.1))
        assertEquals(WeatherFormat.Tone.RAIN, WeatherFormat.tone("HeavyRain"))
        assertEquals(WeatherFormat.Tone.SUN, WeatherFormat.tone("MostlyClear"))
        assertEquals(WeatherFormat.Tone.NEUTRAL, WeatherFormat.tone("Cloudy"))
        val range = WeatherFormat.chartTempRange(WeatherParsing.monthlyClimate(months()), true)
        assertEquals(-9.5 - 5, range.start, 1e-9)
        assertEquals(22.0 + 5, range.endInclusive, 1e-9)
    }

    // ── Repository ──────────────────────────────────────────────────────────

    private class Fake(val bodies: Map<String, String>) : WeatherHttp {
        val urls = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        override suspend fun get(url: String, token: String): String {
            urls += url; tokens += token
            return bodies.entries.first { url.contains(it.key) }.value
        }
    }

    @Test
    fun forecastRequestUsesTheRollupTimezoneAndTheBearerToken() = runBlocking {
        val http = Fake(mapOf("/weather/" to forecastJson))
        var issued = 0
        val repo = WeatherRepository(tmp.root, http, fetchToken = { "tok${++issued}" }, now = { 0L })
        val f = repo.forecast(41.3, 69.2, "Tashkent")
        assertEquals(2, f.daily.size)
        assertTrue(http.urls.single().contains("/weather/en/41.3000/69.2000?dataSets=forecastDaily,forecastHourly&timezone=Etc%2FGMT-5"))
        // The unreadable-expiry token is reused for ten minutes.
        repo.forecast(41.3, 69.2, "again")
        assertEquals(listOf("tok1", "tok1"), http.tokens)
        assertEquals(1, issued)
    }

    @Test
    fun climateIsFetchedOnceThenServedFromTheDiskCache() = runBlocking {
        val http = Fake(mapOf("/statistics/monthly/" to months()))
        val repo = WeatherRepository(tmp.root, http, fetchToken = { "t" })
        val first = repo.climate(41.301, 69.199)
        val second = repo.climate(41.302, 69.201) // same 0.01° cell
        assertEquals(first, second)
        assertEquals(1, http.urls.size)
        assertTrue(http.urls[0].contains("/api/v2/statistics/monthly/41.3010/69.1990?dataSets=precipitation,temperature&start=1&end=12"))
    }

    @Test
    fun invalidCoordinatesAreRefusedBeforeAnyRequest() = runBlocking {
        val http = Fake(emptyMap())
        val repo = WeatherRepository(tmp.root, http, fetchToken = { "t" })
        try {
            repo.forecast(95.0, 0.0, "x")
            fail()
        } catch (e: WeatherException) {
            assertEquals(WeatherFailure.NO_COORDINATE, e.failure)
        }
        assertTrue(http.urls.isEmpty())
    }
}
