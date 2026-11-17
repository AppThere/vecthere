package app.loki.vecthere

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.loki.kit.IS_MAC
import app.loki.kit.LocalWindowFrame
import app.loki.kit.LokiMenuBar
import app.loki.kit.MENU_MINIMIZE
import app.loki.kit.MENU_ZOOM
import app.loki.kit.MenuBridge
import app.loki.kit.nameMacApplication
import app.loki.kit.ribbonMenus
import app.loki.kit.toggleMaximized
import app.loki.kit.OwnFrame
import app.loki.kit.useOwnFrame
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** A native file dialog; the path, or null when cancelled. [suggested] is the name a Save As starts from. */
fun pickFile(save: Boolean, suggested: String = ""): String? {
    val dialog = FileDialog(null as Frame?, if (save) "Save As" else "Open", if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (save && suggested.isNotBlank()) dialog.file = suggested
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it).path }
}

// The engine through the FFI crate (ffi/, built by scripts/build-desktop.sh). Without the native
// library the UI still opens, on the recorded command list (FixtureTransport): nothing executes.
fun main() = application {
    nameMacApplication(APP_NAME)
    val native = remember { NativeEngine.open() }
    // Where the JVM cannot give the app a custom title bar (Linux, no JBR support) the window is undecorated and the kit's
    // OwnFrame draws the bar, caption buttons and resize edges inside the app's theme. LOKI_FRAME=own|native overrides.
    val ownFrame = remember { useOwnFrame() }
    val windowState = rememberWindowState(size = DpSize(1200.dp, 800.dp))
    Window(onCloseRequest = ::exitApplication, title = APP_NAME, state = windowState, undecorated = ownFrame) {
        val bridge = remember { MenuBridge() }
        if (IS_MAC) LokiMenuBar(bridge, mac = true, menus = { b, mac -> ribbonMenus(b.ribbon, mac) }) { id ->
            when (id) {
                MENU_MINIMIZE -> windowState.isMinimized = true
                MENU_ZOOM -> windowState.toggleMaximized()
                else -> bridge.run(id)
            }
        }
        val frame: (@Composable (@Composable () -> Unit) -> Unit)? = if (ownFrame) { content -> OwnFrame(window, windowState, APP_NAME, ::exitApplication, content) } else null
        CompositionLocalProvider(LocalWindowFrame provides frame) {
            if (native != null) {
                // Edits the surface makes itself (typing into a cell) re-read it too.
                var own by remember { mutableIntStateOf(0) }
                App(native, pickFile = { pickFile(it) }, saveAs = { pickFile(true, it) }, surface = { revision, modifier -> ArtSurface(native, revision + own, modifier) }, menuBridge = if (IS_MAC) bridge else null)
            } else {
                val commands = object {}.javaClass.getResource("/engine-commands.json")?.readText() ?: "[]"
                App(FixtureTransport(commands), pickFile = { pickFile(it) }, menuBridge = if (IS_MAC) bridge else null)
            }
        }
    }
}
