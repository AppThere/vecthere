@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.appthere.vectorcraft

import com.appthere.vectorcraft.engine.PointerPhase
import com.appthere.vectorcraft.engine.PointerSample
import com.appthere.vectorcraft.engine.ToolType
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIColor
import platform.UIKit.UIEvent
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateChanged
import platform.UIKit.UIHoverGestureRecognizer
import platform.UIKit.UIPencilInteraction
import platform.UIKit.UIPencilInteractionDelegateProtocol
import platform.UIKit.UIPencilPreferredActionSwitchEraser
import platform.UIKit.UIPencilPreferredActionSwitchPrevious
import platform.UIKit.UITouch
import platform.UIKit.UITouchTypeIndirectPointer
import platform.UIKit.UITouchTypePencil
import platform.UIKit.UIView
import platform.darwin.NSObject
import kotlin.math.PI

/**
 * The canvas's input surface on iPadOS: `UITouch`es with what Apple Pencil reports, forwarded to
 * the engine's input router (`crates/ffi/src/input.rs`).
 *
 * - **Pressure** from `force / maximumPossibleForce`; **tilt** from `altitudeAngle`; **azimuth** from
 *   `azimuthAngle(in:)`.
 * - **Coalesced touches:** Pencil samples at 240 Hz, the display at 120 Hz or less; every coalesced
 *   sample is forwarded so strokes keep their shape.
 * - **Double-tap / squeeze** (Apple Pencil 2, Pro) follow the user's system setting: switch to the
 *   eraser and back, or to the previous tool.
 * - **Hover** (iPad Pro with M2 or later, trackpads) moves the tool's hover feedback.
 * - Touch coordinates are in points; the engine works in pixels (Compose's size), so they are scaled
 *   by `contentScaleFactor`.
 */
class PencilInputView(private val state: EditorState) : UIView(frame = CGRectZero.readValue()) {
    private val pencilDelegate = PencilDelegate(state)

    init {
        multipleTouchEnabled = true
        backgroundColor = UIColor.clearColor
        val pencil = UIPencilInteraction()
        pencil.delegate = pencilDelegate
        addInteraction(pencil)
        addGestureRecognizer(UIHoverGestureRecognizer(target = this, action = platform.objc.sel_registerName("hover:")))
    }

    private fun toolType(t: UITouch): ToolType = when (t.type) {
        UITouchTypePencil -> ToolType.STYLUS
        UITouchTypeIndirectPointer -> ToolType.MOUSE
        else -> ToolType.FINGER
    }

    private fun sample(t: UITouch, phase: PointerPhase, id: ULong): PointerSample {
        val scale = contentScaleFactor
        val (x, y) = t.locationInView(this).useContents { x * scale to y * scale }
        val tool = toolType(t)
        val max = t.maximumPossibleForce
        val pressure = if (max > 0.0) (t.force / max).toFloat() else 1f
        val pencil = tool == ToolType.STYLUS
        return PointerSample(
            pointerId = id,
            toolType = tool,
            phase = phase,
            x = x,
            y = y,
            pressure = pressure,
            // altitudeAngle: π/2 is perpendicular to the screen.
            tilt = if (pencil) (PI / 2 - t.altitudeAngle).toFloat() else 0f,
            // azimuthAngle is measured clockwise from the +x axis; the engine's from up.
            azimuth = if (pencil) (t.azimuthAngleInView(this) + PI / 2).toFloat() else 0f,
            buttons = 0u,
            timeMs = (t.timestamp * 1000.0).toULong(),
        )
    }

    /** A touch's id for its whole life: UIKit keeps the same `UITouch` object from began to ended. */
    private fun idOf(t: UITouch): ULong = t.hash.toULong()

    private fun forward(touches: Set<*>, event: UIEvent?, phase: PointerPhase) {
        val samples = ArrayList<PointerSample>()
        for (t in touches) {
            if (t !is UITouch) continue
            val id = idOf(t)
            if (phase == PointerPhase.MOVE) {
                val coalesced = event?.coalescedTouchesForTouch(t).orEmpty()
                for (c in coalesced) if (c is UITouch) samples += sample(c, PointerPhase.MOVE, id)
                if (coalesced.isEmpty()) samples += sample(t, phase, id)
            } else {
                samples += sample(t, phase, id)
            }
        }
        state.pointer(samples)
    }

    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) = forward(touches, withEvent, PointerPhase.DOWN)

    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) = forward(touches, withEvent, PointerPhase.MOVE)

    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) = forward(touches, withEvent, PointerPhase.UP)

    override fun touchesCancelled(touches: Set<*>, withEvent: UIEvent?) = forward(touches, withEvent, PointerPhase.CANCEL)

    @ObjCAction
    fun hover(recognizer: UIHoverGestureRecognizer) {
        val scale = contentScaleFactor
        val (x, y) = recognizer.locationInView(this).useContents { x * scale to y * scale }
        val phase = when (recognizer.state) {
            UIGestureRecognizerStateBegan, UIGestureRecognizerStateChanged -> PointerPhase.HOVER
            else -> PointerPhase.HOVER_EXIT
        }
        // A hovering trackpad pointer and a hovering Pencil look alike before iPadOS 16.4: treat both
        // as a mouse, so hovering alone never switches fingers to navigation.
        state.pointer(listOf(PointerSample(0uL, ToolType.MOUSE, phase, x, y, 1f, 0f, 0f, 0u, 0uL)))
    }
}

/** Apple Pencil's double-tap and squeeze, as the user set them in Settings › Apple Pencil. */
private class PencilDelegate(private val state: EditorState) : NSObject(), UIPencilInteractionDelegateProtocol {
    override fun pencilInteractionDidTap(interaction: UIPencilInteraction) {
        when (UIPencilInteraction.preferredTapAction) {
            UIPencilPreferredActionSwitchEraser -> state.toggleTool("eraser")
            UIPencilPreferredActionSwitchPrevious -> state.previousTool()
            else -> {}
        }
    }
}
