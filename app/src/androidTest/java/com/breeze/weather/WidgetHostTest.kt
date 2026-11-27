package com.breeze.weather

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.breeze.weather.data.Place
import com.breeze.weather.widget.WidgetConfig
import com.breeze.weather.widget.WidgetKind
import com.breeze.weather.widget.WidgetProviders
import com.breeze.weather.widget.WidgetStore
import com.breeze.weather.widget.WidgetTheme
import com.breeze.weather.widget.WidgetUpdater
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hosts real Breeze widgets the way a launcher does (this test acts as the host), so the whole
 * path runs: binding, the provider's update broadcast, the renderer, and the views the host
 * receives. Needs permission to bind widgets, which it grants itself through the shell.
 */
@RunWith(AndroidJUnit4::class)
class WidgetHostTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val manager = AppWidgetManager.getInstance(context)
    private lateinit var host: AppWidgetHost
    private val ids = mutableListOf<Int>()

    @Before fun setUp() {
        // "--user current" crashes on some builds; pass the id (uid / 100000 is the user id).
        shell("appwidget grantbind --package ${context.packageName} --user ${android.os.Process.myUid() / 100000}")
        host = AppWidgetHost(context, 4242)
        instrumentation.runOnMainSync { host.startListening() }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync {
            ids.forEach { host.deleteAppWidgetId(it) }
            host.stopListening()
            host.deleteHost()
        }
    }

    private fun shell(command: String) {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun bind(kind: WidgetKind, widthDp: Int, heightDp: Int): Int {
        val id = host.allocateAppWidgetId()
        ids += id
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        }
        val bound = manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, WidgetProviders.providerClass(kind)), options)
        assertTrue("binding $kind (grantbind failed?)", bound)
        return id
    }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** Waits for the host's view of [id] to show [expected] text. */
    private fun awaitText(id: Int, expected: (List<String>) -> Boolean): List<String> {
        var view: View? = null
        instrumentation.runOnMainSync { view = host.createView(context, id, manager.getAppWidgetInfo(id)) }
        // Generous: on a freshly booted emulator the broadcast queue can be slow.
        val deadline = System.currentTimeMillis() + 30_000
        var last = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { last = texts(view!!) }
            if (expected(last)) return last
            Thread.sleep(200)
        }
        throw AssertionError("Widget $id never showed the expected text; last saw $last")
    }

    @Test fun everyKindBindsAndIsListed() {
        val bound = WidgetKind.entries.associateWith { bind(it, it.defaultWidthDp, it.defaultHeightDp) }
        val listed = WidgetUpdater.allWidgets(context).toMap()
        bound.forEach { (kind, id) ->
            assertEquals(kind, listed[id])
            assertEquals(kind, WidgetUpdater.kindOf(context, id))
        }
    }

    @Test fun providerDrawsTheConfiguredPlace() {
        val id = bind(WidgetKind.CONDITIONS, 170, 170)
        // A place with no cached forecast (and no network needed): the widget says it's loading.
        val place = Place("test:nowhere", "Testville", latitude = 0.0, longitude = 0.0)
        WidgetStore(context).save(id, WidgetConfig(place = place, theme = WidgetTheme.DARK))
        context.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(ComponentName(context, WidgetProviders.providerClass(WidgetKind.CONDITIONS)))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        )
        val shown = awaitText(id) { "Testville" in it }
        assertTrue(shown.toString(), shown.any { it.contains("Loading") || it.contains("Can't reach") || it.contains("°") })
    }

    @Test fun updaterRedrawsAfterASettingsChange() {
        val id = bind(WidgetKind.NOW, 170, 80)
        val store = WidgetStore(context)
        store.save(id, WidgetConfig(place = Place("test:a", "Alphaville", latitude = 1.0, longitude = 1.0)))
        runBlocking { WidgetUpdater.update(context, listOf(id to WidgetKind.NOW)) }
        awaitText(id) { "Alphaville" in it }
        store.save(id, WidgetConfig(place = Place("test:b", "Betatown", latitude = 2.0, longitude = 2.0)))
        runBlocking { WidgetUpdater.update(context, listOf(id to WidgetKind.NOW)) }
        awaitText(id) { "Betatown" in it }
    }

    @Test fun deletingAWidgetForgetsItsSettings() {
        val id = bind(WidgetKind.DAILY, 350, 260)
        val store = WidgetStore(context)
        store.save(id, WidgetConfig(theme = WidgetTheme.LIGHT))
        assertNotNull(store.config(id))
        instrumentation.runOnMainSync { host.deleteAppWidgetId(id) }
        ids -= id
        val deadline = System.currentTimeMillis() + 30_000
        while (store.config(id) != null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertNull(store.config(id))
    }
}
