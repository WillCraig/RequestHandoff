import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
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
        // Marketplace ZIP Signer CLI used by signPlugin. Not packaged into the plugin.
        zipSigner()
    }
    // Starter tests are JUnit 5 and run in a host JVM, not inside the light-test IDE.
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
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

    // Defaults in the Gradle plugin are the same env vars. Set them here so a blank
    // value counts as absent: signPlugin then skips, and the check tasks below fail
    // with the secret name instead of handing an empty PEM to the signer.
    signing {
        certificateChain = envIfPresent("CERTIFICATE_CHAIN")
        privateKey = envIfPresent("PRIVATE_KEY")
        password = envIfPresent("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = envIfPresent("JETBRAINS_MARKETPLACE_TOKEN")
            .orElse(envIfPresent("PUBLISH_TOKEN"))
            .orElse(providers.gradleProperty("intellijPlatformPublishingToken").filter { it.isNotBlank() })
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
    systemProperty("requestHandoff.projectDir", rootDir.absolutePath)
}

val marketplaceSigningSecrets = listOf("CERTIFICATE_CHAIN", "PRIVATE_KEY", "PRIVATE_KEY_PASSWORD")

fun envIfPresent(name: String) = providers.environmentVariable(name).filter { it.isNotBlank() }

fun envPresent(name: String) = envIfPresent(name).map { true }.orElse(false)

// signPlugin skips when the key or certificate is absent. These tasks run first and name what is missing.
tasks.register<RequireMarketplaceCredentials>("checkMarketplaceSigning") {
    group = "publishing"
    description = "Fails when CERTIFICATE_CHAIN, PRIVATE_KEY, or PRIVATE_KEY_PASSWORD is absent."
    names.set(marketplaceSigningSecrets)
    marketplaceSigningSecrets.forEach { name -> presence.put(name, envPresent(name)) }
    guidance.set("Generate the certificate with the commands in the README Publish section. Do not commit the key or certificate.")
}

tasks.register<RequireMarketplaceCredentials>("checkMarketplaceToken") {
    group = "publishing"
    description = "Fails when the JetBrains Marketplace token is absent."
    names.set(listOf("JETBRAINS_MARKETPLACE_TOKEN"))
    presence.put(
        "JETBRAINS_MARKETPLACE_TOKEN",
        envPresent("JETBRAINS_MARKETPLACE_TOKEN")
            .zip(envPresent("PUBLISH_TOKEN")) { marketplace, publish -> marketplace || publish }
            .zip(providers.gradleProperty("intellijPlatformPublishingToken").map { it.isNotBlank() }.orElse(false)) { env, property ->
                env || property
            },
    )
    guidance.set("Local alternatives: PUBLISH_TOKEN, or -PintellijPlatformPublishingToken / ORG_GRADLE_PROJECT_intellijPlatformPublishingToken.")
}

tasks.named("signPlugin").configure {
    dependsOn("checkMarketplaceSigning")
}

tasks.named("publishPlugin").configure {
    dependsOn("checkMarketplaceSigning", "checkMarketplaceToken")
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

abstract class RequireMarketplaceCredentials : DefaultTask() {
    @get:Input
    abstract val names: ListProperty<String>

    @get:Input
    abstract val presence: MapProperty<String, Boolean>

    @get:Input
    @get:Optional
    abstract val guidance: Property<String>

    @TaskAction
    fun check() {
        val missing = names.get().filter { name -> presence.get()[name] != true }
        if (missing.isEmpty()) {
            return
        }
        val extra = guidance.orNull?.let { " $it" }.orEmpty()
        throw GradleException(
            "Missing Marketplace credentials: ${missing.joinToString(", ")}. " +
                "Set each name as an environment variable or as a GitHub Actions secret of the same name. " +
                "Values are not printed.$extra See the README Publish section.",
        )
    }
}
