@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.appthere.vectorcraft

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.appthere.vectorcraft.engine.OverlayShape
import com.appthere.vectorcraft.engine.PointerPhase
import com.appthere.vectorcraft.engine.PointerSample
import com.appthere.vectorcraft.engine.ToolType

/**
 * The document canvas: the engine's frame, the overlays over it, and the platform's pen input
 * layer on top ([PenInputLayer]: Android `MotionEvent`s, iOS `UITouch`es, mouse on the desktop).
 */
@Composable
fun ArtCanvas(state: EditorState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    Box(modifier.onSizeChanged { size: IntSize -> state.resize(size.width, size.height, density) }) {
        Canvas(Modifier.fillMaxSize()) {
            state.frame?.let { drawImage(it) }
            drawOverlays(state.overlays, measurer, density)
        }
        PenInputLayer(state, Modifier.fillMaxSize())
    }
}

/** The platform's input surface over the canvas: forwards raw samples to [EditorState.pointer]. */
@Composable
expect fun PenInputLayer(state: EditorState, modifier: Modifier)

private fun argb(c: UInt): Color = Color(c.toInt())

private fun DrawScope.polyline(points: List<Float>, closed: Boolean): Path = Path().apply {
    if (points.size < 4) return@apply
    moveTo(points[0], points[1])
    var i = 2
    while (i + 1 < points.size) {
        lineTo(points[i], points[i + 1])
        i += 2
    }
    if (closed) close()
}

/** Draw the engine's overlays (view pixels) as the desktop canvas does. */
fun DrawScope.drawOverlays(shapes: List<OverlayShape>, measurer: TextMeasurer, density: Float) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(4f * density, 3f * density))
    for (s in shapes) {
        when (s) {
            is OverlayShape.Polyline -> drawPath(
                polyline(s.points, s.closed),
                argb(s.color),
                style = Stroke(width = s.width * density, pathEffect = if (s.dashed) dash else null),
            )
            is OverlayShape.Fill -> drawPath(polyline(s.points, true), argb(s.color))
            is OverlayShape.Anchor -> {
                val half = s.size * density / 2f
                val topLeft = Offset(s.x - half, s.y - half)
                val size = Size(half * 2f, half * 2f)
                drawRect(if (s.filled) argb(s.color) else Color.White, topLeft, size)
                drawRect(argb(s.color), topLeft, size, style = Stroke(density))
            }
            is OverlayShape.CurvePoint -> {
                val radius = s.size * density / 2f
                val center = Offset(s.x, s.y)
                drawCircle(if (s.filled) argb(s.color) else Color.White, radius, center)
                drawCircle(argb(s.color), radius, center, style = Stroke(density))
            }
            is OverlayShape.Handle -> {
                drawCircle(argb(s.color), radius = 3f * density, center = Offset(s.x, s.y))
            }
            is OverlayShape.BoxHandle -> {
                val half = s.size * density / 2f
                val topLeft = Offset(s.x - half, s.y - half)
                val size = Size(half * 2f, half * 2f)
                drawRect(Color.White, topLeft, size)
                drawRect(argb(s.color), topLeft, size, style = Stroke(density))
            }
            is OverlayShape.Label -> drawText(
                measurer,
                s.text,
                Offset(s.x + 4f * density, s.y + 4f * density),
                TextStyle(color = argb(s.color), fontSize = 11.sp),
            )
            is OverlayShape.Measure -> {
                val layout = measurer.measure(s.text, TextStyle(color = Color.White, fontSize = 11.sp))
                val pad = 4f * density
                val at = Offset(s.x + 12f * density, s.y + 12f * density)
                drawRoundRect(
                    Color(0xCC444444),
                    at,
                    Size(layout.size.width + 2 * pad, layout.size.height + 2 * pad),
                    androidx.compose.ui.geometry.CornerRadius(pad, pad),
                )
                drawText(layout, topLeft = Offset(at.x + pad, at.y + pad))
            }
            is OverlayShape.Swatch -> {
                val r = 6f * density
                drawCircle(argb(s.color), r, Offset(s.x, s.y))
                drawCircle(if (s.selected) Color(0xFF1E88E5) else Color.White, r, Offset(s.x, s.y), style = Stroke(2f * density))
            }
        }
    }
}

/**
 * Compose's own pointer events as engine samples: the desktop harness (mouse, and pens through
 * Compose's stylus support) and the baseline on platforms without a native layer. Compose reports
 * pressure but not tilt, so native layers replace this where the platform has more.
 */
fun Modifier.composePointerInput(state: EditorState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val samples = ArrayList<PointerSample>(event.changes.size * 2)
            for (c in event.changes) {
                val toolType = when (c.type) {
                    PointerType.Stylus -> ToolType.STYLUS
                    PointerType.Eraser -> ToolType.ERASER
                    PointerType.Mouse -> ToolType.MOUSE
                    else -> ToolType.FINGER
                }
                // Per change, not per event: one finger lifting leaves the others moving.
                val phase = when {
                    c.changedToDownIgnoreConsumed() -> PointerPhase.DOWN
                    c.changedToUpIgnoreConsumed() -> PointerPhase.UP
                    c.pressed -> if (c.positionChanged()) PointerPhase.MOVE else continue
                    event.type == PointerEventType.Exit -> PointerPhase.HOVER_EXIT
                    event.type == PointerEventType.Move -> PointerPhase.HOVER
                    else -> continue
                }
                val buttons = if (event.buttons.isSecondaryPressed) {
                    if (toolType == ToolType.MOUSE) Buttons.MOUSE_SECONDARY else Buttons.STYLUS_PRIMARY
                } else {
                    0u
                }
                if (phase == PointerPhase.MOVE) {
                    for (h in c.historical) {
                        samples += PointerSample(
                            c.id.value.toULong(), toolType, PointerPhase.MOVE,
                            h.position.x.toDouble(), h.position.y.toDouble(),
                            c.pressure, 0f, 0f, buttons, h.uptimeMillis.toULong(),
                        )
                    }
                }
                samples += PointerSample(
                    c.id.value.toULong(), toolType, phase,
                    c.position.x.toDouble(), c.position.y.toDouble(),
                    c.pressure, 0f, 0f, buttons, c.uptimeMillis.toULong(),
                )
                c.consume()
            }
            state.pointer(samples)
        }
    }
}
