package com.breeze.weather

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.breeze.weather.data.PlacesStore
import com.breeze.weather.data.distanceKm
import com.breeze.weather.widget.WidgetUpdater
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Background refreshes can't count on a fresh location fix (Android throttles background
 * requests), so they fall back to the newest location any app has seen. A mock location provider
 * stands in for "another app got a fix" here.
 */
@RunWith(AndroidJUnit4::class)
class WidgetLocationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val store = PlacesStore(context)
    private val saved = store.lastCurrentPlace

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Before fun setUp() {
        shell("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
        shell("appops set ${context.packageName} android:mock_location allow")
        @Suppress("DEPRECATION")
        manager.addTestProvider(PROVIDER, false, false, false, false, true, true, true, 1, 1)
        manager.setTestProviderEnabled(PROVIDER, true)
    }

    @After fun tearDown() {
        runCatching { manager.removeTestProvider(PROVIDER) }
        shell("appops set ${context.packageName} android:mock_location deny")
        store.lastCurrentPlace = saved
    }

    @Test fun refreshPicksUpWhereTheDeviceIsNow() {
        manager.setTestProviderLocation(
            PROVIDER,
            Location(PROVIDER).apply {
                latitude = 48.8566
                longitude = 2.3522
                accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            },
        )
        runBlocking { WidgetUpdater.updateDeviceLocation(WidgetUpdater.Sources(context)) }
        val place = PlacesStore(context).lastCurrentPlace
        assertTrue("now at $place", place != null && place.distanceKm(48.8566, 2.3522) < 1.0)
    }

    private companion object {
        const val PROVIDER = "breeze-test"
    }
}
