import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20"
}

val ndkVersion = "26.1.10909125"

// Release signing. Reads credentials from Gradle properties so secrets never
// land in version control. Sources (in lookup order):
//   - local.properties (local dev — gitignored)
//   - gradle.properties / project properties (CI injects them here)
// The keystore file (app/piano-release.jks) is safe to commit; only the
// passwords are secret. When the properties are absent the release build
// stays unsigned, so debug builds and CI without secrets keep working.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingProp(name: String): String? =
    (localProps.getProperty(name) ?: project.findProperty(name) as String?)

// ── Versioning ──
//
// Releases are cut from git tags (the CI `release` job fires on `v*` tag
// pushes). The git tag is the single source of truth for the release
// version: when HEAD is exactly a tag (e.g. v0.0.5), versionName is the
// tag with its leading "v" stripped — "0.0.5" — regardless of baseVersion
// below, so a tag can never ship a mismatched version even if baseVersion
// was not bumped in lockstep.
//
// Every other build (CI branch/PR builds, local dev) is NOT on a tag, so it
// appends the short commit hash to baseVersion so the exact source of an
// APK is identifiable at a glance: "0.0.5~abc1234". baseVersion therefore
// only governs the dev/CI version string, not the released one.
//
// "Release" is detected by `git describe --tags --exact-match HEAD` — it
// only succeeds when HEAD is exactly a tagged commit, which is the case for
// tag-triggered CI runs (actions/checkout checks out the tag in detached
// HEAD). Branch/PR runs are never on a tag, so they get the hash suffix.
// All git calls are defensive: if git is unavailable they fall back to
// baseVersion so the build never fails on a missing tool.
val baseVersion = "0.0.5"

fun runGit(vararg args: String): String? {
    return try {
        val p = ProcessBuilder("git", *args)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.isNotEmpty()) out else null
    } catch (_: Exception) {
        null
    }
}

// The tag HEAD sits on (e.g. "v0.0.5"), or null when HEAD is not a tag.
val gitExactTag: String? by lazy {
    runGit("describe", "--tags", "--exact-match", "HEAD")
}

val gitShortHash: String? by lazy { runGit("rev-parse", "--short=7", "HEAD") }

val resolvedVersionName: String by lazy {
    // Release: version comes from the tag itself (single source of truth).
    // Strip a leading "v" (tags are v0.0.5); tolerate bare numeric tags too.
    val tagVersion = gitExactTag?.removePrefix("v")
    if (tagVersion != null) {
        tagVersion
    } else if (gitShortHash != null) {
        "$baseVersion~$gitShortHash"
    } else {
        baseVersion
    }
}

android {
    namespace = "com.piano.sequencer"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    signingConfigs {
        create("release") {
            storeFile = file("piano-release.jks")
            storePassword = signingProp("piano.storePassword")
            keyAlias = signingProp("piano.keyAlias") ?: "piano"
            keyPassword = signingProp("piano.keyPassword")
        }
    }

    buildTypes {
        release {
            // Only apply the signing config when credentials are available;
            // otherwise fall back to the default (unsigned) release so builds
            // without secrets don't fail.
            val hasCreds = signingProp("piano.storePassword") != null &&
                signingProp("piano.keyPassword") != null
            if (hasCreds) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    defaultConfig {
        applicationId = "com.piano.sequencer"
        minSdk = 26
        targetSdk = 29
        versionCode = 5
        versionName = resolvedVersionName

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
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = false
    }

    // Package the prebuilt LSP LADSPA bundle (.so) as a JNI library so it is
    // extracted into the app's nativeLibraryDir at install time and loadable
    // via the absolute path passed to loadMasterEffectBundle(). Only arm64-v8a
    // is shipped in v1 (the bundle is cross-compiled for aarch64 only).
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/cpp/lsp-integration/prebuilt")
        }
    }

    // Force extraction of native libs to disk (extractNativeLibs=true).
    // AGP's default useLegacyPackaging=false leaves the .so inside the APK and
    // serves System.loadLibrary from there, but LadspaRegistry::open() dlopens
    // the bundle by its absolute nativeLibraryDir path — which fails with
    // "library not found" when the file is never materialized on disk.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    testImplementation("junit:junit:4.13.2")
}