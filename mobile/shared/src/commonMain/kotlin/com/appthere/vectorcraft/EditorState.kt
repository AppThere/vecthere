package com.appthere.vectorcraft

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.appthere.vectorcraft.engine.CommandInfo
import com.appthere.vectorcraft.engine.DocumentState
import com.appthere.vectorcraft.engine.Frame
import com.appthere.vectorcraft.engine.InputPrefs
import com.appthere.vectorcraft.engine.InputResult
import com.appthere.vectorcraft.engine.Key
import com.appthere.vectorcraft.engine.Modifiers
import com.appthere.vectorcraft.engine.OverlayShape
import com.appthere.vectorcraft.engine.PointerSample
import com.appthere.vectorcraft.engine.ToolGroup
import com.appthere.vectorcraft.engine.UiRequest
import com.appthere.vectorcraft.engine.VectorEngine
import com.appthere.vectorcraft.engine.VectorException
import com.appthere.vectorcraft.engine.ViewOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pen barrel-button flags in [PointerSample.buttons] (mirrors `crates/ffi/src/input.rs`). */
object Buttons {
    const val STYLUS_PRIMARY: UInt = 1u
    const val STYLUS_SECONDARY: UInt = 2u
    const val MOUSE_SECONDARY: UInt = 4u
}

/**
 * The editor as the UI sees it: a thin layer over [VectorEngine]. Panels read the state here and
 * act through [run]; the canvas forwards raw input to [pointer] and draws [frame] and [overlays].
 *
 * Rendering runs off the main thread and is conflated: input asks for a frame, and a burst of
 * input during one render costs one more render, not one per event.
 */
@Stable
class EditorState(val engine: VectorEngine, private val scope: CoroutineScope) {
    var document by mutableStateOf(engine.documentState())
        private set
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var overlays by mutableStateOf<List<OverlayShape>>(emptyList())
        private set
    var commands by mutableStateOf<List<CommandInfo>>(emptyList())
        private set
    val toolGroups: List<ToolGroup> = engine.tools()
    var inputPrefs by mutableStateOf(engine.inputPrefs())
        private set
    var viewOptions by mutableStateOf(engine.viewOptions())
        private set
    var stylusSeen by mutableStateOf(false)
        private set
    var zoomPercent by mutableStateOf(100.0)
        private set
    /** A message for the snackbar (errors, tool status). */
    var message by mutableStateOf<String?>(null)
    /** Dialog a tool or command asked for: id and JSON parameters. */
    var dialog by mutableStateOf<Pair<String, String>?>(null)
    /** The soft keyboard should show (the Type tool is editing). */
    var wantsText by mutableStateOf(false)
        private set

    private val renders = Channel<Unit>(Channel.CONFLATED)

    init {
        refreshCommands()
        scope.launch {
            renders.receiveAsFlow().collect {
                val f: Result<Frame> = withContext(Dispatchers.Default) { runCatching { engine.render() } }
                f.onSuccess { frame = it.toImageBitmap() }.onFailure { message = it.message }
                overlays = withContext(Dispatchers.Default) { engine.overlays() }
            }
        }
    }

    fun requestRender() {
        renders.trySend(Unit)
    }

    private fun refreshCommands() {
        commands = engine.commands()
    }

    /** Re-read what the panels show after the document or tool changed. */
    fun refresh() {
        document = engine.documentState()
        zoomPercent = engine.viewState().zoom * 100.0
        stylusSeen = engine.stylusSeen()
        wantsText = engine.wantsText()
        refreshCommands()
        requestRender()
    }

    fun resize(width: Int, height: Int, density: Float) {
        if (width <= 0 || height <= 0) return
        engine.resize(width.toUInt(), height.toUInt(), density.toDouble())
        refresh()
    }

    /** Run a command by id with JSON params; errors go to the snackbar. */
    fun run(id: String, params: String = "{}"): String? = try {
        engine.execute(id, params).also { refresh() }
    } catch (e: VectorException) {
        message = e.message
        refresh()
        null
    }

    fun selectTool(id: String) {
        val current = engine.activeTool()
        try {
            engine.selectTool(id)
            if (current != id) previous = current
        } catch (e: VectorException) {
            message = e.message
        }
        refresh()
    }

    /** The tool before the last [toggleTool] or [selectTool], for "switch to previous". */
    private var previous: String? = null

    /**
     * Switch to [id], or back to the tool before it when it is already active (Apple Pencil
     * double-tap with "Switch between current tool and eraser").
     */
    fun toggleTool(id: String) {
        val current = engine.activeTool()
        if (current == id) {
            previous?.let { selectTool(it) }
        } else {
            selectTool(id)
        }
    }

    /** Back to the tool chosen before this one (Apple Pencil "Switch between current tool and last used"). */
    fun previousTool() {
        previous?.let { selectTool(it) }
    }

    fun pointer(samples: List<PointerSample>) {
        if (samples.isEmpty()) return
        handle(engine.pointer(samples))
    }

    fun key(key: Key) = handle(engine.key(key))

    fun text(text: String) = handle(engine.text(text))

    fun setModifiers(mods: Modifiers) = engine.setModifiers(mods)

    /** A hardware-keyboard chord (`Cmd+Z`, `P`): a command or tool shortcut. */
    fun shortcut(chord: String): Boolean = try {
        (engine.shortcut(chord) != null).also { if (it) refresh() }
    } catch (e: VectorException) {
        message = e.message
        true
    }

    private fun handle(r: InputResult) {
        r.error?.let { message = it }
        for (req in r.requests) {
            when (req) {
                is UiRequest.Dialog -> dialog = req.id to req.params
                is UiRequest.Status -> message = req.message
                is UiRequest.ToolChanged -> {}
            }
        }
        if (r.documentChanged || r.commands.isNotEmpty() || r.requests.isNotEmpty()) {
            refresh()
        } else if (r.viewChanged) {
            zoomPercent = engine.viewState().zoom * 100.0
            stylusSeen = engine.stylusSeen()
            requestRender()
        } else if (!stylusSeen && engine.stylusSeen()) {
            stylusSeen = true
        }
    }

    fun pan(dx: Double, dy: Double) {
        engine.pan(dx, dy)
        requestRender()
    }

    fun zoomBy(factor: Double, x: Double, y: Double) {
        engine.zoomBy(factor, x, y)
        zoomPercent = engine.viewState().zoom * 100.0
        requestRender()
    }

    fun fitArtboard() {
        engine.fitArtboard()
        refresh()
    }

    fun updateInputPrefs(transform: (InputPrefs) -> InputPrefs) {
        val p = transform(inputPrefs)
        engine.setInputPrefs(p)
        inputPrefs = p
    }

    fun updateViewOptions(transform: (ViewOptions) -> ViewOptions) {
        val o = transform(viewOptions)
        engine.setViewOptions(o)
        viewOptions = o
        requestRender()
    }

    fun newDocument(width: Double = 612.0, height: Double = 792.0) {
        try {
            engine.newDocument(width, height)
        } catch (e: VectorException) {
            message = e.message
        }
        refresh()
    }

    fun openBytes(name: String, bytes: ByteArray) {
        try {
            engine.openBytes(name, bytes)
        } catch (e: VectorException) {
            message = e.message
        }
        refresh()
    }

    /** The document as a file in [format] (`vectorcraft`, `svg`, `pdf`, `png`…), or null on error. */
    fun saveBytes(format: String): ByteArray? = try {
        engine.saveBytes(format)
    } catch (e: VectorException) {
        message = e.message
        null
    }

    fun markSaved() {
        try {
            engine.markSaved()
        } catch (_: VectorException) {
            // Nothing open: nothing to mark.
        }
        refresh()
    }

    fun commandEnabled(id: String): Boolean = commands.firstOrNull { it.id == id }?.enabled ?: false
}

/** A rendered frame as a Compose image (premultiplied RGBA8 in, platform bitmap out). */
expect fun Frame.toImageBitmap(): ImageBitmap

/** An empty state for previews and before the first frame. */
fun DocumentState.titleOrApp(): String = if (hasDocument) title else "VectorCraft"
