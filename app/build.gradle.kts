plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "slop.miho.aurora"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = providers.gradleProperty("auroraPackageName").get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    // One APK per phone architecture: the Xray core's native library is the app's whole
    // weight, and carrying both 64- and 32-bit copies doubles it for every device that
    // installs the wrong one. The builds are sideloaded, so the split is per file.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
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
    }

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

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
