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

rootProject.name = "x-core"

include(":app")
include(":core")
include(":domain")
include(":data")
include(":services")
include(":versions")
include(":installer")
include(":runtime")
