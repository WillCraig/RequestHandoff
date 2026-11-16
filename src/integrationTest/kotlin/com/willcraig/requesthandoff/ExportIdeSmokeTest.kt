package com.willcraig.requesthandoff

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.dialog
import com.intellij.driver.sdk.ui.components.ideFrame
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Calls [javax.swing.AbstractButton.doClick] in the IDE process. A real mouse click does not land when Robot has no display. */
@Remote("javax.swing.AbstractButton")
interface AbstractButtonRef {
    fun doClick()
}

class ExportIdeSmokeTest {
    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun bootsCommunityIdeAndExportsSampleProject() {
        val projectDir = Files.createTempDirectory("requesthandoff-ide")
        Files.writeString(
            projectDir.resolve("sample.http"),
            "GET https://example.com/health\nAccept: text/plain\n",
        )
        val version = System.getProperty("ide.test.version")
        val context = Starter.newContext(
            testName = "exportSmoke",
            TestCase(IdeProductProvider.IC, LocalProjectInfo(projectDir)).withVersion(version),
        ).apply {
            PluginConfigurator(this).installPluginFromPath(Path(System.getProperty("path.to.build.plugin")))
            applyVMOptionsPatch {
                addSystemProperty("idea.trust.all.projects", "true")
                addSystemProperty("ide.show.tips.on.startup.default.value", "false")
                // A headless session disables third-party plugins unless this is set.
                // Xvfb reports a display, so the flag is unused there and harmless.
                addSystemProperty("idea.qodana.thirdpartyplugins.accept", "true")
                addSystemProperty("jb.consents.confirmation.enabled", "false")
            }
        }
        val collection = projectDir.resolve("sample.postman_collection.json")
        val report = projectDir.resolve("requestHandoff-report.md")
        var driverFailure: Throwable? = null
        try {
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(3.minutes)
                openFile("sample.http")
                val frame = ui.ideFrame()
                frame.toFront()
                // now = true blocks inside the modal dialog. Schedule the action from the
                // IDE frame so the data context has a project, then confirm the dialog.
                // A positional string to dialog() is an XPath; the title attribute is not
                // set on DialogWrapper in this IDE build, so match MyDialog.
                invokeAction(
                    "com.willcraig.requesthandoff.Export",
                    now = false,
                    component = frame.component,
                )
                ui.dialog(xpath = "//div[@class='MyDialog']") {
                    // Export is the OK button. DialogWrapper does not expose @title here.
                    // pressButton uses java.awt.Robot, which does not deliver clicks in this
                    // session. doClick runs the button listener inside the IDE instead.
                    val exportButton = x("//div[@class='JButton' and @visible_text='Export']")
                    driver.withContext(OnDispatcher.EDT) {
                        cast(exportButton.component, AbstractButtonRef::class).doClick()
                    }
                }
                val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
                while (!collection.exists() && System.nanoTime() < deadline) {
                    Thread.sleep(500)
                }
                waitForIndicators(1.minutes)
            }
        } catch (failure: Throwable) {
            driverFailure = failure
        }
        try {
            driverFailure?.let { throw it }
            assertTrue(collection.exists(), "Expected $collection after the IDE export. Project left at $projectDir")
            val json = collection.readText()
            assertTrue(json.contains("https://schema.getpostman.com/json/collection/v2.1.0/collection.json"))
            assertTrue(json.contains("\"method\": \"GET\""))
            assertTrue(json.contains("https://example.com/health"))
            assertTrue(report.exists(), "Expected $report")
            assertTrue(report.readText().contains("Requests exported: 1"))
        } finally {
            if (driverFailure == null && collection.exists()) {
                projectDir.toFile().deleteRecursively()
            }
        }
    }
}
