import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.VerificationReportsFormats
import java.time.Duration

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + configurations.named("intellijPlatformClasspath").get()
    runtimeClasspath += sourceSets.main.get().output + configurations.named("intellijPlatformClasspath").get()
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.named("testImplementation").get())
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.named("testRuntimeOnly").get())
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        intellijIdeaCommunity("2024.3.7.1")
        testFramework(TestFrameworkType.Platform)
        // Starter pulls the IDE Starter and Driver jars that match this platform build.
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
    }
    // Starter tests are JUnit 5 and run in a host JVM, not inside the light-test IDE.
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// stable (default) fails the build. eap is a separate invocation so EAP problems
// can be reported without failing the required PR check. current is the sinceBuild
// IDE only, for a short local check.
val verifierChannel = providers.gradleProperty("requestHandoff.verifierChannel").orElse("stable")

intellijPlatform {
    pluginConfiguration {
        name = "RequestHandoff"
        version = providers.gradleProperty("version")
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // recommended() would also queue every minor release, RC, and EAP since 243
        // for the single current product, and those EAP results would fail verifyPlugin.
        // latest() uses the same product-release filter as select() and keeps the newest
        // build of each product. current() is the sinceBuild baseline (IC 2024.3).
        ides {
            when (verifierChannel.get()) {
                "eap" -> latest {
                    types.set(
                        listOf(
                            IntelliJPlatformType.IntellijIdeaCommunity,
                            IntelliJPlatformType.IntellijIdeaUltimate,
                        ),
                    )
                    channels.set(listOf(ProductRelease.Channel.EAP))
                    sinceBuild.set("243")
                }
                "current" -> current()
                else -> {
                    current()
                    latest {
                        types.set(
                            listOf(
                                IntelliJPlatformType.IntellijIdeaCommunity,
                                IntelliJPlatformType.IntellijIdeaUltimate,
                                IntelliJPlatformType.PyCharm,
                                IntelliJPlatformType.WebStorm,
                            ),
                        )
                        channels.set(listOf(ProductRelease.Channel.RELEASE))
                        sinceBuild.set("243")
                    }
                }
            }
        }
        verificationReportsFormats.set(
            listOf(
                VerificationReportsFormats.PLAIN,
                VerificationReportsFormats.HTML,
                VerificationReportsFormats.MARKDOWN,
            ),
        )
    }
}

tasks.named<Test>("test") {
    useJUnit()
}

intellijPlatformTesting {
    testIdeUi.register("integrationTest") {
        task {
            testClassesDirs = integrationTestSourceSet.output.classesDirs
            classpath = files(
                integrationTestSourceSet.runtimeClasspath,
                configurations.named("intellijPlatformClasspath"),
            )
            useJUnitPlatform()
            jvmArgs("-Xmx2g")
            timeout.set(Duration.ofMinutes(25))
            archiveFile.convention(tasks.named<org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask>("buildPlugin").flatMap { it.archiveFile })
        }
    }
}

tasks.matching { it.name == "integrationTest" }.configureEach {
    group = "verification"
    description = "Boots a real IDE with the built plugin and exports a sample project."
}
