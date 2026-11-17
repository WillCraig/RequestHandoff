package com.willcraig.requesthandoff

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ImportActionTest : BasePlatformTestCase() {
    private val action = ImportAction()

    fun testToolsMenuAndProjectViewEnableImport() {
        assertTrue(presentation(ActionPlaces.MAIN_MENU).isEnabledAndVisible)
        assertTrue(presentation(ActionPlaces.PROJECT_VIEW_POPUP).isEnabledAndVisible)
        val json = myFixture.addFileToProject("api.postman_collection.json", "{}")
        val text = myFixture.addFileToProject("notes.txt", "hello")
        assertTrue(presentation(ActionPlaces.EDITOR_POPUP, file = json.virtualFile).isEnabledAndVisible)
        assertFalse(presentation(ActionPlaces.EDITOR_POPUP, file = text.virtualFile).isEnabledAndVisible)
    }

    fun testImportWritesHttpEnvironmentsAndReportWithoutLeakingSecrets() {
        val directory = Files.createTempDirectory("requesthandoff-import-src")
        val outputDir = Files.createTempDirectory("requesthandoff-import-out")
        val collection = directory.resolve("sample.postman_collection.json")
        collection.writeText(
            """
            {
              "info": {
                "name": "sample",
                "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
              },
              "item": [
                {
                  "name": "Health",
                  "request": {"method": "GET", "url": "https://example.com/health", "header": [{"key": "Accept", "value": "text/plain"}]}
                }
              ]
            }
            """.trimIndent(),
        )
        val environment = directory.resolve("dev.postman_environment.json")
        environment.writeText(
            """
            {
              "name": "dev",
              "values": [
                {"key": "host", "value": "https://dev.example", "type": "default"},
                {"key": "token", "value": "test-private-token", "type": "secret"}
              ]
            }
            """.trimIndent(),
        )
        val notifications = captureNotifications()
        try {
            val output = action.importSelection(outputDir, collection, environment)
            assertEquals(1, output.requestsImported)
            val http = outputDir.resolve("sample.http").readText()
            val report = outputDir.resolve(output.reportFileName).readText()
            val publicEnv = outputDir.resolve("http-client.env.json").readText()
            val privateEnv = outputDir.resolve("http-client.private.env.json").readText()
            assertTrue(http.contains("GET https://example.com/health"))
            assertTrue(http.contains("Accept: text/plain"))
            assertTrue(publicEnv.contains("https://dev.example"))
            assertTrue(privateEnv.contains("test-private-token"))
            assertFalse(publicEnv.contains("test-private-token"))
            assertFalse(http.contains("test-private-token"))
            assertFalse(report.contains("test-private-token"))
            assertEquals(output.reportMarkdown, report)

            action.notifyImported(project, output, outputDir.resolve(output.reportFileName))
            assertEquals(1, notifications.size)
            val notification = notifications.single()
            assertEquals(NotificationType.INFORMATION, notification.type)
            assertTrue(notification.content.contains("Imported 1 requests."))
            assertTrue(notification.content.contains("Skipped 0."))
            assertEquals("Open report", notification.actions.single().templatePresentation.text)
        } finally {
            directory.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }

    private fun captureNotifications(): MutableList<Notification> {
        val notifications = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    notifications += notification
                }
            },
        )
        return notifications
    }

    private fun presentation(
        place: String,
        file: com.intellij.openapi.vfs.VirtualFile? = null,
    ) = event(place, file).let { actionEvent ->
        action.update(actionEvent)
        actionEvent.presentation
    }

    private fun event(place: String, file: com.intellij.openapi.vfs.VirtualFile?): AnActionEvent {
        val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        if (file != null) builder.add(CommonDataKeys.VIRTUAL_FILE, file)
        return AnActionEvent.createFromDataContext(place, null, builder.build())
    }
}
