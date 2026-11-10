plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    application
}
group = "slop.miho.chumpy"
version = "0.1.0"
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("slop.miho.chumpy.windows.MainKt") }
tasks.jar {
    archiveBaseName.set("Chumpy-Windows")
    manifest { attributes["Main-Class"] = application.mainClass.get() }
}

tasks.test { useJUnit() }
