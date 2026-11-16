package com.willcraig.requesthandoff

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.willcraig.requesthandoff.core.ExportOptions
import java.nio.file.Files
import kotlin.io.path.readText

class ExportActionTest : BasePlatformTestCase() {
    private val action = ExportAction()

    fun testProjectViewEnablesHttpAndRestFilesOnly() {
        val http = myFixture.addFileToProject("api.http", "GET https://example.com/health\n")
        val rest = myFixture.addFileToProject("ping.rest", "GET https://example.com/ping\n")
        val text = myFixture.addFileToProject("notes.txt", "hello")
        val folder = http.virtualFile.parent

        assertTrue(presentation(ActionPlaces.PROJECT_VIEW_POPUP, arrayOf(http.virtualFile)).isEnabledAndVisible)
        assertTrue(presentation(ActionPlaces.PROJECT_VIEW_POPUP, arrayOf(rest.virtualFile)).isEnabledAndVisible)
        assertTrue(presentation(ActionPlaces.PROJECT_VIEW_POPUP, arrayOf(folder)).isEnabledAndVisible)
        assertFalse(presentation(ActionPlaces.PROJECT_VIEW_POPUP, arrayOf(text.virtualFile)).isEnabledAndVisible)
        assertTrue(presentation(ActionPlaces.EDITOR_POPUP, file = http.virtualFile).isEnabledAndVisible)
        assertFalse(presentation(ActionPlaces.EDITOR_POPUP, file = text.virtualFile).isEnabledAndVisible)
        assertTrue(presentation(ActionPlaces.MAIN_MENU).isEnabledAndVisible)
    }

    fun testEmptySelectionWarnsWithoutWriting() {
        val text = myFixture.addFileToProject("notes.txt", "hello")
        val notifications = captureNotifications()
        action.actionPerformed(event(ActionPlaces.PROJECT_VIEW_POPUP, arrayOf(text.virtualFile)))
        assertEquals(1, notifications.size)
        assertEquals(NotificationType.WARNING, notifications.single().type)
        assertTrue(notifications.single().content.contains(".http"))
    }

    fun testExportWritesCollectionEnvironmentAndReportAndNotifiesCounts() {
        val http = myFixture.addFileToProject(
            "api/items.http",
            """
            @host = https://example.com
            ### List
            GET {{host}}/items
            Authorization: Bearer {{token}}

            ### Socket
            WEBSOCKET wss://example.com/live
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "api/http-client.env.json",
            """{"dev":{"host":"https://dev.example","auth":{"user":"ada"}}}""",
        )
        myFixture.addFileToProject(
            "api/http-client.private.env.json",
            """{"dev":{"token":"test-private-token"}}""",
        )
        val outputDir = Files.createTempDirectory("requesthandoff-export")
        val notifications = captureNotifications()
        try {
            val output = action.exportSelection(
                project,
                listOf(http.virtualFile.parent),
                outputDir,
                includePrivate = false,
                options = ExportOptions.fixed(),
            )
            assertNotNull(output)
            output!!
            assertTrue(output.requestsExported >= 1)
            assertEquals(1, output.requestsSkipped)
            assertTrue(output.warningCount >= 1)

            val collection = outputDir.resolve(output.collectionFileName)
            val report = outputDir.resolve(output.reportFileName)
            val environment = outputDir.resolve(output.environments.single().fileName)
            assertEquals(output.collectionJson, collection.readText())
            assertEquals(output.reportMarkdown, report.readText())
            assertEquals(output.environments.single().json, environment.readText())
            assertTrue(collection.readText().contains("https://schema.getpostman.com/json/collection/v2.1.0/collection.json"))
            assertTrue(collection.readText().contains("\"method\": \"GET\""))
            assertTrue(environment.readText().contains("ada"))
            assertFalse(collection.readText().contains("test-private-token"))
            assertFalse(environment.readText().contains("test-private-token"))
            assertFalse(report.readText().contains("test-private-token"))
            assertTrue(environment.readText().contains("\"key\": \"token\""))
            assertTrue(environment.readText().contains("\"value\": \"\""))
            assertTrue(report.readText().contains("Auth variable 'token' is not in the public environment."))
            assertTrue(report.readText().contains("Private environment file was present and was not read."))
            assertTrue(report.readText().contains("WebSocket requests are skipped"))

            action.notifyExported(project, output, report)
            assertEquals(1, notifications.size)
            val notification = notifications.single()
            assertEquals(NotificationType.INFORMATION, notification.type)
            assertEquals("RequestHandoff", notification.title)
            assertTrue(notification.content.contains("Exported ${output.requestsExported} requests."))
            assertTrue(notification.content.contains("Skipped ${output.requestsSkipped}."))
            assertTrue(notification.content.contains("Warnings ${output.warningCount}."))
            assertEquals(1, notification.actions.size)
            assertEquals("Open report", notification.actions.single().templatePresentation.text)
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    fun testPrivateValuesAreWrittenOnlyWhenRequested() {
        val http = myFixture.addFileToProject("solo.http", "GET {{host}}/status\n")
        myFixture.addFileToProject(
            "http-client.private.env.json",
            """{"dev":{"token":"test-private-token"}}""",
        )
        val outputDir = Files.createTempDirectory("requesthandoff-private")
        try {
            val output = action.exportSelection(
                project,
                listOf(http.virtualFile),
                outputDir,
                includePrivate = true,
                options = ExportOptions.fixed(),
            )
            assertNotNull(output)
            val environment = outputDir.resolve(output!!.environments.single().fileName).readText()
            assertTrue(environment.contains("test-private-token"))
            assertTrue(environment.contains("\"type\": \"secret\""))
            assertFalse(output.reportMarkdown.contains("test-private-token"))
        } finally {
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
        files: Array<VirtualFile>? = null,
        file: VirtualFile? = null,
    ) = event(place, files, file).let { actionEvent ->
        action.update(actionEvent)
        actionEvent.presentation
    }

    private fun event(
        place: String,
        files: Array<VirtualFile>? = null,
        file: VirtualFile? = null,
    ): AnActionEvent {
        val builder = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
        if (files != null) builder.add(CommonDataKeys.VIRTUAL_FILE_ARRAY, files)
        if (file != null) builder.add(CommonDataKeys.VIRTUAL_FILE, file)
        return AnActionEvent.createFromDataContext(place, null, builder.build())
    }
}
