import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "slop.miho.aurora"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // The build's own number: every invocation that assembles a release APK takes the
    // next one (debug-only and test runs hold it, so a delivery's debug build carries the
    // release's number). The number rides along as versionCode, so every build installs
    // over the one before it. The file at the root carries it: visible in git, safe
    // across cleanups.
    val buildNumberFile = rootProject.file("build_number.properties")
    val releaseAssembled = gradle.startParameter.taskNames.any { name ->
        val task = name.substringAfterLast(':')
        (task.startsWith("assemble", true) || task.startsWith("bundle", true)) &&
            task.contains("Release", true)
    }
    val buildNumber = (buildNumberFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0) +
        if (releaseAssembled) 1 else 0
    if (releaseAssembled) buildNumberFile.writeText(buildNumber.toString())

    // The version's own three numbers, handed out by hand: the global release — a new
    // feature's mark, 0 while the app's whole functionality is still landing — and the
    // minor release, raised on request. The third number is the build itself: the
    // ever-counting one above, starting at 76.
    val majorVersion = 0
    val minorVersion = 2
    // Whoever runs the build signs the version with their own system username.
    val builderName = System.getProperty("user.name") ?: "unknown"

    defaultConfig {
        applicationId = providers.gradleProperty("auroraPackageName").get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = buildNumber
        versionName = "$majorVersion.$minorVersion.$buildNumber-$builderName"
        // The About page's build stamp: when this build was assembled, on the Moscow
        // clock with its offset said out loud. ROOT locale keeps it English, the app's
        // only language.
        val buildDate = ZonedDateTime.now(ZoneId.of("Europe/Moscow"))
            .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ROOT)) +
            " MSK (UTC+3)"
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    // One APK per phone architecture: the Xray core's native library is the app's whole
    // weight, and carrying both 64- and 32-bit copies doubles it for every device that
    // installs the wrong one. The builds are sideloaded, so the split is per file. The
    // debug build runs on the phone in hand — its invocation carries no release task, so
    // it splits into the 64-bit file only; a release invocation splits into both.
    val releaseRequested = gradle.startParameter.taskNames.any { name ->
        name.substringAfterLast(':').contains("Release", ignoreCase = true)
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            if (releaseRequested) include("armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload builds: signed with the local debug key, so a release installs right
            // over a debug install (same id, same signature) and keeps its data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    // The debug build runs on the phone in hand — its 32-bit split is a wasted copy of
    // the core: the splits above hand it the 64-bit file only when its invocation carries
    // no release task (see there).

    // The gomobile runtime is loaded from the APK the classic way, like every Xray client.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        localeFilters += "en"
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    // The Xray core: gomobile AAR of 2dust/AndroidLibXrayLite (a Xray fork with the
    // Hysteria2 outbound), checked in under app/libs. Its consumer rules keep the
    // go.Seq / libv2ray bindings alive through minification.
    implementation(files("libs/libv2ray.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)

    // QR rendering only (com.google.zxing:core is the pure-Java encoder, no camera).
    implementation(libs.zxing.core)
    // The QR scanner's camera: CameraX feeds the frames, zxing (above) decodes them.
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
