plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.micplugin"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.micplugin"
        minSdk = 26
        targetSdk = 34
        // Version: a release tag (RELEASE_TAG=v1.2.3) wins; otherwise every CI build gets its own
        // number from the run counter, e.g. 1.1.14-ab12cd3, so each build is told apart on the phone.
        val tag = System.getenv("RELEASE_TAG")
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        val sha = (System.getenv("GITHUB_SHA") ?: "").take(7)
        if (tag != null) {
            val cleanTag = tag.removePrefix("v")
            val parts = cleanTag.split(".").mapNotNull { it.toIntOrNull() }
            versionCode = (parts.getOrElse(0){1} * 10000) +
                          (parts.getOrElse(1){0} * 100) +
                           parts.getOrElse(2){0}
            versionName = cleanTag
        } else {
            versionCode = 20000 + (run ?: 0)   // always above the old 10000, rises every build
            versionName = "1.1.${run ?: 0}" + if (sha.isNotEmpty()) "-$sha" else "-local"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math", "-DANDROID")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DFETCHCONTENT_QUIET=OFF"
                )
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }
    }

    // A fixed debug key (the public, standard "android" debug credentials) so that every CI build
    // is signed the same way and a new APK installs over the previous one without uninstalling.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
        prefab = true          // Required: exposes Oboe CMake targets via Prefab AAR
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Audio (Oboe via prefab)
    implementation(libs.oboe)

    // Kotlin
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    // Core
    implementation(libs.core.ktx)

    // Shizuku — ADB-level privileged API access (no root required)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
