plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Not applied anywhere (AGP 9 has built-in Kotlin); only pins the Kotlin version
    // so it matches the Compose compiler plugin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
