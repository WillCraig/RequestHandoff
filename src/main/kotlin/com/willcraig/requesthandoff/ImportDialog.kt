package com.willcraig.requesthandoff

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

open class ImportDialog(
    private val project: Project,
    suggestedCollection: String?,
    suggestedEnvironment: String?,
    suggestedDirectory: String?,
) : DialogWrapper(project) {
    protected val collectionField = JBTextField()
    protected val environmentField = JBTextField()
    protected val directoryField = JBTextField()

    val collectionPath: String get() = collectionField.text.trim()
    val environmentPath: String get() = environmentField.text.trim()
    val outputDirectory: String get() = directoryField.text.trim()

    init {
        title = RequestHandoffBundle.message("import.dialog.title")
        setOKButtonText(RequestHandoffBundle.message("import.dialog.ok"))
        collectionField.text = suggestedCollection.orEmpty()
        environmentField.text = suggestedEnvironment.orEmpty()
        directoryField.text = suggestedDirectory.orEmpty()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        addRow(panel, 0, "import.dialog.collection", collectionField) { chooseFile(collectionField) }
        addRow(panel, 1, "import.dialog.environment", environmentField) { chooseFile(environmentField) }
        addRow(panel, 2, "import.dialog.directory", directoryField) { chooseDirectory() }
        val comment = JBLabel(RequestHandoffBundle.message("import.dialog.secret.comment"))
        comment.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        panel.add(comment, constraints(0, 3, 1.0).apply { gridwidth = 3 })
        panel.preferredSize = Dimension(JBUI.scale(640), JBUI.scale(160))
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = collectionField

    override fun doValidate(): ValidationInfo? {
        val collection = collectionPath
        if (collection.isEmpty()) {
            return ValidationInfo(RequestHandoffBundle.message("import.dialog.collection.empty"), collectionField)
        }
        val collectionFile = File(collection)
        if (!collectionFile.isFile) {
            return ValidationInfo(RequestHandoffBundle.message("import.dialog.collection.missing"), collectionField)
        }
        if (environmentPath.isNotEmpty() && !File(environmentPath).isFile) {
            return ValidationInfo(RequestHandoffBundle.message("import.dialog.environment.missing"), environmentField)
        }
        val directory = outputDirectory
        if (directory.isEmpty()) {
            return ValidationInfo(RequestHandoffBundle.message("import.dialog.directory.empty"), directoryField)
        }
        val directoryFile = File(directory)
        if (directoryFile.exists() && !directoryFile.isDirectory) {
            return ValidationInfo(RequestHandoffBundle.message("import.dialog.directory.file"), directoryField)
        }
        return null
    }

    private fun addRow(panel: JPanel, row: Int, labelKey: String, field: JBTextField, browse: () -> Unit) {
        panel.add(JBLabel(RequestHandoffBundle.message(labelKey)), constraints(0, row, 0.0))
        panel.add(field, constraints(1, row, 1.0))
        val button = JButton(RequestHandoffBundle.message("import.dialog.browse"))
        button.addActionListener { browse() }
        panel.add(button, constraints(2, row, 0.0))
    }

    private fun chooseFile(field: JBTextField) {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val current = LocalFileSystem.getInstance().findFileByPath(field.text.trim()) ?: base
        val chosen = FileChooser.chooseFile(descriptor, project, current) ?: return
        field.text = chosen.path
    }

    private fun chooseDirectory() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val current = LocalFileSystem.getInstance().findFileByPath(outputDirectory) ?: base
        val chosen = FileChooser.chooseFile(descriptor, project, current) ?: return
        directoryField.text = chosen.path
    }

    private fun constraints(x: Int, y: Int, weightX: Double): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x
            gridy = y
            this.weightx = weightX
            fill = if (weightX > 0) GridBagConstraints.HORIZONTAL else GridBagConstraints.NONE
            insets = JBUI.insets(4)
            anchor = GridBagConstraints.WEST
        }
}
