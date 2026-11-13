plugins {
    id("com.android.application")
}

// Avoid dependencies shared with the guest (Kotlin, okhttp, AndroidX runtime).
// apksig is the standalone Android build-tools verifier; desugaring uses the
// separate j$ namespace so the Java APIs work on our API 26 minimum as well.
android {
    namespace = "app.boxblox"
    compileSdk = 36
    // The NDK this project builds against on the development host. Every
    // native dependency here is platform-only (liblog, libdl, libc++), so the
    // version is a build-environment pin rather than an engine-facing ABI.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.boxblox"
        // Roblox's manifest declares minSdk 26; pointer capture is API 26+.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.android.tools.build:apksig:8.13.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // Test-only. `testImplementation` never reaches the app's runtime class
    // path, so the guest's class loader still sees no third-party library from
    // the wrapper — which is the property the empty runtime graph protects.
    // No AppCompat dependency, deliberately.
    //
    // The theme check that fails ("You need to use a Theme.AppCompat theme")
    // comes from j.g.b0 inside the ARCHIVE's own dex, and it reads
    // AppCompatTheme_windowActionBar from the activity's theme. The archive
    // already defines style/Theme.AppCompat.Light.NoActionBar with
    // windowActionBar=false, so the archive supplies everything that check
    // needs. Bundling a second AppCompat in the wrapper only produced duplicate
    // Kotlin/okhttp/AndroidX classes that shadowed the archive's and broke it
    // (measured: NoSuchMethodError in kotlin.text.StringsKt from okhttp3).
    // AppCompat resources only, no runtime: the guest activity's theme descends
    // from Theme.AppCompat, and AppCompat (inside the archive) validates it from
    // GameActivity.onCreate. The archive supplies the classes; this supplies the
    // style/attr resources so the platform can resolve the theme at attach.
    implementation("androidx.appcompat:appcompat:1.7.0") {
        isTransitive = false
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "androidx.startup")
    }
    testImplementation("junit:junit:4.13.2")
    // Exercise the real JSON payload, not android.jar's no-op unit-test stubs.
    testImplementation("org.json:json:20240303")
}
