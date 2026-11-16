package com.willcraig.requesthandoff

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
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

class ExportDialog(
    private val project: Project,
    suggestedDirectory: String?,
) : DialogWrapper(project) {
    private val directoryField = JBTextField()
    private val privateValues = JBCheckBox(RequestHandoffBundle.message("export.dialog.private"))

    val outputDirectory: String get() = directoryField.text.trim()
    val includePrivateEnvironments: Boolean get() = privateValues.isSelected

    init {
        title = RequestHandoffBundle.message("export.dialog.title")
        setOKButtonText(RequestHandoffBundle.message("export.dialog.ok"))
        directoryField.text = suggestedDirectory.orEmpty()
        privateValues.isSelected = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val fieldConstraints = constraints(0, 0, 1.0)
        panel.add(JBLabel(RequestHandoffBundle.message("export.dialog.directory")), fieldConstraints)
        directoryField.emptyText.text = ""
        val input = constraints(1, 0, 1.0).apply { gridwidth = 1 }
        panel.add(directoryField, input)
        val browse = JButton(RequestHandoffBundle.message("export.dialog.browse"))
        browse.addActionListener { chooseDirectory() }
        panel.add(browse, constraints(2, 0, 0.0))
        val comment = JBLabel(RequestHandoffBundle.message("export.dialog.private.comment"))
        comment.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        panel.add(privateValues, constraints(0, 1, 1.0).apply { gridwidth = 3 })
        panel.add(comment, constraints(0, 2, 1.0).apply { gridwidth = 3 })
        panel.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(120))
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = directoryField

    override fun doValidate(): ValidationInfo? {
        val path = outputDirectory
        if (path.isEmpty()) {
            return ValidationInfo(RequestHandoffBundle.message("export.dialog.directory.empty"), directoryField)
        }
        val file = File(path)
        if (file.exists() && !file.isDirectory) {
            return ValidationInfo(RequestHandoffBundle.message("export.dialog.directory.file"), directoryField)
        }
        return null
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
