package com.winlator.star.ui.screens

import android.content.Context
import android.content.Intent
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.container.Container
import com.winlator.star.core.WinePath
import java.io.File

/**
 * Runs a Windows program once, without adding it to the Games list: the File Manager's Run action and
 * "Open with → Bannerlator" ([com.winlator.star.OpenExeActivity]) both go through here so the two map
 * drives and write the throwaway shortcut identically.
 *
 * The file's folder is mapped to a Wine drive letter (persisted on the container, see [WinePath]) and
 * XServerDisplayActivity gets a shortcut whose Exec is `wine <X:\path>`. The .desktop lives in app
 * storage, not the container's desktop dir, so it never shows up in the Shortcuts list.
 */
internal object ExeQuickRun {

    /**
     * Writes the throwaway shortcut for [file] in [container] and returns it. BLOCKING (may persist a
     * new drive letter on the container) - call off the main thread. Throws
     * [WinePath.NoFreeDriveLetterException] when every letter is taken.
     */
    fun writeRunShortcut(context: Context, container: Container, file: File): File {
        val winPath = WinePath.resolveWindowsPath(container, file.absolutePath)
        val escaped = WinePath.escapeForExec(winPath)
        val desktopDir = File(context.filesDir, "desktops").apply { mkdirs() }
        val safeName = file.nameWithoutExtension
            .replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "run" }
        return File(desktopDir, "$safeName.desktop").apply {
            writeText(
                buildString {
                    append("[Desktop Entry]\n")
                    append("Name=").append(file.nameWithoutExtension).append("\n")
                    append("Exec=wine ").append(escaped).append("\n")
                    append("Icon=").append(safeName).append("\n")
                    append("Type=Application\n")
                    append("StartupWMClass=explorer\n")
                    append("\ncontainer_id:").append(container.id).append("\n")
                    append("\n[Extra Data]\n")
                }
            )
        }
    }

    /** The session intent for a shortcut written by [writeRunShortcut]. */
    fun sessionIntent(context: Context, container: Container, shortcutFile: File): Intent =
        Intent(context, XServerDisplayActivity::class.java)
            .putExtra("container_id", container.id)
            .putExtra("shortcut_path", shortcutFile.absolutePath)
            .putExtra("shortcut_name", shortcutFile.nameWithoutExtension)

    /** What to tell the user when [writeRunShortcut] failed for [file]. */
    fun failureMessage(file: File, failure: Throwable): String =
        if (failure is WinePath.NoFreeDriveLetterException) {
            "No free drive letters left in this container — remove one you don't need in its Drives tab"
        } else {
            "Couldn't prepare ${file.name} to run"
        }
}
