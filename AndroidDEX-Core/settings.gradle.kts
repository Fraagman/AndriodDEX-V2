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

rootProject.name = "AndroidDEX"

include(":androiddex-core")
include(":androiddex-video")
include(":androiddex-network")
include(":androiddex-input")
include(":androiddex-audio")
include(":androiddex-security")
include(":androiddex-discovery")
include(":androiddex-session")
include(":androiddex-diagnostics")
