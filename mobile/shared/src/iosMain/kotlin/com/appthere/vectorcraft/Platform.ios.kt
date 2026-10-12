@file:OptIn(ExperimentalComposeUiApi::class)

package com.appthere.vectorcraft

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import com.appthere.vectorcraft.engine.Frame
import com.appthere.vectorcraft.engine.VectorEngine
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.UIKit.UIViewController

actual fun Frame.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo(width.toInt(), height.toInt(), ColorType.RGBA_8888, ColorAlphaType.PREMUL))
    bitmap.installPixels(pixels)
    return bitmap.asComposeImageBitmap()
}

/**
 * The Pencil view over the canvas. Non-cooperative: touches go to it at once (the cooperative mode
 * holds them back while it decides whether a scroll is starting, which a pen stroke can't afford).
 */
@Composable
actual fun PenInputLayer(state: EditorState, modifier: Modifier) {
    UIKitView(
        factory = { PencilInputView(state) },
        modifier = modifier,
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = false,
        ),
    )
}

/** Apple keyboards: Command is the shortcut modifier. */
actual val commandIsMeta: Boolean = true

/** The app's root view controller, for the Swift entry point (`iosApp/iosApp/ContentView.swift`). */
fun MainViewController(): UIViewController {
    val engine = VectorEngine()
    val files = IosFiles()
    return ComposeUIViewController { VectorCraftApp(engine, files) }
}
