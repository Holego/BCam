import java.util.Properties

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

android {
    namespace = "io.github.holego.bcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.holego.bcam"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
        release {
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
}
