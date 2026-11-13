package com.breeze.weather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breeze.weather.data.Condition
import com.breeze.weather.ui.WeatherIcon
import com.breeze.weather.ui.gradient
import com.breeze.weather.ui.theme.BreezeTheme

/** Debug-only: every WMO code's icon, day and night, at the sizes the app draws them. */
class IconGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val isDay = intent.getBooleanExtra("day", true)
        setContent { BreezeTheme { Gallery(isDay) } }
    }
}

private val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 61, 63, 65, 66, 80, 81, 82, 71, 73, 75, 77, 85, 86, 95, 96, 99)

@Composable
private fun Gallery(isDay: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(Condition.PARTLY_CLOUDY.gradient(isDay)))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        codes.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { code ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text("$code", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(22.dp))
                        Sample(code, isDay, 14)
                        Sample(code, isDay, 22)
                        Sample(code, isDay, 44)
                        Spacer(Modifier.width(4.dp))
                        Text(Condition.describe(code, isDay), color = Color.White, fontSize = 10.sp, lineHeight = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Sample(code: Int, isDay: Boolean, size: Int) {
    WeatherIcon(code, isDay, Modifier.padding(horizontal = 2.dp).size(size.dp))
}
