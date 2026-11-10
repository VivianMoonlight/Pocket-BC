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

rootProject.name = "mobile-bc"

include(":app")

include(":core:model")
include(":core:protocol")
include(":core:socket")
include(":core:ui")

include(":data:storage")

include(":domain:messaging")
include(":domain:sync")
include(":domain:bc-text")
include(":domain:session-core")
include(":domain:session")
include(":domain:character")

include(":feature:auth")
include(":feature:chat")
include(":feature:rooms")
include(":feature:profile")
