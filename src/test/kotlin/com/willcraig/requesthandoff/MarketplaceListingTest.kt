package com.willcraig.requesthandoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class MarketplaceListingTest {
    private val root: Path = Path(
        System.getProperty("requestHandoff.projectDir")
            ?: error("requestHandoff.projectDir is not set"),
    )

    @Test
    fun pluginDescriptorKeepsOpenCompatibilityAndDoesNotDependOnHttpClient() {
        val xml = read("src/main/resources/META-INF/plugin.xml")
        assertTrue(xml.contains("""<idea-version since-build="243"/>"""))
        assertFalse(Regex("""until-build\s*=""").containsMatchIn(xml))
        assertEquals(
            listOf("com.intellij.modules.platform"),
            Regex("<depends>([^<]+)</depends>").findAll(xml).map { it.groupValues[1] }.toList(),
        )
        assertTrue(xml.contains("Postman is a trademark of Postman, Inc."))
        assertTrue(xml.contains("JetBrains and IntelliJ are trademarks of JetBrains s.r.o."))
        assertFalse(xml.contains("Add change notes here"))
        assertFalse(xml.contains("com.jetbrains.plugins"))
    }

    @Test
    fun pluginIconsAreFortyPixelSvg() {
        val light = read("src/main/resources/META-INF/pluginIcon.svg")
        val dark = read("src/main/resources/META-INF/pluginIcon_dark.svg")
        listOf(light, dark).forEach { svg ->
            assertTrue(svg.contains("""width="40""""))
            assertTrue(svg.contains("""height="40""""))
            assertTrue(svg.contains("""viewBox="0 0 40 40""""))
            assertTrue(svg.trimStart().startsWith("<svg"))
            assertFalse(svg.contains("<image"))
            assertFalse(svg.contains("<script"))
        }
        assertTrue(light != dark)
    }

    @Test
    fun publishWorkflowNamesSecretsAndDoesNotRunOnPullRequests() {
        val workflow = read(".github/workflows/publish.yml")
        assertTrue(workflow.contains("runs-on: ubuntu-latest"))
        assertTrue(workflow.contains("workflow_dispatch:"))
        assertTrue(workflow.contains("tags:"))
        assertFalse(workflow.contains("pull_request:"))
        assertFalse(workflow.contains("macos-"))
        listOf(
            "JETBRAINS_MARKETPLACE_TOKEN",
            "CERTIFICATE_CHAIN",
            "PRIVATE_KEY",
            "PRIVATE_KEY_PASSWORD",
        ).forEach { secret ->
            assertTrue(workflow.contains(secret))
        }
        assertFalse(workflow.contains("echo \"\${CERTIFICATE_CHAIN"))
        assertFalse(workflow.contains("echo \"\${PRIVATE_KEY"))
        assertFalse(workflow.contains("echo \"\${JETBRAINS_MARKETPLACE_TOKEN"))
        assertFalse(workflow.contains("set -x"))
    }

    @Test
    fun gradleWiringFailsClosedWithoutPrintingSecrets() {
        val build = read("build.gradle.kts")
        assertTrue(build.contains("zipSigner()"))
        assertTrue(build.contains("checkMarketplaceSigning"))
        assertTrue(build.contains("checkMarketplaceToken"))
        assertTrue(build.contains("""sinceBuild = "243""""))
        assertTrue(build.contains("untilBuild = provider { null }"))
        assertTrue(build.contains("Values are not printed."))
        assertTrue(build.contains("envIfPresent(\"JETBRAINS_MARKETPLACE_TOKEN\")"))
        assertTrue(build.contains("envIfPresent(\"CERTIFICATE_CHAIN\")"))
        assertTrue(build.contains("envIfPresent(\"PRIVATE_KEY\")"))
        assertTrue(build.contains("envIfPresent(\"PRIVATE_KEY_PASSWORD\")"))
    }

    @Test
    fun readmeAndListingCopyCoverTheStepsWillStillDoes() {
        val readme = read("README.md")
        val listing = read("docs/marketplace/listing.md")
        assertTrue(readme.contains("## Publish"))
        assertTrue(readme.contains("JETBRAINS_MARKETPLACE_TOKEN"))
        assertTrue(readme.contains("CERTIFICATE_CHAIN"))
        assertTrue(readme.contains("PRIVATE_KEY_PASSWORD"))
        assertTrue(readme.contains("Postman is a trademark of Postman, Inc."))
        assertTrue(listing.contains("since-build"))
        assertTrue(listing.contains("1200×760"))
        assertTrue(listing.contains("pluginIcon.svg"))
        assertTrue(listing.contains("Postman is a trademark of Postman, Inc."))
    }

    private fun read(path: String): String {
        val file = root.resolve(path)
        assertTrue("$path is missing", file.exists())
        return file.readText()
    }
}
