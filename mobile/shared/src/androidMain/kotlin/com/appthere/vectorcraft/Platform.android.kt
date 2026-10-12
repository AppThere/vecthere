package com.appthere.vectorcraft

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import com.appthere.vectorcraft.engine.Frame
import java.nio.ByteBuffer

/** `ARGB_8888` bitmaps hold premultiplied RGBA bytes in memory: the engine's frame as it is. */
actual fun Frame.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
    return bitmap.asImageBitmap()
}

@Composable
actual fun PenInputLayer(state: EditorState, modifier: Modifier) {
    AndroidView(factory = { PenInputView(it, state) }, modifier = modifier)
}

/** ChromeOS and Android keyboards: Ctrl is the shortcut modifier (Search/Meta is not). */
actual val commandIsMeta: Boolean = false
