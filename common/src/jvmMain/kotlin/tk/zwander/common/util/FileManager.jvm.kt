package tk.zwander.common.util

import dev.zwander.kotlin.file.IPlatformFile
import dev.zwander.kotlin.file.PlatformFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileSystemView

actual object FileManager {
    actual suspend fun pickFile(): IPlatformFile? {
        return FileKit.openFilePicker()?.let { PlatformFile(it.file) }
    }

    actual suspend fun pickDirectory(): IPlatformFile? {
        return withContext(Dispatchers.Main) {
            val chooser = JFileChooser(FileSystemView.getFileSystemView())
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.isMultiSelectionEnabled = false
            
            val result = chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                PlatformFile(chooser.selectedFile)
            } else {
                null
            }
        }
    }

    actual suspend fun saveFile(name: String): IPlatformFile? {
        val dotIndex = name.lastIndexOf('.')
        val baseName = name.slice(0 until dotIndex)
        val extension = name.slice(dotIndex + 1 until name.length)

        return FileKit.openFileSaver(
            suggestedName = baseName,
            defaultExtension = extension,
        )?.let { PlatformFile(it.file) }
    }

    actual suspend fun getTempDirectory(): IPlatformFile? {
        return PlatformFile(File.createTempFile("bifrost", "tmp").parentFile!!)
    }
}
