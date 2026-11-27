package com.breeze.weather

import com.breeze.weather.data.GridArea
import com.breeze.weather.data.MapField
import com.breeze.weather.data.Mercator
import com.breeze.weather.data.RainViewer
import com.breeze.weather.ui.ColorRamp
import com.breeze.weather.ui.MapRamps
import com.breeze.weather.ui.renderField
import com.breeze.weather.ui.temperatureLabelsGeoJson
import com.breeze.weather.ui.windArrowsGeoJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MapDataTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // ---------- RainViewer ----------

    private val rainViewerBody = """
        {"version":"2.0","generated":1795380021,"host":"https://tilecache.rainviewer.com",
         "radar":{"past":[
           {"time":1795372800,"path":"/v2/radar/a"},{"time":1795373400,"path":"/v2/radar/b"},
           {"time":1795374000,"path":"/v2/radar/c"},{"time":1795374600,"path":"/v2/radar/d"},
           {"time":1795375200,"path":"/v2/radar/e"}],"nowcast":[]},
         "satellite":{"infrared":[]}}
    """

    @Test fun radarFramesStepTwentyMinutesAndEndAtTheLatest() {
        val frames = RainViewer.parse(rainViewerBody, json)
        assertEquals(listOf(1795372800L, 1795374000L, 1795375200L), frames.map { it.time })
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/e/512/{z}/{x}/{y}/2/1_0.png",
            frames.last().tiles,
        )
    }

    @Test fun radarFramesToleratesMissingRadar() {
        assertTrue(RainViewer.parse("""{"host":"https://x.com/","radar":{}}""", json).isEmpty())
        assertEquals("https://x.com/v2/p/512/{z}/{x}/{y}/2/1_0.png", RainViewer.tileUrl("https://x.com/", "/v2/p"))
    }

    // ---------- Grid ----------

    @Test fun mercatorRoundTrips() {
        for (lat in listOf(-80.0, -33.9, 0.0, 43.65, 79.9)) assertEquals(lat, Mercator.lat(Mercator.y(lat)), 1e-9)
    }

    @Test fun gridRowsAreEvenInMercatorNotLatitude() {
        val area = GridArea(west = -90.0, east = -70.0, north = 55.0, south = 35.0, cols = 5, rows = 5)
        assertEquals(55.0, area.lat(0), 1e-9)
        assertEquals(35.0, area.lat(4), 1e-9)
        val steps = (0 until 4).map { Mercator.y(area.lat(it)) - Mercator.y(area.lat(it + 1)) }
        steps.forEach { assertEquals(steps[0], it, 1e-9) }
        // Evenly spaced latitudes would put the middle row at 45°; Mercator puts it further north.
        assertTrue(area.lat(2) > 45.0)
        assertEquals(-80.0, area.lon(2), 1e-9)
        assertEquals(25, area.points().size)
        assertEquals(55.0 to -90.0, area.points().first())
    }

    @Test fun gridAroundAViewGrowsItAndMatchesItsShape() {
        // A tall phone-shaped view over southern Ontario.
        val area = GridArea.around(west = -84.0, east = -76.0, north = 48.0, south = 38.0)
        assertTrue(area.covers(-84.0, -76.0, 48.0, 38.0))
        assertEquals(-86.0, area.west, 1e-9)
        assertEquals(-74.0, area.east, 1e-9)
        assertEquals(9, area.cols)
        // Rows follow the area's shape on the map, so grid cells come out roughly square.
        val cellW = Math.toRadians((area.east - area.west) / (area.cols - 1))
        val cellH = (Mercator.y(area.north) - Mercator.y(area.south)) / (area.rows - 1)
        assertTrue("cells ${cellW / cellH}", cellW / cellH in 0.7..1.4)
        assertFalse(area.covers(-90.0, -76.0, 48.0, 38.0))
    }

    @Test fun gridAroundStaysInsideTheWorld() {
        val world = GridArea.around(west = -250.0, east = 250.0, north = 85.0, south = -85.0)
        assertEquals(360.0, world.east - world.west, 1e-9)
        assertTrue(world.north <= Mercator.MAX_LAT + 1e-9 && world.south >= -Mercator.MAX_LAT - 1e-9)
        assertTrue(world.rows in 4..16)
        // A view zoomed right in still gets a usable grid.
        val street = GridArea.around(west = -79.40, east = -79.39, north = 43.66, south = 43.65)
        assertTrue(street.east > street.west && street.north > street.south)
    }

    @Test fun forecastUrlWrapsLongitudesPastTheDateLine() {
        val area = GridArea(west = 170.0, east = 190.0, north = 10.0, south = -10.0, cols = 3, rows = 2)
        val url = MapField.url(area)
        assertTrue(url, url.contains("&longitude=170.000,-180.000,-170.000,170.000,-180.000,-170.000"))
        assertTrue(url, url.contains("latitude=10.000,10.000,10.000,-10.000,-10.000,-10.000"))
        assertTrue(url, url.contains("forecast_hours=13"))
        assertTrue(url, url.contains("timeformat=unixtime"))
    }

    // ---------- Forecast field ----------

    private fun point(temps: String, speed: String = "[5,6]", dir: String = "[270,280]", cloud: String = "[0,100]") =
        """{"latitude":1,"longitude":2,"hourly":{"time":[1795377600,1795381200],"temperature_2m":$temps,
            "wind_speed_10m":$speed,"wind_direction_10m":$dir,"cloud_cover":$cloud}}"""

    @Test fun fieldKeepsThePointsInTheOrderAsked() {
        val area = GridArea(west = 0.0, east = 1.0, north = 1.0, south = 0.0, cols = 2, rows = 2)
        val body = "[" + listOf(point("[1,11]"), point("[2,12]"), point("[3,13]"), point("[4,null]")).joinToString(",") + "]"
        val field = MapField.parse(body, area, json)
        assertEquals(listOf(1795377600L, 1795381200L), field.times)
        assertEquals(listOf(1f, 2f, 3f, 4f), field.temperature[0].toList())
        assertEquals(listOf(11f, 12f, 13f), field.temperature[1].take(3))
        assertTrue(field.temperature[1][3].isNaN())
        assertEquals(100f, field.cloudCover[1][0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun fieldRejectsTheWrongNumberOfPoints() {
        val area = GridArea(west = 0.0, east = 1.0, north = 1.0, south = 0.0, cols = 2, rows = 2)
        MapField.parse(point("[1,2]"), area, json)
    }

    // ---------- Painting ----------

    @Test fun rampBlendsBetweenStopsAndHoldsAtTheEnds() {
        val ramp = ColorRamp(0f to 0xFF000000, 10f to 0xFFFFFFFF)
        assertEquals(0xFF000000.toInt(), ramp.at(-5f))
        assertEquals(0xFFFFFFFF.toInt(), ramp.at(50f))
        assertEquals(0xFF808080.toInt(), ramp.at(5f))
        assertEquals(0, ramp.at(Float.NaN))
        // Clear skies leave the map untouched.
        assertEquals(0, MapRamps.CLOUDS.at(0f) ushr 24)
    }

    @Test fun fieldImageHitsTheGridValuesAndHasTheMapShape() {
        val area = GridArea(west = -10.0, east = 10.0, north = 10.0, south = -10.0, cols = 3, rows = 3)
        val values = floatArrayOf(0f, 0f, 0f, 0f, 10f, 0f, 0f, 0f, 0f)
        val ramp = ColorRamp(0f to 0xFF000000, 10f to 0xFFFFFFFF)
        val image = renderField(area, values, 101, ramp)
        assertEquals(101, image.width)
        assertTrue(abs(image.height - 101 * area.aspect) <= 1.0)
        val mid = image.pixels[(image.height / 2) * image.width + 50]
        assertTrue("centre ${Integer.toHexString(mid)}", (mid and 0xFF) > 0xF0)
        assertEquals(0xFF000000.toInt(), image.pixels[0])
        // A gap in the data leaves a hole rather than inventing values.
        val holey = values.copyOf().also { it[0] = Float.NaN }
        assertEquals(0, renderField(area, holey, 101, ramp).pixels[0])
    }

    @Test fun windArrowsPointWhereTheWindBlows() {
        val area = GridArea(west = 0.0, east = 1.0, north = 1.0, south = 0.0, cols = 2, rows = 2)
        val speed = floatArrayOf(10f, 20f, Float.NaN, 30f)
        val dir = floatArrayOf(270f, 90f, 0f, 200f)
        val features = json.parseToJsonElement(windArrowsGeoJson(area, speed, dir)).jsonObject["features"]!!.jsonArray
        assertEquals(3, features.size)
        val first = features[0].jsonObject
        // A west wind blows towards the east.
        assertEquals(90f, first["properties"]!!.jsonObject["rot"]!!.jsonPrimitive.float)
        assertEquals(270f, features[1].jsonObject["properties"]!!.jsonObject["rot"]!!.jsonPrimitive.float)
        val coords = first["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(0.0, coords[0].jsonPrimitive.double, 1e-9)
        assertEquals(1.0, coords[1].jsonPrimitive.double, 1e-9)
    }

    @Test fun temperatureLabelsUseTheAppsUnits() {
        val area = GridArea(west = 0.0, east = 1.0, north = 1.0, south = 0.0, cols = 2, rows = 1)
        val temps = floatArrayOf(12.4f, Float.NaN)
        fun labels(f: Boolean) = json.parseToJsonElement(temperatureLabelsGeoJson(area, temps, f))
            .jsonObject["features"]!!.jsonArray.map { it.jsonObject["properties"]!!.jsonObject["t"]!!.jsonPrimitive.content }
        assertEquals(listOf("12°"), labels(false))
        assertEquals(listOf("54°"), labels(true))
    }
}
