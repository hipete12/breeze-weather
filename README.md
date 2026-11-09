# Breeze

A clean, modern Android weather app built with Kotlin and Jetpack Compose.

<p>
  <img src="docs/screenshots/home.png" width="200" alt="Home screen" />
  <img src="docs/screenshots/details.png" width="200" alt="Forecast and details" />
  <img src="docs/screenshots/locations.png" width="200" alt="Saved locations" />
  <img src="docs/screenshots/search.png" width="200" alt="City search" />
</p>

## Features

- **Precise current location.** Asks for fine location and requests a fresh high-accuracy
  fix (it doesn't rely on a stale cached one). If you grant only *approximate* access, a
  small "Use precise" pill lets you upgrade later. If you've permanently denied access, the
  same button opens the app's system settings.
- **Saved locations.** Search any city worldwide and save it. Swipe between places on the
  home screen, or open the list to jump to one. Swipe a saved place left to remove it
  (with undo).
- **Uncluttered UI.** One big temperature, a 24-hour strip, a 10-day forecast with
  temperature-range bars, and six detail tiles (feels like, humidity, wind, UV, sunrise,
  sunset). The background gradient follows the current conditions and day/night.
- **°C / °F toggle.** The default follows your locale.
- **Fast start and offline-friendly.** The last forecast for each place is cached on disk and
  shown instantly, then refreshed in the background. Pull down to refresh.
- **No API key needed.** Forecasts and geocoding come from [Open-Meteo](https://open-meteo.com/).
- **No Google Play Services dependency.** Location uses the platform `LocationManager`
  (with the fused provider on Android 12+).

## Requirements

| Tool | Version |
| --- | --- |
| JDK | 17 or newer |
| Android SDK | Platform 37 (compile), build-tools 36+ |
| Gradle | Provided by the wrapper (9.8.2), so no separate install is needed |
| Device / emulator | Android 8.0 (API 26) or newer |

Android Studio (latest stable) works out of the box: open the project folder and press **Run**.

## Building the APK from the command line

1. **Point Gradle at your Android SDK.** Do one of the following:

   ```bash
   export ANDROID_HOME="$HOME/Android/Sdk"
   ```

   or create a `local.properties` file in the project root (it's git-ignored):

   ```properties
   sdk.dir=/home/<you>/Android/Sdk
   ```

2. **Install the SDK platform** if you don't have it yet:

   ```bash
   "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" "build-tools;36.0.0"
   ```

3. **Build a debug APK**, which is signed with the debug key and installable right away:

   ```bash
   ./gradlew assembleDebug
   ```

   Output: `app/build/outputs/apk/debug/app-debug.apk`

4. **Or build a release APK**, minified with R8:

   ```bash
   ./gradlew assembleRelease
   ```

   Output: `app/build/outputs/apk/release/app-release.apk`

   > The release build is currently signed with the **debug key** so it can be sideloaded
   > directly. Before publishing to the Play Store, add your own `signingConfigs` block in
   > `app/build.gradle.kts` and point `release` at it.

5. **Run the unit tests:**

   ```bash
   ./gradlew testDebugUnitTest
   ```

## Installing on a device or emulator

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.breeze.weather/.MainActivity
```

Or build and install in one step:

```bash
./gradlew installDebug
```

### Testing location on an emulator

```bash
# Create and start an emulator (any API 26+ image works)
avdmanager create avd -n weather_phone -k "system-images;android-36;google_apis;x86_64" -d pixel_7
emulator -avd weather_phone &

# Simulate a GPS fix (longitude first, then latitude)
adb emu geo fix -122.4194 37.7749   # San Francisco
```

The emulator only delivers a fix while an app is actively listening. If the location doesn't
change, pull to refresh on the "My location" page and send the `geo fix` again.

## Project structure

```
app/src/main/java/com/breeze/weather/
├── MainActivity.kt          # Edge-to-edge host activity
├── data/
│   ├── Models.kt            # Domain models + Open-Meteo DTOs
│   ├── Condition.kt         # WMO weather code → condition/description
│   ├── WeatherRepository.kt # Forecast + geocoding HTTP client with disk cache
│   ├── LocationProvider.kt  # Precise current location + reverse geocoding
│   └── PlacesStore.kt       # Saved places and unit preference (SharedPreferences)
└── ui/
    ├── BreezeApp.kt         # Navigation + permission handling
    ├── WeatherViewModel.kt  # App state, refresh policy, search
    ├── HomeScreen.kt        # Pager of places with forecast cards
    ├── LocationsScreen.kt   # Search, saved places, unit toggle
    ├── WeatherVisuals.kt    # Icons, gradients, formatting helpers
    └── theme/Theme.kt
```

## Tech stack

Kotlin 2.4 · Jetpack Compose (Material 3) · AndroidX Lifecycle/ViewModel · kotlinx.serialization ·
Coroutines/Flow · Android Gradle Plugin 9.5.

## Credits

Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0).
