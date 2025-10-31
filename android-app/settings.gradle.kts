pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://raw.githubusercontent.com/opencv/opencv/4.x/platforms/android/repository")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://raw.githubusercontent.com/opencv/opencv/4.x/platforms/android/repository")
    }
}

rootProject.name = "AstralUNWM"
include(":app")
