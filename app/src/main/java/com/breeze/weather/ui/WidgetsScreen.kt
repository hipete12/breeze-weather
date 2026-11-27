package com.breeze.weather.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.breeze.weather.ui.theme.InkRaised
import com.breeze.weather.widget.DataState
import com.breeze.weather.widget.RemoteViewsPreview
import com.breeze.weather.widget.SampleWeather
import com.breeze.weather.widget.WidgetBitmaps
import com.breeze.weather.widget.WidgetConfigActivity
import com.breeze.weather.widget.WidgetData
import com.breeze.weather.widget.WidgetKind
import com.breeze.weather.widget.WidgetRenderer
import com.breeze.weather.widget.WidgetStore
import com.breeze.weather.widget.WidgetTheme
import com.breeze.weather.widget.WidgetUpdater

/**
 * Widgets inside the app: every kind with a live preview and a button that adds it to the home
 * screen (configured first), the widgets already placed, and how often they refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetsScreen(state: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { WidgetStore(context) }
    val renderer = remember { WidgetRenderer(context) }
    val pinSupported = remember { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }
    var placed by remember { mutableStateOf(WidgetUpdater.allWidgets(context)) }
    var backgroundLocation by remember { mutableStateOf(WidgetUpdater.canLocateInBackground(context)) }
    LifecycleResumeEffect(Unit) {
        placed = WidgetUpdater.allWidgets(context)
        backgroundLocation = WidgetUpdater.canLocateInBackground(context)
        onPauseOrDispose { }
    }

    // Previews show the first place's real forecast when there is one.
    val page = state.pages.firstOrNull()
    val weather = page?.let { state.weather[it.id]?.weather }
    val data = if (page != null && weather != null) WidgetData(page, weather, DataState.READY)
    else WidgetData(SampleWeather.place, SampleWeather.create(state.units), DataState.READY)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Widgets") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (placed.isNotEmpty()) {
                item { Heading("On your home screen") }
                items(placed, key = { it.first }) { (id, kind) ->
                    val config = store.configOrDefault(id, kind)
                    PlacedRow(
                        title = kind.title,
                        subtitle = listOf(config.place?.name ?: "My location", themeLabel(config.theme)).joinToString(" · "),
                        onCustomize = { context.startActivity(WidgetConfigActivity.editIntent(context, id)) },
                    )
                }
            }

            item {
                Heading("Add a widget")
                Text(
                    if (pinSupported) "Customize a widget, then add it to your home screen. You can also long-press the home screen and choose Widgets."
                    else "Long-press your home screen, choose Widgets, and find Breeze.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(WidgetKind.entries, key = { it.name }) { kind ->
                val views = remember(kind, data) {
                    renderer.render(
                        0, kind, kind.defaultConfig(), data, kind.defaultWidthDp.toFloat(), kind.defaultHeightDp.toFloat(),
                        WidgetBitmaps(context.resources.displayMetrics.density), interactive = false,
                    )
                }
                KindCard(kind, pinSupported) {
                    RemoteViewsPreview(views, kind.defaultWidthDp.toFloat(), kind.defaultHeightDp.toFloat(), maxWidth = 320.dp)
                }
            }

            item { Heading("Updates") }
            item {
                UpdatesCard(
                    minutes = store.refreshMinutes,
                    onMinutes = { minutes ->
                        store.refreshMinutes = minutes
                        if (placed.isNotEmpty()) WidgetUpdater.schedule(context, intervalChanged = true)
                    },
                    showLocationTip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !backgroundLocation &&
                        state.location.access != com.breeze.weather.data.LocationAccess.NONE,
                )
            }
        }
    }
}

private fun themeLabel(theme: WidgetTheme) = when (theme) {
    WidgetTheme.SKY -> "Sky"
    WidgetTheme.SYSTEM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Dynamic" else "Auto"
    WidgetTheme.DARK -> "Dark"
    WidgetTheme.LIGHT -> "Light"
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PlacedRow(title: String, subtitle: String, onCustomize: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(InkRaised)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(onClick = onCustomize) { Text("Customize") }
    }
}

@Composable
private fun KindCard(kind: WidgetKind, pinSupported: Boolean, preview: @Composable () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(InkRaised),
    ) {
        // A stand-in for a wallpaper behind the preview.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFF3B4A6B), Color(0xFF6A5A7E), Color(0xFF2E5A6B))))
                .padding(vertical = 20.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { preview() }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium)
                Text(kind.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pinSupported) {
                Spacer(Modifier.width(12.dp))
                Button(onClick = { context.startActivity(WidgetConfigActivity.pinIntent(context, kind)) }) { Text("Add") }
            }
        }
    }
}

@Composable
private fun UpdatesCard(minutes: Int, onMinutes: (Int) -> Unit, showLocationTip: Boolean) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(minutes) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(InkRaised)
            .padding(16.dp),
    ) {
        Text("Refresh widgets every", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WidgetStore.REFRESH_CHOICES.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = selected == m,
                    onClick = { selected = m; onMinutes(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, WidgetStore.REFRESH_CHOICES.size),
                    label = { Text(if (m < 60) "$m min" else "${m / 60} h") },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Widgets also update whenever you open Breeze. If they stop updating, set Breeze's battery use to Unrestricted (on Samsung phones: Settings › Apps › Breeze › Battery).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("App settings") }
        if (showLocationTip) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Widgets showing My location use where you were the last time you opened Breeze. Allow location access all the time to keep them current.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { permission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Allow all the time") }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
