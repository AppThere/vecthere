package com.appthere.vectorcraft

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.appthere.vectorcraft.engine.PointerPhase
import com.appthere.vectorcraft.engine.PointerSample
import com.appthere.vectorcraft.engine.ToolType
import kotlin.math.pow

/**
 * The canvas's input surface on Android and ChromeOS: every `MotionEvent` sample, with what pens
 * report, goes to the engine's input router (`crates/ffi/src/input.rs`), which decides what is
 * drawing, navigating or a palm.
 *
 * - **Samsung S Pen** (Galaxy Tab, Galaxy S Ultra, Galaxy Book): `TOOL_TYPE_STYLUS` with pressure,
 *   tilt and orientation; the side button is `BUTTON_STYLUS_PRIMARY` (older One UI builds report
 *   `BUTTON_SECONDARY`); Air View hover arrives as hover events.
 * - **USI pens** (Chromebooks): the same axes, plus `TOOL_TYPE_ERASER` for the eraser end and the
 *   barrel button as `BUTTON_STYLUS_PRIMARY`.
 * - **Latency:** a pen's first event asks for unbuffered dispatch, so its samples arrive as the
 *   digitizer reports them (up to 240 Hz on S Pen) instead of once per frame; each event's
 *   historical samples are forwarded too, so fast strokes keep their shape.
 * - **Palms:** Android 13+ marks a contact it decided was a palm `FLAG_CANCELED`; it is cancelled,
 *   never committed.
 * - **Mice and touchpads** (ChromeOS, DeX, Bluetooth): the wheel pans (Ctrl zooms), and Android 14's
 *   touchpad pinch and two-finger swipe zoom and pan.
 */
@SuppressLint("ViewConstructor")
class PenInputView(context: Context, private val state: EditorState) : View(context) {
    private val density = resources.displayMetrics.density

    init {
        isClickable = true
        // Keys go to the Compose editor, not this view.
        isFocusable = false
    }

    private fun toolType(e: MotionEvent, i: Int): ToolType = when (e.getToolType(i)) {
        MotionEvent.TOOL_TYPE_STYLUS -> ToolType.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> ToolType.ERASER
        MotionEvent.TOOL_TYPE_MOUSE -> ToolType.MOUSE
        else -> ToolType.FINGER
    }

    private fun buttons(e: MotionEvent, tool: ToolType): UInt {
        val s = e.buttonState
        var b = 0u
        if (s and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) b = b or Buttons.STYLUS_PRIMARY
        if (s and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) b = b or Buttons.STYLUS_SECONDARY
        if (s and MotionEvent.BUTTON_SECONDARY != 0) {
            // Older S Pen firmware reports its side button as the secondary button.
            b = b or if (tool == ToolType.MOUSE) Buttons.MOUSE_SECONDARY else Buttons.STYLUS_PRIMARY
        }
        return b
    }

    private fun sample(e: MotionEvent, i: Int, phase: PointerPhase): PointerSample {
        val tool = toolType(e, i)
        return PointerSample(
            pointerId = e.getPointerId(i).toULong(),
            toolType = tool,
            phase = phase,
            x = e.getX(i).toDouble(),
            y = e.getY(i).toDouble(),
            pressure = e.getPressure(i),
            tilt = e.getAxisValue(MotionEvent.AXIS_TILT, i),
            azimuth = e.getOrientation(i),
            buttons = buttons(e, tool),
            timeMs = e.eventTime.toULong(),
        )
    }

    private fun historical(e: MotionEvent, i: Int, h: Int): PointerSample {
        val tool = toolType(e, i)
        return PointerSample(
            pointerId = e.getPointerId(i).toULong(),
            toolType = tool,
            phase = PointerPhase.MOVE,
            x = e.getHistoricalX(i, h).toDouble(),
            y = e.getHistoricalY(i, h).toDouble(),
            pressure = e.getHistoricalPressure(i, h),
            tilt = e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h),
            azimuth = e.getHistoricalOrientation(i, h),
            buttons = buttons(e, tool),
            timeMs = e.getHistoricalEventTime(h).toULong(),
        )
    }

    /** Android 13+: the system decided this contact was a palm. */
    private fun canceledByPalm(e: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (e.flags and MotionEvent.FLAG_CANCELED) != 0

    /** Android 14 touchpad gestures (ChromeOS, DeX): pinch zooms, two-finger swipe pans. */
    private fun touchpadGesture(e: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return when (e.classification) {
            MotionEvent.CLASSIFICATION_PINCH -> {
                if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                    val f = e.getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR)
                    if (f > 0f) state.zoomBy(f.toDouble(), e.x.toDouble(), e.y.toDouble())
                }
                true
            }
            MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE -> {
                if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                    state.pan(
                        -e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE).toDouble(),
                        -e.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE).toDouble(),
                    )
                }
                true
            }
            else -> false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (touchpadGesture(e)) return true
        val samples = ArrayList<PointerSample>(e.pointerCount * (e.historySize + 1))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (toolType(e, i) != ToolType.FINGER) requestUnbufferedDispatch(e)
                samples += sample(e, i, PointerPhase.DOWN)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    for (i in 0 until e.pointerCount) samples += historical(e, i, h)
                }
                for (i in 0 until e.pointerCount) samples += sample(e, i, PointerPhase.MOVE)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val phase = if (canceledByPalm(e)) PointerPhase.CANCEL else PointerPhase.UP
                samples += sample(e, e.actionIndex, phase)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) samples += sample(e, i, PointerPhase.CANCEL)
            }
            else -> return false
        }
        state.pointer(samples)
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val phase = when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> PointerPhase.HOVER
            MotionEvent.ACTION_HOVER_EXIT -> PointerPhase.HOVER_EXIT
            else -> return super.onHoverEvent(e)
        }
        val samples = ArrayList<PointerSample>(e.historySize + 1)
        if (phase == PointerPhase.HOVER) {
            for (h in 0 until e.historySize) samples += historical(e, 0, h).copy(phase = PointerPhase.HOVER)
        }
        samples += sample(e, 0, phase)
        state.pointer(samples)
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (e.metaState and KeyEvent.META_CTRL_ON != 0) {
                state.zoomBy(1.1.pow(v.toDouble()), e.x.toDouble(), e.y.toDouble())
            } else {
                // One wheel notch: 48 dp.
                val step = 48.0 * density
                state.pan(-h * step, v * step)
            }
            return true
        }
        return super.onGenericMotionEvent(e)
    }
}
