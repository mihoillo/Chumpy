plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    application
}
group = "slop.miho.chumpy"
version = "0.1.0"
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
application { mainClass.set("slop.miho.chumpy.windows.MainKt") }
tasks.jar {
    archiveBaseName.set("Chumpy-Windows")
    manifest { attributes["Main-Class"] = application.mainClass.get() }
}
