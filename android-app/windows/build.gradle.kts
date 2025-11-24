import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.compose") version "1.6.10"
}

group = "com.astral.unwm"
version = "1.6.5"

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.openpnp:opencv:4.10.0-0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "com.astral.unwm.desktop.DesktopAppKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "AstralUNWM"
            packageVersion = version.toString()
            description = "AstralUNWM desktop build"
            modules("java.desktop")
        }
    }
}
