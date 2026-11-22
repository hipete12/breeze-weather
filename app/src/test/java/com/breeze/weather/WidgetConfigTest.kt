package com.breeze.weather

import com.breeze.weather.data.Place
import com.breeze.weather.widget.Metric
import com.breeze.weather.widget.TapAction
import com.breeze.weather.widget.TextScale
import com.breeze.weather.widget.WidgetConfig
import com.breeze.weather.widget.WidgetKind
import com.breeze.weather.widget.WidgetSection
import com.breeze.weather.widget.WidgetStore
import com.breeze.weather.widget.WidgetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigTest {

    private val json = WidgetStore.json

    @Test fun roundTripsThroughJson() {
        val config = WidgetConfig(
            place = Place("geo:1", "Oslo", "Oslo", "Norway", 59.9, 10.7),
            theme = WidgetTheme.LIGHT,
            opacity = 40,
            textScale = TextScale.LARGE,
            sections = setOf(WidgetSection.HOURLY, WidgetSection.DETAILS),
            showFeelsLike = true,
            hourStep = 3,
            metrics = listOf(Metric.UV, Metric.SUNSET),
            tapAction = TapAction.REFRESH,
        )
        assertEquals(config, json.decodeFromString<WidgetConfig>(json.encodeToString(config)))
    }

    @Test fun oldOrPartialJsonGetsDefaults() {
        val config = json.decodeFromString<WidgetConfig>("""{"theme":"DARK"}""")
        assertEquals(WidgetTheme.DARK, config.theme)
        assertNull(config.place)
        assertTrue(config.followsLocation)
        assertEquals(100, config.opacity)
        assertEquals(TapAction.OPEN_APP, config.tapAction)
    }

    @Test fun newerJsonIsReadAsFarAsPossible() {
        // A field from a future version, and an option this version doesn't know.
        val config = json.decodeFromString<WidgetConfig>("""{"theme":"NEON","opacity":70,"sparkles":true}""")
        assertEquals(WidgetTheme.SKY, config.theme)
        assertEquals(70, config.opacity)
    }

    @Test fun sanitizedKeepsValuesInRange() {
        val config = WidgetConfig(opacity = 140, hourStep = 9, metrics = listOf(Metric.UV, Metric.UV, Metric.WIND), sections = emptySet()).sanitized()
        assertEquals(100, config.opacity)
        assertEquals(3, config.hourStep)
        assertEquals(listOf(Metric.UV, Metric.WIND), config.metrics)
        assertEquals(setOf(WidgetSection.HEADER), config.sections)
        assertEquals(0, WidgetConfig(opacity = -5).sanitized().opacity)
        assertEquals(WidgetConfig.MAX_STRIP_COUNT, WidgetConfig(stripCount = 50).sanitized().stripCount)
        assertEquals(WidgetConfig.MIN_STRIP_COUNT, WidgetConfig(stripCount = 0).sanitized().stripCount)
    }

    @Test fun stripsAreFixedRowsWithColouredHighAndLow() {
        for (kind in listOf(WidgetKind.HOURLY_STRIP, WidgetKind.DAILY_STRIP, WidgetKind.MINI)) {
            assertTrue(kind.isStrip)
            assertFalse(kind.hasSections)
            val config = kind.defaultConfig()
            assertTrue(config.showHighLow)
            assertTrue(config.colorTemps)
            assertEquals(5, config.stripCount)
        }
        assertFalse(WidgetKind.HOURLY.isStrip)
        // Settings saved before strips existed still load.
        val old = json.decodeFromString<WidgetConfig>("""{"theme":"DARK","hourStep":2}""")
        assertTrue(old.colorTemps)
        assertEquals(5, old.stripCount)
        // The place line in strips is opt-in, even though other widgets show the place by default.
        assertFalse(old.stripPlace)
        assertTrue(old.showPlace)
        assertTrue(WidgetKind.HOURLY_STRIP.stripCanShowPlace && WidgetKind.DAILY_STRIP.stripCanShowPlace)
        assertFalse(WidgetKind.MINI.stripCanShowPlace)
    }

    @Test fun kindsHaveSensibleDefaults() {
        assertEquals(setOf(WidgetSection.HEADER), WidgetKind.NOW.defaultConfig().sections)
        assertFalse(WidgetKind.NOW.defaultConfig().showHighLow)
        assertFalse(WidgetKind.NOW.hasSections)
        assertTrue(WidgetSection.HOURLY in WidgetKind.HOURLY.defaultConfig().sections)
        assertTrue(WidgetSection.DAILY in WidgetKind.DAILY.defaultConfig().sections)
        assertEquals(setOf(WidgetSection.DETAILS), WidgetKind.DETAILS.defaultConfig().sections)
        assertEquals(Metric.DETAILS_DEFAULT, WidgetKind.DETAILS.defaultConfig().metrics)
        assertEquals(3, WidgetKind.FORECAST.defaultConfig().sections.size)
        WidgetKind.entries.forEach { kind ->
            assertTrue("$kind's primary section is on by default", kind.primarySection in kind.defaultSections)
        }
    }

    @Test fun kindNamesRoundTrip() {
        WidgetKind.entries.forEach { assertEquals(it, WidgetKind.fromName(it.name)) }
        assertNull(WidgetKind.fromName("CLOCK"))
        assertNull(WidgetKind.fromName(null))
    }
}
