package com.breeze.weather.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.breeze.weather.data.LocationAccess
import com.breeze.weather.data.Place

private enum class Screen { Home, Locations }

@Composable
fun BreezeApp(vm: WeatherViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()

    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var focusSearch by remember { mutableStateOf(false) }
    var targetPageId by rememberSaveable { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState { state.pages.size }

    // --- Location permission ---
    var requestStartedAt by remember { mutableLongStateOf(0L) }
    var accessBeforeRequest by remember { mutableStateOf(LocationAccess.NONE) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        vm.onPermissionsChanged()
        val after = vm.state.value.location.access
        val improved = after.ordinal > accessBeforeRequest.ordinal
        if (improved) {
            targetPageId = Place.CURRENT_ID
            screen = Screen.Home
        } else if (SystemClock.elapsedRealtime() - requestStartedAt < 400 && after != LocationAccess.PRECISE) {
            // The system answered instantly without showing a dialog: the user previously chose
            // "Don't allow". The only way forward is the app's settings page.
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    val requestLocation: () -> Unit = {
        accessBeforeRequest = state.location.access
        requestStartedAt = SystemClock.elapsedRealtime()
        vm.markAskedForLocation()
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    // Ask once on first launch so the app opens straight to local weather.
    LaunchedEffect(Unit) {
        if (!state.location.askedBefore && state.location.access == LocationAccess.NONE) requestLocation()
    }

    // Pick up permission changes made in system settings, and refresh stale data on return.
    LifecycleResumeEffect(Unit) {
        vm.onPermissionsChanged()
        vm.refreshAll(force = false)
        onPauseOrDispose { }
    }

    // Jump to a specific page once it exists (e.g. after picking a place in Locations).
    LaunchedEffect(targetPageId, state.pages, screen) {
        val id = targetPageId ?: return@LaunchedEffect
        if (screen != Screen.Home) return@LaunchedEffect
        val index = state.pages.indexOfFirst { it.id == id }
        if (index >= 0) {
            pagerState.scrollToPage(index)
            targetPageId = null
        }
    }

    BackHandler(enabled = screen == Screen.Locations) {
        vm.onQueryChange("")
        screen = Screen.Home
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (targetState == Screen.Locations) {
                (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 12 }) togetherWith fadeOut(tween(180))
            } else {
                fadeIn(tween(220)) togetherWith fadeOut(tween(180))
            }
        },
        label = "screen",
    ) { current ->
        when (current) {
            Screen.Home -> HomeScreen(
                state = state,
                pagerState = pagerState,
                onOpenLocations = { focusSearch = false; screen = Screen.Locations },
                onSearch = { focusSearch = true; screen = Screen.Locations },
                onRefresh = vm::refresh,
                onRequestLocation = requestLocation,
            )

            Screen.Locations -> LocationsScreen(
                state = state,
                search = search,
                focusSearch = focusSearch,
                onBack = { vm.onQueryChange(""); screen = Screen.Home },
                onQueryChange = vm::onQueryChange,
                onSelect = { place ->
                    targetPageId = place.id
                    screen = Screen.Home
                },
                onAdd = vm::addPlace,
                onRemove = vm::removePlace,
                onRestore = vm::restorePlace,
                onUnits = vm::setUnits,
                onRequestLocation = requestLocation,
            )
        }
    }
}
