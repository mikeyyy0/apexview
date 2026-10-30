import java.time.LocalDateTime
import java.util.Properties
import java.time.format.DateTimeFormatter

// Release signing lives outside the project (see keystore.properties); without it, release
// builds stay unsigned and only debug builds work.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.apexview"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.apexview"
        minSdk = 29
        targetSdk = 35
        // Bump both for every build that goes to the phone, so it's clear which one is installed.
        versionCode = 71
        versionName = "0.71"
        val builtAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
        buildConfigField("String", "BUILT_AT", "\"$builtAt\"")
        // Where updates come from (owner/repo on GitHub). A fork points this at its own releases.
        buildConfigField("String", "UPDATE_REPO", "\"mikeyyy0/apexview\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // The build to share: signed with its own key and shrunk by R8 (the icon set alone is ~40 MB
        // unshrunk), so it installs like a normal app instead of a debug build.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        // The release build under its own app ID, to smoke-test shrinking on a phone without
        // replacing the installed app.
        create("smoke") {
            initWith(getByName("release"))
            applicationIdSuffix = ".smoke"
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val media3 = "1.5.1"

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // WebSocket for F1's live timing feed (SignalR Core).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Fold posture (half-open like a laptop) for laptop mode.
    implementation("androidx.window:window:1.3.0")
    // Chrome sheet for the Driver of the Day vote, so the race stays on screen.
    implementation("androidx.browser:browser:1.8.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; android.jar only has stubs.
    testImplementation("org.json:json:20240303")
}
