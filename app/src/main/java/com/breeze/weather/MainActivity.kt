package com.breeze.weather

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.breeze.weather.ui.BreezeApp
import com.breeze.weather.ui.LaunchRequest
import com.breeze.weather.ui.theme.BreezeTheme
import com.breeze.weather.widget.WidgetIntents

class MainActivity : ComponentActivity() {

    /** A place (and day) to show, from a widget tap; consumed by [BreezeApp]. */
    private val launchRequest = mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // After a configuration change the original intent has already been handled.
        if (savedInstanceState == null) launchRequest.value = launchRequestFrom(intent)
        setContent {
            BreezeTheme {
                BreezeApp(launchRequest = launchRequest.value, onLaunchRequestHandled = { launchRequest.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequestFrom(intent)?.let { launchRequest.value = it }
    }

    private fun launchRequestFrom(intent: Intent?): LaunchRequest? {
        if (intent == null || !intent.hasExtra(WidgetIntents.EXTRA_PLACE_ID) && !intent.hasExtra(WidgetIntents.EXTRA_DAY)) return null
        return LaunchRequest(
            placeId = intent.getStringExtra(WidgetIntents.EXTRA_PLACE_ID),
            day = intent.getIntExtra(WidgetIntents.EXTRA_DAY, -1).takeIf { it >= 0 },
        )
    }
}
