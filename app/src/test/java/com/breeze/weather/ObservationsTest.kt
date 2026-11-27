package com.breeze.weather

import com.breeze.weather.data.Condition
import com.breeze.weather.data.CurrentConditions
import com.breeze.weather.data.DayForecast
import com.breeze.weather.data.HourForecast
import com.breeze.weather.data.MetarCloudDto
import com.breeze.weather.data.MetarDto
import com.breeze.weather.data.Observations
import com.breeze.weather.data.Place
import com.breeze.weather.data.Units
import com.breeze.weather.data.Weather
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class ObservationsTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val today: LocalDate = LocalDate.of(2026, 11, 26)
    private val utcOffset = -5 * 3600 // EST (UTC-5)
    private val zone = ZoneOffset.ofTotalSeconds(utcOffset)

    private fun epoch(time: LocalDateTime): Long = time.toEpochSecond(zone)

    /** Two days of forecast hours (10°C all day, partly cloudy), fetched at 14:15 EST. */
    private fun forecastWeather(units: Units = Units.METRIC): Weather {
        val hours = (0 until 48).map { i ->
            val time = today.atStartOfDay().plusHours(i.toLong())
            HourForecast(
                time = time,
                temperature = if (units == Units.METRIC) 10.0 else 50.0,
                condition = Condition.PARTLY_CLOUDY,
                weatherCode = 2,
                isDay = time.hour in 7..17,
                precipitationChance = 20,
                feelsLike = if (units == Units.METRIC) 9.0 else 48.0,
                humidity = 60.0,
                precipitation = 0.5,
                windSpeed = 10.0,
                windGusts = 18.0,
                windDirection = 180.0,
                uvIndex = if (time.hour in 8..16) 3.0 else 0.0,
                cloudCover = 50.0,
            )
        }
        val days = listOf(
            DayForecast(
                date = today,
                condition = Condition.PARTLY_CLOUDY,
                weatherCode = 2,
                high = if (units == Units.METRIC) 18.0 else 64.0,
                low = if (units == Units.METRIC) 8.0 else 46.0,
                precipitationChance = 20,
                sunrise = today.atTime(7, 0),
                sunset = today.atTime(16, 30),
                uvMax = 3.0,
            ),
            DayForecast(
                date = today.plusDays(1),
                condition = Condition.PARTLY_CLOUDY,
                weatherCode = 2,
                high = if (units == Units.METRIC) 12.0 else 54.0,
                low = if (units == Units.METRIC) 6.0 else 43.0,
                precipitationChance = 10,
                sunrise = today.plusDays(1).atTime(7, 1),
                sunset = today.plusDays(1).atTime(16, 29),
                uvMax = 2.0,
            ),
        )
        val current = CurrentConditions(
            time = today.atTime(14, 15),
            temperature = if (units == Units.METRIC) 14.0 else 57.0,
            feelsLike = if (units == Units.METRIC) 13.0 else 55.0,
            humidity = 55,
            isDay = true,
            condition = Condition.PARTLY_CLOUDY,
            weatherCode = 2,
            description = "Partly cloudy",
            windSpeed = 10.0,
            windGusts = null,
            windDirection = 180,
            uvIndex = 2.0,
        )
        return Weather(
            current = current,
            hourly = hours.filterNot { it.time.isBefore(today.atTime(14, 0)) }.take(25),
            hours = hours,
            daily = days,
            units = units,
            fetchedAt = 0L,
            utcOffsetSeconds = utcOffset,
        )
    }

    @Test fun bboxUrlUsesDotDecimalsRegardlessOfLocale() {
        val prev = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val url = Observations.bboxUrl(48.8566, 2.3522)
            assertTrue(url.contains("bbox=48.3066,"))
            assertFalse(url.contains("48,"))
            assertTrue(url.endsWith("&hours=3&format=json"))
        } finally {
            Locale.setDefault(prev)
        }
    }

    @Test fun parseHandlesEmptyAndVariableWindDirection() {
        assertTrue(Observations.parse("", json).isEmpty())
        assertTrue(Observations.parse("   ", json).isEmpty())
        assertTrue(Observations.parse("{}", json).isEmpty())

        val payload = """
            [
              {
                "icaoId": "KLNA",
                "name": "Lantana/Palm Beach Cnty, FL, US",
                "lat": 26.5925,
                "lon": -80.0848,
                "obsTime": 1726716900,
                "temp": 25.8,
                "dewp": 23.8,
                "wdir": "VRB",
                "wspd": 8,
                "wgst": null,
                "precip": null,
                "wxString": "VCTS",
                "clouds": [{"cover": "BKN", "base": 2000}]
              },
              {
                "icaoId": "KORD",
                "obsTime": 1699048260,
                "temp": 14.4,
                "dewp": 2.8,
                "wdir": 230,
                "wspd": 6,
                "wgst": 12,
                "precip": 0.08,
                "wxString": "-RA",
                "clouds": [{"cover": "OVC", "base": 11000}]
              }
            ]
        """.trimIndent()

        val parsed = Observations.parse(payload, json)
        assertEquals(2, parsed.size)
        assertNull(Observations.parseDirection(parsed[0].wdir!!))
        assertEquals(230.0, Observations.parseDirection(parsed[1].wdir!!)!!, 0.001)
        assertEquals(0.08, parsed[1].precip!!, 0.001)
    }

    @Test fun nearestStationPicksClosestWithinRangeAndSkipsMissingTemp() {
        val nyc = Place("nyc", "New York", latitude = 40.7128, longitude = -74.0060)
        val metars = listOf(
            // Closer, but missing temperature -> ignored.
            MetarDto(icaoId = "KNYC", name = "Central Park, NY, US", lat = 40.78, lon = -73.97, temp = null),
            // Within range (~20 km).
            MetarDto(icaoId = "KJFK", name = "New York/JFK Intl, NY, US", lat = 40.6413, lon = -73.7781, temp = 12.0),
            // Closer (~14 km).
            MetarDto(icaoId = "KLGA", name = "New York/LaGuardia, NY, US", lat = 40.7769, lon = -73.8740, temp = 11.5),
            // Duplicate of KLGA.
            MetarDto(icaoId = "KLGA", name = "New York/LaGuardia, NY, US", lat = 40.7769, lon = -73.8740, temp = 11.0),
            // Too far (> 60 km).
            MetarDto(icaoId = "KPHL", name = "Philadelphia, PA, US", lat = 39.8729, lon = -75.2437, temp = 13.0),
        )
        val station = Observations.nearestStation(nyc, metars)
        assertNotNull(station)
        assertEquals("KLGA", station!!.icaoId)
        assertEquals("New York/LaGuardia (KLGA)", station.label)

        // When only a distant station exists, return null.
        assertNull(Observations.nearestStation(nyc, listOf(metars.last())))
    }

    @Test fun wmoCodeMapsPresentWeatherAndCloudCover() {
        assertEquals(0, Observations.wmoCode(null, listOf(MetarCloudDto("CLR"))))
        assertEquals(1, Observations.wmoCode(null, listOf(MetarCloudDto("FEW"))))
        assertEquals(2, Observations.wmoCode(null, listOf(MetarCloudDto("SCT"))))
        assertEquals(3, Observations.wmoCode(null, listOf(MetarCloudDto("FEW"), MetarCloudDto("BKN"))))
        assertEquals(3, Observations.wmoCode(null, listOf(MetarCloudDto("OVC"))))
        assertNull(Observations.wmoCode(null, null))

        assertEquals(45, Observations.wmoCode("FG", null))
        assertEquals(48, Observations.wmoCode("FZFG", null))
        assertEquals(51, Observations.wmoCode("-DZ", null))
        assertEquals(61, Observations.wmoCode("-RA BR", null)) // rain wins over mist
        assertEquals(65, Observations.wmoCode("+RA", null))
        assertEquals(66, Observations.wmoCode("-FZRA", null))
        assertEquals(71, Observations.wmoCode("-SN", null))
        assertEquals(75, Observations.wmoCode("+SN", null))
        assertEquals(80, Observations.wmoCode("-SHRA", null))
        assertEquals(85, Observations.wmoCode("SHSN", null))
        assertEquals(95, Observations.wmoCode("VCTS", null))
        assertEquals(95, Observations.wmoCode("+TSRA", null))
        assertEquals(99, Observations.wmoCode("+TSGR", null))
    }

    @Test fun relativeHumidityMatchesMagnusTetens() {
        assertEquals(100.0, Observations.relativeHumidity(15.0, 15.0), 0.1)
        assertEquals(45.6, Observations.relativeHumidity(14.4, 2.8), 1.0)
    }

    @Test fun mergeReplacesPastHoursAndUpdatesTodayHighLowAndIcon() {
        val weather = forecastWeather(Units.METRIC)
        val metars = listOf(
            // 05:53 -> buckets to 06:00 (colder than forecast low 8°C)
            MetarDto(
                obsTime = epoch(today.atTime(5, 53)),
                temp = 4.0,
                dewp = 2.0,
                wspd = 10.0,
                wdir = JsonPrimitive(270),
                clouds = listOf(MetarCloudDto("CLR")),
            ),
            // 11:53 -> buckets to 12:00 (observed rain, daytime)
            MetarDto(
                obsTime = epoch(today.atTime(11, 53)),
                temp = 13.0,
                dewp = 11.0,
                wspd = 12.0,
                wgst = 20.0,
                wdir = JsonPrimitive(210),
                precip = 0.10,
                wxString = "-RA",
                clouds = listOf(MetarCloudDto("OVC")),
            ),
            // 13:53 -> buckets to 14:00 (current hour; still <= 14:00)
            MetarDto(
                obsTime = epoch(today.atTime(13, 53)),
                temp = 15.0,
                dewp = 10.0,
                wspd = 8.0,
                wdir = JsonPrimitive("VRB"),
                clouds = listOf(MetarCloudDto("SCT")),
            ),
            // 14:53 -> buckets to 15:00 (future hour > 14:00 -> must NOT be merged)
            MetarDto(
                obsTime = epoch(today.atTime(14, 53)),
                temp = 99.0,
                clouds = listOf(MetarCloudDto("CLR")),
            ),
        )

        val merged = Observations.merge(weather, metars, "New York/LaGuardia (KLGA)")
        assertEquals("New York/LaGuardia (KLGA)", merged.observationStation)

        val h6 = merged.hours.first { it.time == today.atTime(6, 0) }
        assertTrue(h6.observed)
        assertFalse(h6.observedPrecipitation)
        assertEquals(4.0, h6.temperature, 0.001)
        assertEquals(0, h6.weatherCode)
        assertEquals(Condition.CLEAR, h6.condition)
        assertEquals(18.52, h6.windSpeed!!, 0.01)
        assertNull(h6.windGusts) // steady observed wind clears forecast gusts
        assertEquals(270.0, h6.windDirection!!, 0.001)
        assertEquals(0.0, h6.cloudCover!!, 0.001)
        // Forecast-only fields stay intact.
        assertEquals(20, h6.precipitationChance)
        assertEquals(0.5, h6.precipitation!!, 0.001)

        val h12 = merged.hours.first { it.time == today.atTime(12, 0) }
        assertTrue(h12.observed)
        assertTrue(h12.observedPrecipitation)
        assertEquals(13.0, h12.temperature, 0.001)
        assertEquals(61, h12.weatherCode)
        assertEquals(Condition.RAIN, h12.condition)
        assertEquals(2.54, h12.precipitation!!, 0.01)
        assertEquals(37.04, h12.windGusts!!, 0.05)

        val h14 = merged.hours.first { it.time == today.atTime(14, 0) }
        assertTrue(h14.observed)
        assertEquals(15.0, h14.temperature, 0.001)
        assertEquals(180.0, h14.windDirection!!, 0.001) // VRB keeps fallback direction

        val h15 = merged.hours.first { it.time == today.atTime(15, 0) }
        assertFalse(h15.observed)
        assertEquals(10.0, h15.temperature, 0.001)

        // Today's DayForecast reflects observed low (4°C), actual peak (15°C), and daytime rain (61).
        val todayDay = merged.daily.first()
        assertEquals(15.0, todayDay.high, 0.001)
        assertEquals(4.0, todayDay.low, 0.001)
        assertEquals(61, todayDay.weatherCode)
        assertEquals(Condition.RAIN, todayDay.condition)

        // Tomorrow is untouched.
        assertEquals(weather.daily[1], merged.daily[1])
    }

    @Test fun mergeConvertsUnitsToImperial() {
        val weather = forecastWeather(Units.IMPERIAL)
        val metars = listOf(
            MetarDto(
                obsTime = epoch(today.atTime(10, 0)),
                temp = 0.0,
                dewp = 0.0,
                wspd = 10.0,
                precip = 0.25,
                wxString = "-SN",
            )
        )
        val merged = Observations.merge(weather, metars, "KORD")
        val h10 = merged.hours.first { it.time == today.atTime(10, 0) }
        assertTrue(h10.observed)
        assertEquals(32.0, h10.temperature, 0.01)
        assertEquals(11.51, h10.windSpeed!!, 0.05)
        assertEquals(0.25, h10.precipitation!!, 0.001)
        assertEquals(71, h10.weatherCode)
    }

    @Test fun mergeCombinesSpeciRainWithRoutineReportInSameHour() {
        val weather = forecastWeather(Units.METRIC)
        val metars = listOf(
            // Routine at 09:53 (7 min before 10:00 -> closer to hour mark)
            MetarDto(
                obsTime = epoch(today.atTime(9, 53)),
                temp = 12.0,
                wspd = 6.0,
                clouds = listOf(MetarCloudDto("SCT")),
            ),
            // SPECI at 10:15 (15 min after 10:00 -> reports thunderstorm + gust)
            MetarDto(
                obsTime = epoch(today.atTime(10, 15)),
                temp = 10.5,
                wspd = 14.0,
                wgst = 26.0,
                wxString = "TSRA",
                clouds = listOf(MetarCloudDto("OVC")),
            ),
        )
        val merged = Observations.merge(weather, metars, "KJFK")
        val h10 = merged.hours.first { it.time == today.atTime(10, 0) }
        assertEquals(12.0, h10.temperature, 0.001) // closer to 10:00
        assertEquals(95, h10.weatherCode) // significant weather from SPECI wins
        assertEquals(26.0 * 1.852, h10.windGusts!!, 0.05) // max gust in the hour
    }

    @Test fun mergeReturnsOriginalWhenNoObservationsMatchToday() {
        val weather = forecastWeather(Units.METRIC)
        val yesterdayObs = listOf(
            MetarDto(obsTime = epoch(today.minusDays(1).atTime(12, 0)), temp = 5.0)
        )
        assertSame(weather, Observations.merge(weather, yesterdayObs, "KJFK"))
        assertSame(weather, Observations.merge(weather, emptyList(), "KJFK"))
    }
}
