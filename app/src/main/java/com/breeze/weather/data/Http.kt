package com.breeze.weather.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain GET returning the body as text; throws [IOException] on a non-2xx reply. */
internal fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 15_000
    conn.setRequestProperty("User-Agent", "BreezeWeather/0.1 (Android)")
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("Server returned HTTP $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
