@file:Suppress("UnstableApiUsage")

import org.gradle.api.tasks.testing.logging.TestLogEvent

// wireguard-go hardcodes its UAPI socket directory to /data/data/<package>/cache,
// so the native library has to be built for the exact application id.
val appPackage: String = providers.gradleProperty("adhdiumPackageName").get()

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "$appPackage.tunnel"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        externalNativeBuild {
            cmake {
                targets("libwg-go.so")
                arguments("-DGRADLE_USER_HOME=${project.gradle.gradleUserHomeDir}")
                arguments("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                arguments("-DANDROID_PACKAGE_NAME=$appPackage")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("tools/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    testOptions.unitTests.all {
        it.testLogging { events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED) }
    }
    lint {
        disable += "LongLogTag"
        disable += "NewApi"
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.collection)
    compileOnly(libs.jsr305)
    testImplementation(libs.junit)
}
