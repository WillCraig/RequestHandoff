package com.willcraig.requesthandoff

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.willcraig.requesthandoff.core.ImportInput
import com.willcraig.requesthandoff.core.ImportOutput
import com.willcraig.requesthandoff.core.PRIVATE_ENVIRONMENT_FILE_NAME
import com.willcraig.requesthandoff.core.PUBLIC_ENVIRONMENT_FILE_NAME
import com.willcraig.requesthandoff.core.importPostmanCollection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText

class ImportAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        if (event.project == null) {
            event.presentation.isEnabledAndVisible = false
            return
        }
        event.presentation.isEnabledAndVisible = when (event.place) {
            ActionPlaces.EDITOR_POPUP -> isJson(event.getData(CommonDataKeys.VIRTUAL_FILE))
            else -> true
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val selected = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()
        val dialog = ImportDialog(
            project,
            suggestedCollection(selected),
            suggestedEnvironment(selected),
            suggestedDirectory(project, selected),
        )
        if (!dialog.showAndGet()) return
        val outputDirectory = Path(dialog.outputDirectory)
        val collectionPath = Path(dialog.collectionPath)
        val environmentPath = dialog.environmentPath.takeIf { it.isNotEmpty() }?.let(::Path)
        PropertiesComponent.getInstance(project).setValue(OUTPUT_DIRECTORY, outputDirectory.toString())

        val holder = arrayOfNulls<ImportOutput>(1)
        var failure: String? = null
        val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    holder[0] = importSelection(outputDirectory, collectionPath, environmentPath)
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (ex: Exception) {
                    failure = ex.message ?: RequestHandoffBundle.message("import.notification.failed")
                }
            },
            RequestHandoffBundle.message("import.progress"),
            true,
            project,
        )
        if (!completed) return
        failure?.let {
            notify(project, it, NotificationType.ERROR, report = null)
            return
        }
        val output = holder[0] ?: return
        notifyImported(project, output, outputDirectory.resolve(output.reportFileName))
    }

    internal fun importSelection(
        outputDirectory: Path,
        collectionPath: Path,
        environmentPath: Path?,
    ): ImportOutput {
        val output = importPostmanCollection(
            ImportInput(
                collectionJson = collectionPath.readText(),
                collectionFileName = collectionPath.fileName.toString(),
                environmentJson = environmentPath?.readText(),
                environmentFileName = environmentPath?.fileName?.toString(),
            ),
        )
        write(outputDirectory, output)
        return output
    }

    internal fun notifyImported(project: Project, output: ImportOutput, report: Path) {
        notify(
            project,
            RequestHandoffBundle.message(
                "import.notification.body",
                output.requestsImported,
                output.requestsSkipped,
                output.warningCount,
            ),
            NotificationType.INFORMATION,
            report,
        )
    }

    private fun notify(
        project: Project,
        @NlsContexts.NotificationContent content: String,
        type: NotificationType,
        report: Path?,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(content, type)
        notification.setTitle(RequestHandoffBundle.message("notification.title"))
        if (report != null) {
            notification.addAction(
                com.intellij.notification.NotificationAction.createSimpleExpiring(
                    RequestHandoffBundle.message("notification.openReport"),
                ) {
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(report) ?: return@createSimpleExpiring
                    FileEditorManager.getInstance(project).openFile(file, true)
                },
            )
        }
        notification.notify(project)
    }

    private fun write(directory: Path, output: ImportOutput) {
        val root = directory.toAbsolutePath().normalize()
        Files.createDirectories(root)
        fun place(relative: String, content: String) {
            val target = root.resolve(relative).normalize()
            if (!target.startsWith(root)) {
                error("Refusing to write outside the output directory.")
            }
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, content)
        }
        output.files.forEach { place(it.relativePath, it.content) }
        output.publicEnvironmentJson?.let { place(PUBLIC_ENVIRONMENT_FILE_NAME, it) }
        output.privateEnvironmentJson?.let { place(PRIVATE_ENVIRONMENT_FILE_NAME, it) }
        place(output.reportFileName, output.reportMarkdown)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)?.refresh(false, true)
    }

    private fun suggestedCollection(selected: List<VirtualFile>): String? =
        selected.singleOrNull { isCollectionName(it.name) && !it.isDirectory }?.path

    private fun suggestedEnvironment(selected: List<VirtualFile>): String? =
        selected.singleOrNull { isEnvironmentName(it.name) && !it.isDirectory }?.path

    private fun suggestedDirectory(project: Project, selected: List<VirtualFile>): String {
        PropertiesComponent.getInstance(project).getValue(OUTPUT_DIRECTORY)?.let { return it }
        val folder = selected.singleOrNull { it.isDirectory }
        if (folder != null) return folder.path
        return selected.firstOrNull()?.parent?.path ?: project.basePath.orEmpty()
    }

    private fun isJson(file: VirtualFile?): Boolean = file?.extension?.equals("json", ignoreCase = true) == true

    private fun isCollectionName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".json") && (lower.contains("collection") || lower.endsWith(".postman_collection.json"))
    }

    private fun isEnvironmentName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".json") && lower.contains("environment")
    }

    companion object {
        private const val OUTPUT_DIRECTORY = "com.willcraig.requesthandoff.importDirectory"
        private const val NOTIFICATION_GROUP = "RequestHandoff.Notifications"
    }
}
