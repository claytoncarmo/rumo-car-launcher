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
rootProject.name = "Rumo"
include(":launcher")
// App do rádio online (só o player): à parte do launcher, ver android/webradio/build.gradle.kts
include(":webradio")
