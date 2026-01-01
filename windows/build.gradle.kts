plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.compose") version "1.6.10"
}

group = "com.astral.unwm"
version = "1.6.5"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3) // Add Material3 specifically
    implementation("org.openpnp:opencv:4.9.0-0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "AstralUNWM"
            packageVersion = "1.6.5"
            description = "AstralUNWM Port for Windows"
            vendor = "AstralExpress"

            windows {
                menuGroup = "AstralUNWM"
                upgradeUuid = "12345678-1234-1234-1234-123456789012"
            }
        }
    }
}
