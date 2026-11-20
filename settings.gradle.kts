pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MobileChat"

include(":domain")
include(":application")
include(":adapter-persistence")
include(":adapter-network")
include(":adapter-platform")
include(":ui")
include(":app")
