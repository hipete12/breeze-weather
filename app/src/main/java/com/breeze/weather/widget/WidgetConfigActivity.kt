package com.breeze.weather.widget

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.breeze.weather.data.Condition
import com.breeze.weather.data.LocationAccess
import com.breeze.weather.data.LocationProvider
import com.breeze.weather.data.Place
import com.breeze.weather.data.PlacesStore
import com.breeze.weather.data.WeatherRepository
import com.breeze.weather.ui.gradient
import com.breeze.weather.ui.theme.BreezeTheme
import com.breeze.weather.ui.theme.InkRaised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Customizes one widget, with a live preview drawn over the home screen wallpaper. Opened by the
 * launcher when a widget is placed (and from its "Settings" on Android 12+), from the app's
 * Widgets screen, or by tapping a widget set to open its settings. From the app it can also
 * configure a new widget before asking the launcher to pin it.
 */
class WidgetConfigActivity : ComponentActivity() {

    enum class Mode { PLACE, EDIT, PIN }

    private val vm: WidgetConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val hasId = appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        val kind = if (hasId) WidgetUpdater.kindOf(this, appWidgetId) else WidgetKind.fromName(intent.getStringExtra(EXTRA_KIND))
        // Until saved, a widget being placed is cancelled (the launcher then removes it).
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (kind == null) {
            finish()
            return
        }
        val store = WidgetStore(this)
        val mode = when {
            !hasId -> Mode.PIN
            store.config(appWidgetId) == null -> Mode.PLACE
            else -> Mode.EDIT
        }
        vm.init(appWidgetId, kind, mode)

        setContent {
            BreezeTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                BackHandler { finish() }
                val preview = remember(state) { vm.preview(state) }
                WidgetConfigScreen(
                    state = state,
                    preview = preview,
                    onChange = vm::update,
                    onClose = ::finish,
                    onSave = ::save,
                    onLocationChanged = vm::refreshPermissions,
                )
            }
        }
    }

    private fun save() {
        val state = vm.state.value
        val store = WidgetStore(this)
        when (state.mode) {
            Mode.PIN -> {
                pin(state)
                finish()
            }
            Mode.PLACE, Mode.EDIT -> {
                store.save(state.appWidgetId, state.config)
                store.clearPendingPin(state.kind)
                lifecycleScope.launch {
                    withContext(Dispatchers.Default) {
                        WidgetUpdater.schedule(applicationContext)
                        val stale = WidgetUpdater.update(applicationContext, listOf(state.appWidgetId to state.kind))
                        if (stale || state.config.place != state.initialPlace) WidgetUpdater.requestRefresh(applicationContext, force = false)
                    }
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, state.appWidgetId))
                    finish()
                }
            }
        }
    }

    /** Asks the launcher to add a widget with these settings; [WidgetPinReceiver] stores them. */
    private fun pin(state: ConfigState) {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, "Your launcher can't add widgets from apps. Long-press the home screen and choose Widgets.", Toast.LENGTH_LONG).show()
            return
        }
        WidgetStore(this).setPendingPin(state.kind, state.config)
        val callback = Intent(this, WidgetPinReceiver::class.java)
            .putExtra(WidgetPinReceiver.EXTRA_KIND, state.kind.name)
            .putExtra(WidgetPinReceiver.EXTRA_CONFIG, WidgetStore.json.encodeToString(state.config.sanitized()))
        // Mutable: the launcher adds the new widget's id to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(this, state.kind.ordinal, callback, flags)
        val extras = Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, vm.preview(state)?.views) }
        manager.requestPinAppWidget(ComponentName(this, WidgetProviders.providerClass(state.kind)), extras, pending)
    }

    companion object {
        const val EXTRA_KIND = "com.breeze.weather.extra.WIDGET_KIND"

        /** Opens the settings of the placed widget [appWidgetId]. */
        fun editIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

        /** Configures a new widget of [kind], then asks the launcher to pin it. */
        fun pinIntent(context: Context, kind: WidgetKind): Intent =
            Intent(context, WidgetConfigActivity::class.java).putExtra(EXTRA_KIND, kind.name)
    }
}

/** Everything the configuration screen shows. */
data class ConfigState(
    val appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
    val kind: WidgetKind = WidgetKind.CONDITIONS,
    val mode: WidgetConfigActivity.Mode = WidgetConfigActivity.Mode.EDIT,
    val config: WidgetConfig = WidgetConfig(),
    val initialPlace: Place? = null,
    val places: List<Place> = emptyList(),
    val currentPlace: Place? = null,
    val locationAccess: LocationAccess = LocationAccess.NONE,
    val backgroundLocation: Boolean = false,
    val data: WidgetData? = null,
    /** True while the preview shows made-up weather because there's no forecast for the place. */
    val sample: Boolean = false,
    val previewWidthDp: Float = 0f,
    val previewHeightDp: Float = 0f,
)

class WidgetPreviewViews(val views: RemoteViews, val widthDp: Float, val heightDp: Float)

class WidgetConfigViewModel(app: Application) : AndroidViewModel(app) {

    private val places = PlacesStore(app)
    private val repo = WeatherRepository(app)
    private val locator = LocationProvider(app)
    private val renderer = WidgetRenderer(app)

    private val _state = MutableStateFlow(ConfigState())
    val state: StateFlow<ConfigState> = _state.asStateFlow()
    private var initialized = false
    private var loadJob: Job? = null

    fun init(appWidgetId: Int, kind: WidgetKind, mode: WidgetConfigActivity.Mode) {
        if (initialized) return
        initialized = true
        val store = WidgetStore(getApplication())
        val config = store.config(appWidgetId) ?: store.pendingPin(kind) ?: kind.defaultConfig()
        val size = if (mode == WidgetConfigActivity.Mode.PIN) WidgetSizes.default(kind)
        else WidgetSizes.from(AppWidgetManager.getInstance(getApplication()).getAppWidgetOptions(appWidgetId), kind)
        _state.value = ConfigState(
            appWidgetId = appWidgetId,
            kind = kind,
            mode = mode,
            config = config,
            initialPlace = config.place,
            places = places.saved.value,
            currentPlace = places.lastCurrentPlace,
            previewWidthDp = size.primary.width,
            previewHeightDp = size.primary.height,
        )
        refreshPermissions()
        loadPreviewData()
    }

    fun update(transform: (WidgetConfig) -> WidgetConfig) {
        val before = _state.value.config
        _state.update { it.copy(config = transform(it.config).sanitized()) }
        if (_state.value.config.place != before.place) loadPreviewData()
    }

    fun refreshPermissions() {
        val access = locator.access()
        _state.update {
            it.copy(
                locationAccess = access,
                backgroundLocation = WidgetUpdater.canLocateInBackground(getApplication()),
                currentPlace = places.lastCurrentPlace,
            )
        }
    }

    /** Loads the forecast to preview: cached, else fetched, else a made-up sample. */
    private fun loadPreviewData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val config = _state.value.config
            val place = config.place ?: places.lastCurrentPlace?.takeIf { locator.access() != LocationAccess.NONE }
            val units = places.units.value
            val weather = place?.let { repo.cached(it, units) ?: runCatching { repo.forecast(it, units) }.getOrNull() }
            val data = if (weather != null) WidgetData(place, weather, DataState.READY)
            else WidgetData(place ?: SampleWeather.place.copy(name = "My location"), SampleWeather.create(units), DataState.READY)
            _state.update { it.copy(data = data, sample = weather == null) }
        }
    }

    fun preview(state: ConfigState): WidgetPreviewViews? {
        val data = state.data ?: return null
        val bitmaps = WidgetBitmaps(getApplication<Application>().resources.displayMetrics.density)
        val views = renderer.render(state.appWidgetId, state.kind, state.config, data, state.previewWidthDp, state.previewHeightDp, bitmaps, interactive = false)
        return WidgetPreviewViews(views, state.previewWidthDp, state.previewHeightDp)
    }
}

// ---------- UI ----------

@Composable
private fun WidgetConfigScreen(
    state: ConfigState,
    preview: WidgetPreviewViews?,
    onChange: ((WidgetConfig) -> WidgetConfig) -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onLocationChanged: () -> Unit,
) {
    LifecycleResumeEffect(Unit) {
        onLocationChanged()
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize()) {
        // The preview floats over the real wallpaper (the window shows it), so opacity and text
        // colours can be judged against it.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 400.dp)
                .height((state.previewHeightDp + 96).dp)
                .statusBarsPadding(),
        ) {
            if (preview != null) {
                FittedRemoteViewsPreview(
                    preview.views, preview.widthDp, preview.heightDp,
                    Modifier
                        .fillMaxSize()
                        .padding(start = 20.dp, end = 20.dp, top = 52.dp, bottom = 16.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f), contentColor = Color.White),
                ) { Icon(Icons.Rounded.Close, contentDescription = "Cancel") }
                Spacer(Modifier.width(8.dp))
                Text(
                    state.kind.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                if (state.sample) {
                    Text(
                        "Sample weather",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column {
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
                    item { LocationSection(state, onChange) }
                    item { StyleSection(state.config, onChange) }
                    item { ContentSection(state.kind, state.config, onChange) }
                    if (WidgetSection.DETAILS in state.config.sections || state.kind == WidgetKind.CONDITIONS) {
                        item { MetricsSection(state.kind, state.config, onChange) }
                    }
                    item { TapSection(state.config, onChange) }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(InkRaised)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = onClose) { Text("Cancel") }
                    Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                        Text(
                            when (state.mode) {
                                WidgetConfigActivity.Mode.PLACE -> "Add widget"
                                WidgetConfigActivity.Mode.EDIT -> "Save"
                                WidgetConfigActivity.Mode.PIN -> "Add to home screen"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun LocationSection(state: ConfigState, onChange: ((WidgetConfig) -> WidgetConfig) -> Unit) {
    val context = LocalContext.current
    SectionTitle("Location")
    PlaceRow(
        title = "My location",
        subtitle = when {
            state.locationAccess == LocationAccess.NONE -> "Allow location access in Breeze to use this"
            state.currentPlace != null -> "Now ${state.currentPlace.name}"
            else -> "Wherever you are"
        },
        current = true,
        selected = state.config.place == null,
        onClick = { onChange { it.copy(place = null) } },
    )
    state.places.forEach { place ->
        PlaceRow(
            title = place.name,
            subtitle = place.subtitle,
            current = false,
            selected = state.config.place?.id == place.id,
            onClick = { onChange { it.copy(place = place) } },
        )
    }
    // A widget can keep a place the user has since removed from the app.
    val kept = state.config.place?.takeIf { p -> state.places.none { it.id == p.id } }
    if (kept != null) PlaceRow(kept.name, kept.subtitle, current = false, selected = true, onClick = {})
    if (state.places.isEmpty()) {
        Text(
            "Add places in Breeze to show them here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }

    // Following the device's location in the background needs "Allow all the time".
    val needsBackground = state.config.followsLocation && state.locationAccess != LocationAccess.NONE &&
        !state.backgroundLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    if (needsBackground) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(InkRaised)
                .padding(16.dp),
        ) {
            Text("Keep the location current", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "The widget uses where you were the last time you opened Breeze. To update it while the app is closed, allow location access all the time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }) { Text("Allow all the time") }
        }
    }
}

@Composable
private fun PlaceRow(title: String, subtitle: String, current: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Icon(
            if (current) Icons.Rounded.Navigation else Icons.Rounded.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StyleSection(config: WidgetConfig, onChange: ((WidgetConfig) -> WidgetConfig) -> Unit) {
    SectionTitle("Style")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        WidgetTheme.entries.forEach { theme ->
            ThemeSwatch(theme, selected = config.theme == theme) { onChange { it.copy(theme = theme) } }
        }
    }
    Spacer(Modifier.height(12.dp))
    var opacity by remember(config.opacity) { mutableIntStateOf(config.opacity) }
    LabeledRow("Background", "$opacity%")
    Slider(
        value = opacity.toFloat(),
        // Steps of 5%, without the slider's tick marks.
        onValueChange = { opacity = (it / 5).roundToInt() * 5 },
        onValueChangeFinished = { onChange { c -> c.copy(opacity = opacity) } },
        valueRange = 0f..100f,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    LabeledRow("Text size", null)
    Segmented(TextScale.entries, config.textScale, { it.label }) { scale -> onChange { it.copy(textScale = scale) } }
}

@Composable
private fun ThemeSwatch(theme: WidgetTheme, selected: Boolean, onClick: () -> Unit) {
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val (label, background, textColor) = when (theme) {
        WidgetTheme.SKY -> Triple("Sky", Brush.verticalGradient(Condition.PARTLY_CLOUDY.gradient(true)), Color.White)
        WidgetTheme.SYSTEM -> Triple(
            if (dynamic) "Dynamic" else "Auto",
            Brush.linearGradient(0.5f to Color(0xFFE8EDF7), 0.5f to Color(0xFF2A3146)),
            Color.White,
        )
        WidgetTheme.DARK -> Triple("Dark", Brush.verticalGradient(listOf(Color(0xFF141A2A), Color(0xFF141A2A))), Color.White)
        WidgetTheme.LIGHT -> Triple("Light", Brush.verticalGradient(listOf(Color(0xFFF7F9FC), Color(0xFFF7F9FC))), Color(0xFF1A1F2B))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .size(width = 68.dp, height = 52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(background)
                .then(
                    if (selected) Modifier.border(BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(14.dp))
                    else Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(14.dp))
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
            } else if (theme == WidgetTheme.SYSTEM) {
                // Half light, half dark: no single text colour reads on both.
                Icon(Icons.Rounded.Contrast, contentDescription = null, tint = Color(0xFF8A93A6), modifier = Modifier.size(22.dp))
            } else {
                Text("21°", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Light)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ContentSection(kind: WidgetKind, config: WidgetConfig, onChange: ((WidgetConfig) -> WidgetConfig) -> Unit) {
    SectionTitle("Show")
    if (kind.isStrip) {
        StripContent(kind, config, onChange)
        return
    }
    if (kind.hasSections) {
        WidgetSection.entries.forEach { section ->
            SwitchRow(section.label, checked = section in config.sections) { on ->
                onChange { c ->
                    val sections = if (on) c.sections + section else c.sections - section
                    // Keep at least one section.
                    c.copy(sections = sections.ifEmpty { c.sections })
                }
            }
        }
        Text(
            "Sections that don't fit are left out; resize the widget to see more.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
    val hasHeader = WidgetSection.HEADER in config.sections || !kind.hasSections
    if (hasHeader) {
        SwitchRow("Place name", config.showPlace) { on -> onChange { it.copy(showPlace = on) } }
        SwitchRow("Condition", config.showCondition) { on -> onChange { it.copy(showCondition = on) } }
        SwitchRow("High and low", config.showHighLow) { on -> onChange { it.copy(showHighLow = on) } }
        SwitchRow("Feels like", config.showFeelsLike) { on -> onChange { it.copy(showFeelsLike = on) } }
        SwitchRow("Time of last update", config.showUpdated) { on -> onChange { it.copy(showUpdated = on) } }
    }
    if (WidgetSection.HOURLY in config.sections || WidgetSection.DAILY in config.sections) {
        SwitchRow("Chance of rain", config.showRainChance) { on -> onChange { it.copy(showRainChance = on) } }
    }
    if (WidgetSection.DAILY in config.sections) {
        SwitchRow("Temperature bars", config.showRangeBars) { on -> onChange { it.copy(showRangeBars = on) } }
    }
    if (WidgetSection.HOURLY in config.sections) {
        LabeledRow("Hourly forecast every", null)
        Segmented(listOf(1, 2, 3), config.hourStep, { if (it == 1) "1 hour" else "$it hours" }) { step -> onChange { it.copy(hourStep = step) } }
    }
}

/** Strips are a fixed row, so their options are only about the numbers in it. */
@Composable
private fun StripContent(kind: WidgetKind, config: WidgetConfig, onChange: ((WidgetConfig) -> WidgetConfig) -> Unit) {
    SwitchRow("High and low", config.showHighLow) { on -> onChange { it.copy(showHighLow = on) } }
    SwitchRow("Colour by temperature", config.colorTemps) { on -> onChange { it.copy(colorTemps = on) } }
    Text(
        "High and low" + (if (kind == WidgetKind.DAILY_STRIP) ", for today and each day," else "") + " go from blue when it's cold to red when it's hot.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    if (kind == WidgetKind.MINI) return
    val hours = kind == WidgetKind.HOURLY_STRIP
    SwitchRow("Place name", config.stripPlace) { on -> onChange { it.copy(stripPlace = on) } }
    SwitchRow("Chance of rain", config.showRainChance) { on -> onChange { it.copy(showRainChance = on) } }
    LabeledRow(if (hours) "Hours to show" else "Days to show", null)
    Segmented(
        (WidgetConfig.MIN_STRIP_COUNT..WidgetConfig.MAX_STRIP_COUNT).toList(),
        config.stripCount,
        { "$it" },
    ) { count -> onChange { it.copy(stripCount = count) } }
    Text(
        "As many as fit, up to this number. Chance of rain shows when the widget is tall enough.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    if (hours) {
        LabeledRow("One every", null)
        Segmented(listOf(1, 2, 3), config.hourStep, { if (it == 1) "1 hour" else "$it hours" }) { step -> onChange { it.copy(hourStep = step) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricsSection(kind: WidgetKind, config: WidgetConfig, onChange: ((WidgetConfig) -> WidgetConfig) -> Unit) {
    SectionTitle("Details")
    Text(
        if (kind == WidgetKind.CONDITIONS && WidgetSection.DETAILS !in config.sections)
            "Shown beside the temperature when the widget is wide. Tap in the order you want them."
        else "Tap in the order you want them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Metric.entries.forEach { metric ->
            val index = config.metrics.indexOf(metric)
            FilterChip(
                selected = index >= 0,
                onClick = {
                    onChange { c -> c.copy(metrics = if (index >= 0) c.metrics - metric else c.metrics + metric) }
                },
                label = { Text(if (index >= 0) "${index + 1}  ${metric.label}" else metric.label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun TapSection(config: WidgetConfig, onChange: ((WidgetConfig) -> WidgetConfig) -> Unit) {
    SectionTitle("When tapped")
    Segmented(TapAction.entries, config.tapAction, { it.label }) { action -> onChange { it.copy(tapAction = action) } }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledRow(label: String, value: String?) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
