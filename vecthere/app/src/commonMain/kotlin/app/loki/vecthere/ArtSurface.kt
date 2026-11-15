package app.loki.vecthere

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import app.loki.kit.ArtCanvas
import app.loki.kit.CanvasTextEditor
import app.loki.kit.CanvasView
import app.loki.kit.LocalWorkspace
import app.loki.kit.renderScaleFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

/**
 * The art workspace's canvas on the engine: the page as the engine renders it, at the zoom it is
 * shown at (re-rendered when the zoom settles, after every change, and while a streamed gesture
 * runs), under [CanvasTools]'s overlay, with every press handed to [CanvasTools], and the text
 * field the Type tool opens. The page, the render limits and the tools are this app's (AppParts.kt).
 */
@OptIn(FlowPreview::class)
@Composable
fun ArtSurface(native: SurfaceEngine, revision: Int, modifier: Modifier) {
    val ws = LocalWorkspace.current
    val view = remember { CanvasView() }
    val tools = remember { CanvasTools() }
    val page = pageRect(ws?.state)
    val pageIndex = pageIndex(ws?.state)
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var scale by remember { mutableStateOf(1f) }
    // Marching ants move only while there is something to march: a still canvas draws nothing per frame.
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(tools.animating) {
        if (tools.animating) { val start = withFrameMillis { it }; while (true) withFrameMillis { phase = ((it - start) / 60f) % 1000f } }
    }
    // The zoom moves at every frame of a pinch; the picture is re-rendered once it settles.
    LaunchedEffect(view) {
        snapshotFlow { view.zoom }.debounce(150).collect { z ->
            if (z > 0f) {
                scale = renderScaleFor(z * RENDER_OVERSAMPLE, MAX_RENDER_SCALE)
                ws?.let { tools.onZoom(it, z) }
            }
        }
    }
    LaunchedEffect(revision, scale, tools.live, pageIndex) {
        image = withContext(Dispatchers.Default) { native.renderPage(pageIndex, scale)?.let { rgbaToImageBitmap(it.width, it.height, it.rgba) } } ?: image
    }
    // What the tools draw over the picture (selection outlines, a tool's own feedback) is read again after each change.
    LaunchedEffect(ws?.state, scale, tools.live) { ws?.let { tools.onRevision(it, view) } }
    Box(modifier) {
        ArtCanvas(
            page, image, view,
            onInput = { input -> ws?.let { tools.onInput(input, it, view) } },
            modifier = Modifier.fillMaxSize(),
            panTool = tools.pans(ws?.tool.orEmpty()),
            overlay = { v -> with(tools) { draw(v, ws, phase) } },
        )
        val edit = tools.editing
        if (edit != null && ws != null) {
            CanvasTextEditor(edit, view.toScreen(edit.x, edit.y), onDone = { text -> tools.commitText(ws, edit, text) }, onCancel = { tools.editing = null })
        }
    }
}
