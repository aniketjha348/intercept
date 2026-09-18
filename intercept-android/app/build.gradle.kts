plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.intercept"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.intercept"
        minSdk = 29
        targetSdk = 34
        versionCode = 21
        versionName = "0.6.1"

        // WebRTC's jingle .so is 11–15 MB per ABI, and the x86 pair exists only
        // for emulators. Shipping them cost ~27 MB on every download for phones
        // that can never load them.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    // Signing order: real upload key from env (Play, never committed) →
    // shared repo debug keystore (same signature on EVERY build, everywhere:
    // local, CI, releases — installs update cleanly instead of conflicting).
    // Debug keystores are public by design; the Play upload key stays secret.
    signingConfigs {
        create("prod") {
            val ks = System.getenv("INTERCEPT_KEYSTORE") ?: ""
            if (ks.isNotBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("INTERCEPT_STORE_PASSWORD")
                keyAlias = System.getenv("INTERCEPT_KEY_ALIAS")
                keyPassword = System.getenv("INTERCEPT_KEY_PASSWORD")
            }
        }
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Emulators are x86_64. Listed in full so this holds whether AGP
            // merges these filters with defaultConfig or replaces them.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        release {
            // The shrinker is the whole reason a Compose app fits in a download:
            // it drops the thousands of unused extended icons, LiveKit's unused
            // Java and every other class nothing reaches. See proguard-rules.pro
            // for the kotlinx.serialization keeps it must not remove.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val ks = System.getenv("INTERCEPT_KEYSTORE") ?: ""
            signingConfig = if (ks.isNotBlank()) signingConfigs.getByName("prod")
            else signingConfigs.getByName("debug")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    // Pinned to 1.6.3: newer core runtimes demand Kotlin 2.x, but this
    // project stays on Kotlin 1.9.24 (compose compiler 1.5.14 needs it).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Studio-grade voice transport (WebRTC mic publish + agent audio play).
    implementation("io.livekit:livekit-android:2.28.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Keep every serialization artifact on the Kotlin-1.9-compatible line even if
// a transitive dependency (e.g. the Retrofit converter) pulls a newer one.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" &&
            requested.name.startsWith("kotlinx-serialization")
        ) {
            useVersion("1.6.3")
            because("Kotlin 1.9.24 project: newer runtimes require Kotlin 2.x")
        }
    }
}
