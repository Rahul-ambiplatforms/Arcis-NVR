plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.arcisai.nvr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arcisainvr.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags  += "-std=c++17"
                cFlags    += "-std=c11"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release signing — credentials read from environment variables so nothing
    // sensitive lives in git. Required env vars for a release build:
    //   KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
    // If any are missing the release signingConfig is left unset and the build
    // will fail explicitly at signing time (rather than silently producing an
    // unsigned APK).
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            val ksPwd  = System.getenv("KEYSTORE_PASSWORD")
            val kAlias = System.getenv("KEY_ALIAS")
            val kPwd   = System.getenv("KEY_PASSWORD")
            if (!ksPath.isNullOrBlank() && !ksPwd.isNullOrBlank() &&
                !kAlias.isNullOrBlank() && !kPwd.isNullOrBlank()) {
                storeFile       = file(ksPath)
                storePassword   = ksPwd
                keyAlias        = kAlias
                keyPassword     = kPwd
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {    
        debug {
            // Debug builds use Android's auto-generated debug keystore.
            // Note: a debug-signed build can't install over a release-signed production app
            // (INSTALL_FAILED_UPDATE_INCOMPATIBLE) — uninstall the production app first.
        }
        release {
            signingConfig  = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0", "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    // AndroidX core + lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // Networking â€” OkHttp w/ Basic auth (NetSdkApi + publisher).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Retrofit + Gson for the Arcis cloud backend (auth/login, abd/addAbd, abd/getAbd).
    // HTTP-only cookie session â€” same shape the production ArcisAI-Android app uses.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Encrypted creds storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Media3 (ExoPlayer) â€” kept for HLS / DASH / playback; RTSP swapped to libVLC
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // libVLC â€” RTSP player (Media3's RTSP rejects SDPs without fmtp; camera doesn't send it)
    implementation("org.videolan.android:libvlc-all:3.6.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
