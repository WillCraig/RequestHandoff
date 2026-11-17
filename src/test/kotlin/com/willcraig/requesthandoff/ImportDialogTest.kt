package com.willcraig.requesthandoff

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import java.nio.file.Files
import kotlin.io.path.writeText

class ImportDialogTest : BasePlatformTestCase() {
    fun testCollectionIsRequiredAndEnvironmentIsOptional() {
        val directory = Files.createTempDirectory("requesthandoff-import")
        val collection = directory.resolve("api.postman_collection.json")
        collection.writeText("{}")
        val dialog = openDialog()
        try {
            assertEquals("", dialog.collectionPath)
            assertEquals("", dialog.environmentPath)
            assertNotNull(dialog.validation())

            val field = dialog.preferredFocusedComponent as JBTextField
            field.text = "  ${collection.toAbsolutePath()}  "
            dialog.setDirectory("  ${directory.toAbsolutePath()}  ")
            assertEquals(collection.toAbsolutePath().toString(), dialog.collectionPath)
            assertEquals(directory.toAbsolutePath().toString(), dialog.outputDirectory)
            assertEquals("", dialog.environmentPath)
            assertNull(dialog.validation())
            assertEquals("Import", dialog.okButtonText())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    fun testMissingEnvironmentAndFileDirectoryAreRejected() {
        val directory = Files.createTempDirectory("requesthandoff-import")
        val collection = directory.resolve("api.postman_collection.json")
        collection.writeText("{}")
        val dialog = openDialog()
        try {
            (dialog.preferredFocusedComponent as JBTextField).text = collection.toString()
            dialog.setEnvironment(directory.resolve("missing.postman_environment.json").toString())
            dialog.setDirectory(directory.toString())
            val missing = dialog.validation()
            assertNotNull(missing)
            assertTrue(missing!!.message.contains("file"))

            dialog.setEnvironment("")
            val file = Files.createTempFile("requesthandoff", ".txt")
            dialog.setDirectory(file.toString())
            val rejected = dialog.validation()
            assertNotNull(rejected)
            assertTrue(rejected!!.message.contains("directory") || rejected.message.contains("file"))
            file.toFile().delete()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun openDialog(): ProbeDialog {
        val dialog = ProbeDialog(project)
        Disposer.register(testRootDisposable, Disposable { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) })
        return dialog
    }

    private class ProbeDialog(project: com.intellij.openapi.project.Project) :
        ImportDialog(project, null, null, null) {
        fun validation() = doValidate()

        fun okButtonText(): String = okAction.getValue(javax.swing.Action.NAME) as String

        fun setDirectory(path: String) {
            directoryField.text = path
        }

        fun setEnvironment(path: String) {
            environmentField.text = path
        }
    }
}
