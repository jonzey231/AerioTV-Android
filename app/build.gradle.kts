import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Read the OAuth Web Client ID from local.properties (never checked in) so
// Drive Sync can do a real Sign-in with Google flow without leaking the
// project credentials into git. Each contributor pastes their own once.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val googleDriveWebClientId: String =
    localProps.getProperty("GOOGLE_DRIVE_WEB_CLIENT_ID")
        ?: System.getenv("GOOGLE_DRIVE_WEB_CLIENT_ID")
        ?: ""

// Release signing for Play uploads. Reads from keystore.properties (gitignored,
// never committed). When absent (e.g. a contributor without the upload key),
// the release signingConfig is simply not created and release builds stay
// unsigned -- debug builds are unaffected. Play App Signing holds the real
// app signing key; this is only the upload key (resettable via Play Console).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.aeriotv.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aeriotv.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 27
        versionName = "0.3.6"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        buildConfigField(
            "String",
            "GOOGLE_DRIVE_WEB_CLIENT_ID",
            "\"$googleDriveWebClientId\"",
        )
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // Distribution split (in-app updater). The GitHub/sideload channel carries
    // the self-updater + REQUEST_INSTALL_PACKAGES (github manifest overlay);
    // the Play channel must contain NEITHER -- Play policy forbids self-update
    // of Play-distributed builds and the manifest permission alone triggers a
    // declaration review. Build the GitHub release APK with
    // :app:assembleGithubRelease and the Play AAB with :app:bundlePlayRelease.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.windowsizeclass)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    // FFmpeg software audio decoder (built from the media3 1.4.1 decoder_ffmpeg
    // extension, AC-3/E-AC-3/DTS/TrueHD/MP2 enabled). Google ships no prebuilt,
    // so this AAR is built from source; it restores Dolby broadcast audio on
    // devices with no hardware AC-3 decoder (e.g. Chromecast with Google TV),
    // wired in as the fallback renderer by aerioRenderersFactory. Consumed via
    // files() so its base media3 classes resolve against the maven deps above.
    implementation(files("libs/media3-decoder-ffmpeg.aar"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.playservices)
    implementation(libs.google.identity.googleid)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.sh.calvin.reorderable)
    implementation(libs.androidx.media)
    // QR encoding for the TV log-share dialog (zxing CORE only: the
    // android-embedded wrapper drags in camera/legacy deps we don't need).
    implementation(libs.zxing.core)

    // ProfileInstaller: hooks into androidx.startup to AOT-compile the methods
    // listed in src/main/baseline-prof.txt at install time. Without this the
    // first paint of every Compose screen (GuideScreen, OnDemandTabContent,
    // MoviesSubScreen ...) is interpreted -> JIT-warmup -> AOT, which on a
    // slow TV device costs hundreds of ms per screen on cold launch and shows
    // up in logcat as `Compiler allocated 8141KB to compile GuideScreen`. With
    // a baseline profile those methods are AOT'd at install and run native
    // from the first frame.
    implementation(libs.androidx.profileinstaller)

    debugImplementation(libs.androidx.ui.tooling)
}
