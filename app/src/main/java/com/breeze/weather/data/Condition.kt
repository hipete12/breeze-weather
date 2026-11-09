package com.breeze.weather.data

/** Coarse weather condition groups derived from WMO weather codes. */
enum class Condition {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM;

    companion object {
        fun fromWmo(code: Int): Condition = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 56, 57 -> DRIZZLE
            61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> STORM
            else -> CLOUDY
        }

        /** Human-readable description for the precise WMO code. */
        fun describe(code: Int, isDay: Boolean): String = when (code) {
            0 -> if (isDay) "Sunny" else "Clear"
            1 -> if (isDay) "Mostly sunny" else "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45 -> "Fog"
            48 -> "Freezing fog"
            51 -> "Light drizzle"
            53 -> "Drizzle"
            55 -> "Heavy drizzle"
            56, 57 -> "Freezing drizzle"
            61 -> "Light rain"
            63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71 -> "Light snow"
            73 -> "Snow"
            75 -> "Heavy snow"
            77 -> "Snow grains"
            80 -> "Light showers"
            81 -> "Showers"
            82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorms"
            96, 99 -> "Thunderstorms with hail"
            else -> "Cloudy"
        }
    }
}
