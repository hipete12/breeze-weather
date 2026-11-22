package com.breeze.weather.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Data for the radar map: the list of recent radar frames (shared by the home card and the map,
 * refreshed every few minutes) and forecast fields over a map area.
 */
object MapRepository {
    /** RainViewer adds a frame every 10 minutes. */
    private const val FRAMES_FRESH_MS = 4 * 60_000L

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val framesLock = Mutex()
    private var frames: List<RadarFrame> = emptyList()
    private var framesAt = 0L

    suspend fun radarFrames(): List<RadarFrame> = framesLock.withLock {
        val now = System.currentTimeMillis()
        if (frames.isNotEmpty() && now - framesAt < FRAMES_FRESH_MS) return frames
        val fresh = withContext(Dispatchers.IO) { RainViewer.parse(httpGet(RainViewer.URL), json) }
        frames = fresh
        framesAt = now
        fresh
    }

    suspend fun field(area: GridArea): MapField = withContext(Dispatchers.IO) {
        MapField.parse(httpGet(MapField.url(area)), area, json)
    }
}
