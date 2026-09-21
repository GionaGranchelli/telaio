plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("org.jetbrains.intellij.platform") version "2.2.1" apply false
}

allprojects {
    group = "dev.telaio"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
