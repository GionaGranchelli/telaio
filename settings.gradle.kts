rootProject.name = "telaio"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

include("telaio-core")
include("integrations:jetbrains")
project(":integrations:jetbrains").projectDir = file("integrations/jetbrains")
