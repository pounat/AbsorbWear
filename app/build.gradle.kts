import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Sign watch APKs with the *same* keystore as the main Absorb phone app.
// Required by the Wearable Data Layer: Play Services only routes messages
// between two apps that share both packageId AND signing key. A
// key.properties in this repo's root wins (the release workflow writes one
// there); otherwise the phone app's own absorb/android/key.properties next
// door is used, so a local build never gets out of sync with it.
val keyPropsFile = listOf(
    rootProject.file("key.properties"),
    rootProject.file("../absorb/android/key.properties"),
).firstOrNull { it.exists() }
val keystoreProps = Properties().apply {
    if (keyPropsFile != null) {
        FileInputStream(keyPropsFile).use { load(it) }
    }
}

// The watch app carries the phone app's version. The release workflow passes
// the pubspec version in (-PversionName=1.12.1 -PversionCode=305); a local
// build falls back to the numbers here, which are bumped with the phone.
// Play keeps the watch bundle in the phone app's listing, and every bundle
// there needs its own version code that is never reused, so the watch's is
// a million plus the phone's times ten plus a revision: phone 305 is watch
// 1003050, and a watch-only rebuild of the same phone build goes up with
// -PwearRevision=1 to 1003051.
val absorbVersionName = (project.findProperty("versionName") as String?) ?: "1.12.1"
val phoneVersionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 305
val wearRevision = (project.findProperty("wearRevision") as String?)?.toInt() ?: 0
val absorbVersionCode = 1_000_000 + phoneVersionCode * 10 + wearRevision

android {
    namespace = "com.barnabas.absorb.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.barnabas.absorb"
        minSdk = 30
        targetSdk = 35
        versionCode = absorbVersionCode
        versionName = absorbVersionName
        resValue("string", "app_name", "Absorb")
    }

    // Mirrors the phone app's flavors so each watch build pairs with the phone
    // build that shares its application id: "github" with the stable Absorb,
    // "playstore" with the Play install (same id, uploaded as a bundle to the
    // listing's Wear OS track), "dev" with the Absorb Dev install
    // (com.barnabas.absorb.dev). The Data Layer only routes between apps with
    // the same id, so a dev phone needs a dev watch.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
        create("dev") {
            dimension = "distribution"
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Absorb Dev")
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("absorbShared") {
                keyAlias = keystoreProps["keyAlias"] as String?
                keyPassword = keystoreProps["keyPassword"] as String?
                storeFile = (keystoreProps["storeFile"] as String?)?.let { file(it) }
                storePassword = keystoreProps["storePassword"] as String?
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Match the phone-app override (debug builds signed with release
            // key) so Wear OS Data Layer messages route between them.
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("absorbShared")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("absorbShared")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    buildFeatures {
        compose = true
    }

    lint {
        // No fragments here; the activity-result API is used from a plain
        // ComponentActivity, which this check does not understand.
        disable += "InvalidFragmentVersionForActivityResult"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.graphics.shapes)
    implementation(libs.androidx.palette)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.horologist.compose.layout)
    implementation(libs.wear.complications.data.source)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)

    // Media3 session — lets the watch own a MediaSession so Wear OS surfaces
    // our controls (and the ongoing activity that opens us) instead of the
    // generic system media template. media3-common brings SimpleBasePlayer.
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    // ExoPlayer — standalone on-watch streaming of ABS audio.
    implementation(libs.androidx.media3.exoplayer)
    // media3-ui only for WearUnsuitableOutputPlaybackSuppressionResolverListener:
    // Wear OS will not play media through the watch speaker, so this pauses
    // and opens the system output picker until headphones connect.
    implementation(libs.androidx.media3.ui)
    // On-disk stream cache (and, later, downloads) share one SimpleCache.
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)
    implementation(libs.health.services.client)

    // Coil — loads ABS cover art directly from the server (auth headers are
    // attached per-request so reverse-proxied servers don't 403).
    implementation(libs.coil.compose)
    testImplementation("junit:junit:4.13.2")
}
