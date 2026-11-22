import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Widget text drawn straight on the wallpaper needs a shadow, but RemoteViews can't set one from
 * code. So each widget layout gets a generated "_shadow" twin whose WidgetText.* styles are
 * swapped for their WidgetShadow.* versions (see res/values/widget_styles.xml).
 */
abstract class GenerateShadowWidgetLayouts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val layouts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile.resolve("layout")
        out.deleteRecursively()
        out.mkdirs()
        layouts.files.filter { it.readText().contains("@style/WidgetText.") }.forEach { file ->
            val text = file.readText()
                .replace("@style/WidgetText.", "@style/WidgetShadow.")
                .replaceFirst("?>", "?>\n<!-- Generated from ${file.name} by generateShadowWidgetLayouts. Don't edit. -->")
            out.resolve("${file.nameWithoutExtension}_shadow.xml").writeText(text)
        }
    }
}

val shadowWidgetLayouts = tasks.register<GenerateShadowWidgetLayouts>("generateShadowWidgetLayouts") {
    layouts.from(fileTree("src/main/res/layout") { include("widget_*.xml") })
    outputDir.set(layout.buildDirectory.dir("generated/res/widgetShadow"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(shadowWidgetLayouts, GenerateShadowWidgetLayouts::outputDir)
    }
}

android {
    namespace = "com.breeze.weather"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.breeze.weather"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.3.0-beta.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// WidgetResourcesTest reads res/ and the manifest directly, so changes there must rerun tests.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/AndroidManifest.xml").withPathSensitivity(PathSensitivity.RELATIVE)
}
