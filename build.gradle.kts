// AGP 9.3 — upgraded to unlock M3 Expressive APIs (MotionScheme, MaterialExpressiveTheme,
// 8-level Shapes, 30-param Typography). It requires Gradle 9.4.1+ and supports
// compileSdk 37. Uses built-in Kotlin (no org.jetbrains.kotlin.android plugin needed).
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.android.test") version "9.3.2" apply false
    id("androidx.baselineprofile") version "1.5.0-rc02" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("androidx.room") version "2.8.4" apply false
    // CAMusic for Linux (:linux) — Kotlin JVM + Compose Desktop. Versions match
    // :app's pins so the shared files compile against identical APIs.
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.12.1" apply false
}