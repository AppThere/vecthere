package com.appthere.vectorcraft

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.foundation.layout.Box
import com.appthere.vectorcraft.engine.Frame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

actual fun Frame.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width.toInt(), height.toInt(), ColorType.RGBA_8888, ColorAlphaType.PREMUL))
    bitmap.installPixels(pixels)
    return bitmap.asComposeImageBitmap()
}

/** The desktop harness: mouse and trackpad through Compose's pointer events. */
@Composable
actual fun PenInputLayer(state: EditorState, modifier: Modifier) {
    Box(modifier.composePointerInput(state))
}

actual val commandIsMeta: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")
