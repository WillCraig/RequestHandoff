package com.willcraig.requesthandoff

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import java.nio.file.Files
import kotlin.io.path.writeText

class ExportDialogTest : BasePlatformTestCase() {
    fun testPrivateValuesDefaultOffAndChosenDirectoryIsAccepted() {
        val chosen = Files.createTempDirectory("requesthandoff-out")
        val dialog = openDialog(suggestedDirectory = null)
        try {
            assertFalse(dialog.includePrivateEnvironments)
            assertEquals("", dialog.outputDirectory)
            assertNotNull(dialog.validation())

            val field = dialog.preferredFocusedComponent as JBTextField
            field.text = "  ${chosen.toAbsolutePath()}  "
            assertEquals(chosen.toAbsolutePath().toString(), dialog.outputDirectory)
            assertNull(dialog.validation())
            assertEquals("Export", dialog.okButtonText())
        } finally {
            chosen.toFile().deleteRecursively()
        }
    }

    fun testMissingDirectoryIsAllowedAndAFileIsRejected() {
        val missing = Files.createTempDirectory("requesthandoff-parent").resolve("new-export")
        val dialog = openDialog(missing.toString())
        assertNull("A directory that does not exist yet can be created by the export.", dialog.validation())

        val file = Files.createTempFile("requesthandoff", ".txt")
        file.writeText("not a directory")
        val rejected = openDialog(file.toString())
        val info = rejected.validation()
        assertNotNull(info)
        assertTrue(info!!.message.contains("file"))
        file.toFile().delete()
        missing.parent.toFile().deleteRecursively()
    }

    private fun openDialog(suggestedDirectory: String?): ProbeDialog {
        val dialog = ProbeDialog(project, suggestedDirectory)
        Disposer.register(testRootDisposable, Disposable { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) })
        return dialog
    }

    private class ProbeDialog(project: com.intellij.openapi.project.Project, suggestedDirectory: String?) :
        ExportDialog(project, suggestedDirectory) {
        fun validation() = doValidate()

        fun okButtonText(): String = okAction.getValue(javax.swing.Action.NAME) as String
    }
}
