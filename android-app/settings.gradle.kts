pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://raw.githubusercontent.com/opencv/opencv/4.x/platforms/android/repository")
        maven("https://github.com/QuickBirdEng/opencv-android/raw/main") {
            content {
                includeGroup("org.opencv")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://raw.githubusercontent.com/opencv/opencv/4.x/platforms/android/repository")
        maven("https://github.com/QuickBirdEng/opencv-android/raw/main") {
            content {
                includeGroup("org.opencv")
            }
        }
    }
}

rootProject.name = "AstralUNWM"
include(":app")
