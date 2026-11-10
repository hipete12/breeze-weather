package com.breeze.weather

import com.breeze.weather.data.Condition
import com.breeze.weather.data.Place
import com.breeze.weather.ui.beaufort
import com.breeze.weather.ui.beaufortName
import com.breeze.weather.ui.RainStyle
import com.breeze.weather.ui.rainStyle
import com.breeze.weather.ui.uvLabel
import com.breeze.weather.ui.windCompass
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test fun wmoCodesMapToConditionGroups() {
        assertEquals(Condition.CLEAR, Condition.fromWmo(0))
        assertEquals(Condition.PARTLY_CLOUDY, Condition.fromWmo(2))
        assertEquals(Condition.FOG, Condition.fromWmo(48))
        assertEquals(Condition.DRIZZLE, Condition.fromWmo(53))
        assertEquals(Condition.RAIN, Condition.fromWmo(81))
        assertEquals(Condition.SNOW, Condition.fromWmo(86))
        assertEquals(Condition.STORM, Condition.fromWmo(99))
        assertEquals(Condition.CLOUDY, Condition.fromWmo(1234))
    }

    @Test fun descriptionsDependOnDaylight() {
        assertEquals("Sunny", Condition.describe(0, isDay = true))
        assertEquals("Clear", Condition.describe(0, isDay = false))
    }

    @Test fun windCompassRoundsToNearestPoint() {
        assertEquals("N", windCompass(0))
        assertEquals("N", windCompass(350))
        assertEquals("NE", windCompass(40))
        assertEquals("S", windCompass(180))
        assertEquals("NW", windCompass(315))
        assertEquals("N", windCompass(-10))
    }

    @Test fun uvLabelsFollowWhoScale() {
        assertEquals("Low", uvLabel(2.9))
        assertEquals("Moderate", uvLabel(3.0))
        assertEquals("High", uvLabel(6.5))
        assertEquals("Very high", uvLabel(9.0))
        assertEquals("Extreme", uvLabel(11.0))
    }

    @Test fun subtitleSkipsDuplicatesAndBlanks() {
        val tokyo = Place("geo:1", "Tokyo", region = "Tokyo", country = "Japan", latitude = 0.0, longitude = 0.0)
        assertEquals("Japan", tokyo.subtitle)
        val x = Place("geo:2", "X", region = "", country = null, latitude = 0.0, longitude = 0.0)
        assertEquals("", x.subtitle)
    }

    @Test fun beaufortScaleFromKmh() {
        assertEquals(0, beaufort(0.5))
        assertEquals(2, beaufort(9.7))
        assertEquals(4, beaufort(25.0))
        assertEquals(8, beaufort(70.0))
        assertEquals(12, beaufort(150.0))
        assertEquals("Gentle breeze", beaufortName(3))
    }

    @Test fun rainStreaksGrowWithIntensity() {
        assertEquals(RainStyle(drizzle = true, count = 2), rainStyle(Condition.DRIZZLE, 51))
        assertEquals(RainStyle(drizzle = true, count = 2), rainStyle(Condition.DRIZZLE, 55))
        assertEquals(RainStyle(drizzle = false, count = 3), rainStyle(Condition.RAIN, 61))
        assertEquals(RainStyle(drizzle = false, count = 4), rainStyle(Condition.RAIN, 63))
        assertEquals(RainStyle(drizzle = false, count = 5), rainStyle(Condition.RAIN, 82))
        assertEquals(RainStyle(drizzle = true, count = 2), rainStyle(Condition.DRIZZLE, null))
    }
}
