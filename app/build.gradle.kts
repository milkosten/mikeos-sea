plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

android {
    namespace = "com.mikeos.sea"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mikeos.sea"
        minSdk = 31
        targetSdk = 35
        versionCode = 16
        versionName = "0.9.5-depths-live"

        // MikeDaemon runs ON the phone (loopback). Auth token is pinned for dev.
        buildConfigField("String", "DAEMON_BASE_URL", "\"https://127.0.0.1:7743\"")
        buildConfigField(
            "String",
            "DAEMON_TOKEN",
            "\"7bdc23451b18b5801036f992b66a872670975d19\""
        )

        // Self-hosted MapLibre vector basemap (loads "$BASEMAP_URL/style.json").
        buildConfigField("String", "BASEMAP_URL", "\"https://tiles.osmike.com\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MapLibre ships native .so per ABI. These APKs go OTA over cellular, so drop x86
        // emulator libs — arm64-v8a for modern phones, armeabi-v7a for older 32-bit ones.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Background heartbeat
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Local structured store (Room) — network-context observations, shaped for future cloud sync
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // MapLibre GL Native — the FOSS vector map engine rendering the self-hosted OSM basemap.
    // Its HTTP stack is pointed at our DoH client (this ROM's system DNS is flaky). See MapLibreInit.
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
