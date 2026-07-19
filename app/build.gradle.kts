plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.native.cinterop") version "1.9.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20"
}

val ndkVersion = "26.1.10909125"

android {
    namespace = "com.piano.sequencer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.piano.sequencer"
        minSdk = 26
        targetSdk = 29
        versionCode = 1
        versionName = "0.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_TOOLCHAIN=clang"
                arguments += "-DFLUIDSYNTH_ENABLE_SHARED=OFF"
                arguments += "-DFLUIDSYNTH_ENABLE_DASHBOARD=OFF"
                arguments += "-DFLUIDSYNTH_ENABLE_READLINE=OFF"
                arguments += "-DFLUIDSYNTH_ENABLE_SQLITE3=OFF"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = ndkVersion
        }
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}