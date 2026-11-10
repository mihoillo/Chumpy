plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    application
}
group = "slop.miho.chumpy"
version = "0.1.0"
repositories { mavenCentral() }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") }
kotlin { jvmToolchain(21) }
application { mainClass.set("slop.miho.chumpy.windows.MainKt") }
tasks.jar {
    archiveBaseName.set("Chumpy-Windows")
    manifest { attributes["Main-Class"] = application.mainClass.get() }
}
