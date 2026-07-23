plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ch.overlandmap.map"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose — exposed (api) so the thin app shells (MainActivity, navigation)
    // can build against it without re-declaring.
    api(platform("androidx.compose:compose-bom:2024.12.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.navigation:navigation-compose:2.8.5")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // AppCompat: per-app locales, and the app shells' AppCompatActivity.
    api("androidx.appcompat:appcompat:1.7.0")

    // Preferences (units, selected language, …)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Local library database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Firebase (config resources come from each app's google-services.json)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Google Sign-In (Credential Manager)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // In-app purchases
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    // Background downloads that survive the app being suspended or killed
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Map rendering (Mapbox GL fork; reads the MBTiles served by LocalTileServer)
    implementation("org.maplibre.gl:android-sdk:11.7.1")

    // Mapbox Maps SDK: itinerary screen only, for satellite offline tiles
    // (TileStore/OfflineManager). Needs MAPBOX_DOWNLOADS_TOKEN (see
    // settings.gradle.kts). Coexists with MapLibre — different package.
    // The `-ndk27` variant ships 16 KB-page-aligned native libs (Android 15+).
    implementation("com.mapbox.maps:android-ndk27:11.26.0")

    // Images
    implementation("io.coil-kt:coil-compose:2.7.0")
}
