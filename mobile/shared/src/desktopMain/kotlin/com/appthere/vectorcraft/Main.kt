package com.appthere.vectorcraft

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.appthere.vectorcraft.engine.VectorEngine
import java.awt.FileDialog
import java.awt.Frame as AwtFrame
import java.io.File

/**
 * A desktop JVM window running the mobile UI: for developing and testing the shared code without a
 * device (resize the window to see the phone, tablet and laptop layouts). Not a shipping target:
 * the desktop app is apps/vectorcraft.
 */
fun main() = application {
    val engine = VectorEngine()
    // `-Dvectorcraft.window=390x844` opens at a phone's size (dp).
    val (w, h) = System.getProperty("vectorcraft.window")?.split('x')?.mapNotNull { it.toIntOrNull() }
        ?.takeIf { it.size == 2 } ?: listOf(1100, 760)
    Window(
        onCloseRequest = ::exitApplication,
        title = "VectorCraft (mobile UI)",
        state = rememberWindowState(size = DpSize(w.dp, h.dp)),
    ) {
        VectorCraftApp(engine, DesktopFiles(window))
    }
}

private class DesktopFiles(private val owner: AwtFrame) : PlatformFiles {
    override fun open(onPicked: (name: String, bytes: ByteArray) -> Unit) {
        val d = FileDialog(owner, "Open", FileDialog.LOAD)
        d.isVisible = true
        val name = d.file ?: return
        val f = File(d.directory, name)
        onPicked(f.name, f.readBytes())
    }

    override fun save(suggestedName: String, mimeType: String, bytes: ByteArray, onSaved: () -> Unit) {
        val d = FileDialog(owner, "Save", FileDialog.SAVE)
        d.file = suggestedName
        d.isVisible = true
        val name = d.file ?: return
        File(d.directory, name).writeBytes(bytes)
        onSaved()
    }
}
