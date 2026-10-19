plugins {
    id("com.android.application") version "9.4.0" apply false
    // AGP 9.4.0 ships Kotlin 2.2.10; keep the Compose compiler aligned.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
