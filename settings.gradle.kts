import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "RequestHandoff"

pluginManagement {
    plugins {
        kotlin("jvm") version "2.0.21"
        id("org.jetbrains.intellij.platform") version "2.20.1"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
}

include("core")

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
