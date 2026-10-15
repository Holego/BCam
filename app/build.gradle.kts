import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * Release signing.
 *
 * Key material is read from local.properties (git-ignored) or from the environment, so
 * nothing secret ever enters the repository. When no keystore is configured the release
 * build is simply left unsigned rather than failing, which keeps `assembleRelease`
 * working for F-Droid and for anyone who just clones the repo.
 *
 * To sign locally, add to local.properties:
 *   RELEASE_STORE_FILE=C:/path/outside/the/repo/bcam-release.jks
 *   RELEASE_STORE_PASSWORD=...
 *   RELEASE_KEY_ALIAS=bcam
 *   RELEASE_KEY_PASSWORD=...
 */
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingProp(name: String): String? =
    (localProperties.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

val hasReleaseKeystore = signingProp("RELEASE_STORE_FILE") != null

/*
 * AdMob application ids. Debug builds use Google's public sample id so that developing
 * and testing never produces real ad traffic - clicking your own live ads is a policy
 * violation that can get an AdMob account suspended. The live id belongs to the app
 * registered in AdMob; ad *unit* ids live in AdUnits.kt (play flavor).
 */
val admobAppIdTest = "ca-app-pub-3940256099942544~3347511713"
val admobAppIdLive = "ca-app-pub-6234952902147314~5684803993"

android {
    namespace = "io.github.holego.bcam"
    // Google Play requires new apps and updates to target API 36 from 31 Aug 2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.holego.bcam"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    /*
     * Two distributions of the same app:
     *
     *  foss - F-Droid / GitHub. No ads, no billing, no INTERNET permission, no
     *         proprietary dependency. This is what the privacy statements in the README
     *         and the F-Droid metadata describe.
     *  play - Google Play. Adds Google Mobile Ads (AdMob), the UMP consent form and Play
     *         Billing for the one-time "remove ads" purchase. Everything it adds lives in
     *         src/play; src/main has no reference to any of it.
     *
     * Build: assembleFossRelease (APK)   bundlePlayRelease (AAB for Play Console)
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        create("release") {
            signingProp("RELEASE_STORE_FILE")?.let { path ->
                storeFile = file(path)
                storePassword = signingProp("RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("RELEASE_KEY_ALIAS")
                keyPassword = signingProp("RELEASE_KEY_PASSWORD")
            }
            // v2 covers installation and integrity on Android 7+. v3 is not on by default
            // in AGP but is what makes key rotation possible later, so it is worth having
            // from the very first signed build.
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = admobAppIdTest
        }
        release {
            manifestPlaceholders["admobAppId"] = admobAppIdLive
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // AGP 8 embeds the git commit of the build machine into
            // META-INF/version-control-info.textproto. That single file changes between
            // otherwise identical builds and is enough to break F-Droid's reproducible
            // build verification, so it is left out.
            vcsInfo {
                include = false
            }

            // R8 is left off deliberately: the app is small, and shrinking has not been
            // verified on a device.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // AGP embeds a "Dependency metadata" block into the APK's signing block. It is not a
    // standard part of the APK format and F-Droid's scanner flags it as an unrecognised
    // extra signing block, so it stays out of APKs. In an App Bundle it is the opposite:
    // Play reads it for its SDK/vulnerability reporting, and the bundle is never
    // distributed anywhere else.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    bundle {
        // The app has an in-app language switch (English / Russian). With language splits
        // Play would deliver only the device language, and the other one would silently
        // stop working. The strings are tiny, so ship all of them.
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// CameraX's ProcessCameraProvider.getInstance() returns a Guava ListenableFuture, which the
// app uses directly. The ads and billing libraries drag in
// "listenablefuture:9999.0-empty-to-avoid-conflict-with-guava"; being the higher version it
// wins conflict resolution, and being empty it removes the class from the *compile*
// classpath. Pinning the real 1.0 artifact (just the one interface) there fixes that.
//
// Compile classpaths only: at runtime the play flavor already contains the full Guava, and
// forcing 1.0 there as well would put ListenableFuture on the classpath twice (the duplicate
// class check fails the build). Without the play libraries 1.0 is what resolves anyway, so
// none of this changes the foss build.
configurations.configureEach {
    if (name.endsWith("CompileClasspath", ignoreCase = true)) {
        resolutionStrategy.force("com.google.guava:listenablefuture:1.0")
    }
}

dependencies {
    // CameraX
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")

    // Core / lifecycle. lifecycle-service supplies LifecycleService, which CameraX needs
    // as a LifecycleOwner in order to bind the camera outside of an Activity.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")

    // UI (View system, matching the Theme.Material3.* theme the manifest already declares)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Storage Access Framework helper
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Google Play distribution only. None of this is on the foss classpath.
    "playImplementation"("com.google.android.gms:play-services-ads:25.4.0")
    "playImplementation"("com.google.android.ump:user-messaging-platform:3.2.0")
    "playImplementation"("com.android.billingclient:billing:9.1.0")

    testImplementation("junit:junit:4.13.2")
}
