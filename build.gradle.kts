// Root build file. Plugin versions are declared here once and applied in :app.
//
// AGP 8.13 is the last 8.x line: it supports compileSdk 36 (which Google Play requires
// new releases to target from 31 Aug 2026) while keeping the classic Kotlin plugin setup.
// Kotlin 2.2 is needed because the Google Mobile Ads SDK 24.1+ ships Kotlin 2.1 metadata.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}
