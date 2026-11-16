package com.willcraig.requesthandoff

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.willcraig.requesthandoff.core.ExportInput
import com.willcraig.requesthandoff.core.ExportOptions
import com.willcraig.requesthandoff.core.ExportOutput
import com.willcraig.requesthandoff.core.HttpFileInput
import com.willcraig.requesthandoff.core.exportHttpFiles
import com.willcraig.requesthandoff.core.httpScriptMayBeInlined
import com.willcraig.requesthandoff.core.referencedHttpScripts
import com.willcraig.requesthandoff.core.resolveHttpScriptPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

class ExportAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        if (event.project == null) {
            event.presentation.isEnabledAndVisible = false
            return
        }
        event.presentation.isEnabledAndVisible = when (event.place) {
            ActionPlaces.PROJECT_VIEW_POPUP -> selectable(event).isNotEmpty()
            ActionPlaces.EDITOR_POPUP -> {
                val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
                file != null && isHttp(file)
            }
            else -> true
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val targets = resolve(event)
        if (targets.isEmpty()) {
            notify(project, RequestHandoffBundle.message("export.none"), NotificationType.WARNING, report = null)
            return
        }
        val dialog = ExportDialog(project, suggestedDirectory(project, targets))
        if (!dialog.showAndGet()) return
        val outputDirectory = Path(dialog.outputDirectory)
        val includePrivate = dialog.includePrivateEnvironments
        PropertiesComponent.getInstance(project).setValue(OUTPUT_DIRECTORY, outputDirectory.toString())

        val holder = arrayOfNulls<ExportOutput>(1)
        var failure: String? = null
        val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    holder[0] = exportSelection(
                        project,
                        targets,
                        outputDirectory,
                        includePrivate,
                        ExportOptions.random(),
                    )
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (ex: Exception) {
                    failure = ex.message ?: RequestHandoffBundle.message("notification.failed")
                }
            },
            RequestHandoffBundle.message("export.progress"),
            true,
            project,
        )
        if (!completed) return
        failure?.let {
            notify(project, it, NotificationType.ERROR, report = null)
            return
        }
        val output = holder[0]
        if (output == null) {
            notify(project, RequestHandoffBundle.message("export.noFiles"), NotificationType.WARNING, report = null)
            return
        }
        notifyExported(project, output, outputDirectory.resolve(output.reportFileName))
    }

    internal fun exportSelection(
        project: Project,
        targets: List<VirtualFile>,
        outputDirectory: Path,
        includePrivate: Boolean,
        options: ExportOptions,
    ): ExportOutput? {
        val exportInput = ReadAction.compute<ExportInput, RuntimeException> {
            collect(project, targets, includePrivate)
        }
        if (exportInput.files.isEmpty()) return null
        val output = exportHttpFiles(exportInput, options)
        write(outputDirectory, output)
        return output
    }

    internal fun notifyExported(project: Project, output: ExportOutput, report: Path) {
        notify(
            project,
            RequestHandoffBundle.message(
                "notification.body",
                output.requestsExported,
                output.requestsSkipped,
                output.warningCount,
            ),
            NotificationType.INFORMATION,
            report,
        )
    }

    private fun resolve(event: AnActionEvent): List<VirtualFile> {
        val selected = selectable(event)
        if (selected.isNotEmpty()) return selected
        val project = event.project ?: return emptyList()
        val editorFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        return if (editorFile != null && isHttp(editorFile)) listOf(editorFile) else emptyList()
    }

    private fun selectable(event: AnActionEvent): List<VirtualFile> {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()
        if (files.size == 1) {
            val file = files.first()
            if (file.isDirectory || isHttp(file)) return listOf(file)
        }
        if (files.size > 1 && files.all(::isHttp)) return files
        return emptyList()
    }

    private fun collect(project: Project, targets: List<VirtualFile>, includePrivate: Boolean): ExportInput {
        val singleFile = targets.size == 1 && !targets.first().isDirectory
        val root = if (singleFile) targets.first() else commonParent(targets) ?: targets.first()
        val httpFiles = linkedMapOf<String, VirtualFile>()
        for (target in targets) {
            if (target.isDirectory) {
                VfsUtilCore.iterateChildrenRecursively(target, SKIP_VCS) { file ->
                    if (!file.isDirectory && isHttp(file)) {
                        val relative = relativePath(target, file)
                        httpFiles.putIfAbsent(relative, file)
                    }
                    true
                }
            } else if (isHttp(target)) {
                val relative = if (singleFile) target.name else relativePath(root, target)
                httpFiles.putIfAbsent(relative, target)
            }
        }
        val projectBase = projectDir(project)
        val scripts = linkedMapOf<String, String>()
        val inputs = httpFiles.map { (relative, file) ->
            val text = try {
                VfsUtilCore.loadText(file)
            } catch (ex: java.io.IOException) {
                return@map HttpFileInput(relative, null, ex.message ?: "Could not read the file.")
            }
            loadScriptFiles(project, file, relative, text, scripts)
            HttpFileInput(relative, text)
        }
        val start = if (targets.size == 1 && targets.first().isDirectory) targets.first() else targets.first().parent
        val (publicFile, privateFile) = findEnvironmentFiles(start, projectBase)
        val collectionName = when {
            singleFile -> targets.first().nameWithoutExtension
            targets.size == 1 && targets.first().isDirectory -> targets.first().name
            else -> root.name.ifBlank { "requests" }
        }
        return ExportInput(
            collectionName = collectionName,
            files = inputs,
            singleFile = singleFile,
            publicEnvironmentJson = publicFile?.let { VfsUtilCore.loadText(it) },
            privateEnvironmentJson = if (includePrivate) privateFile?.let { VfsUtilCore.loadText(it) } else null,
            privateEnvironmentOmitted = !includePrivate && privateFile != null,
            scriptFiles = scripts,
        )
    }

    private fun loadScriptFiles(
        project: Project,
        httpFile: VirtualFile,
        relativePath: String,
        text: String,
        scripts: MutableMap<String, String>,
    ) {
        val parent = httpFile.parent ?: return
        for (reference in referencedHttpScripts(text)) {
            if (!httpScriptMayBeInlined(reference)) continue
            val key = resolveHttpScriptPath(relativePath, reference)
            if (key in scripts) continue
            val child = findRelativeFile(parent, reference) ?: continue
            if (child.isDirectory || !isInsideProject(project, child)) continue
            try {
                scripts[key] = VfsUtilCore.loadText(child)
            } catch (_: java.io.IOException) {
                // The exporter reports a script that was not inlined.
            }
        }
    }

    private fun suggestedDirectory(project: Project, targets: List<VirtualFile>): String {
        PropertiesComponent.getInstance(project).getValue(OUTPUT_DIRECTORY)?.let { return it }
        val parent = targets.first().parent ?: projectDir(project)
        return parent?.path ?: ""
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

    private fun write(directory: Path, output: ExportOutput) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(output.collectionFileName), output.collectionJson)
        for (environment in output.environments) {
            Files.writeString(directory.resolve(environment.fileName), environment.json)
        }
        Files.writeString(directory.resolve(output.reportFileName), output.reportMarkdown)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)?.refresh(false, true)
    }

    companion object {
        private const val OUTPUT_DIRECTORY = "com.willcraig.requesthandoff.outputDirectory"
        private const val NOTIFICATION_GROUP = "RequestHandoff.Notifications"
        private val SKIP_VCS = VirtualFileFilter { file -> file.name != ".git" }
    }
}

private fun isInsideProject(project: Project, file: VirtualFile): Boolean {
    val roots = ProjectRootManager.getInstance(project).contentRoots
    if (roots.any { VfsUtilCore.isAncestor(it, file, false) }) return true
    val base = projectDir(project) ?: return false
    return VfsUtilCore.isAncestor(base, file, false)
}

private fun findRelativeFile(start: VirtualFile, reference: String): VirtualFile? {
    var current = start
    for (segment in reference.trim().replace('\\', '/').split('/')) {
        current = when (segment) {
            "", "." -> current
            ".." -> current.parent ?: return null
            else -> current.findChild(segment) ?: return null
        }
    }
    return current
}

private fun isHttp(file: VirtualFile): Boolean {
    val extension = file.extension?.lowercase() ?: return false
    return extension == "http" || extension == "rest"
}

private fun relativePath(root: VirtualFile, file: VirtualFile): String {
    if (root == file) return file.name
    return (VfsUtilCore.getRelativePath(file, root) ?: file.name).replace('\\', '/')
}

private fun commonParent(files: List<VirtualFile>): VirtualFile? {
    var parent = files.first().parent
    while (true) {
        val current = parent ?: return null
        if (files.all { VfsUtilCore.isAncestor(current, it, false) }) return current
        parent = current.parent
    }
}

private fun projectDir(project: Project): VirtualFile? {
    val path = project.basePath ?: return null
    return LocalFileSystem.getInstance().findFileByPath(path)
}

private fun findEnvironmentFiles(start: VirtualFile?, base: VirtualFile?): Pair<VirtualFile?, VirtualFile?> {
    var directory = start
    var publicFile: VirtualFile? = null
    var privateFile: VirtualFile? = null
    while (directory != null) {
        if (publicFile == null) publicFile = directory.findChild("http-client.env.json")
        if (privateFile == null) privateFile = directory.findChild("http-client.private.env.json")
        if (base == null || directory == base) break
        if (!VfsUtilCore.isAncestor(base, directory, true)) break
        directory = directory.parent
    }
    return publicFile to privateFile
}
