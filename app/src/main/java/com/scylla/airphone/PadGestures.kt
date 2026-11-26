package com.scylla.airphone

/**
 * Touchpad gestures → mouse actions. Pure (times in ms, deltas in px) so the rules are unit-tested; the
 * Compose touchpad feeds it pointer events and runs [Out]s, and calls [flush] when [clickDueAt] passes.
 *
 * - one finger drag: move the cursor; tap: click (sent after [doubleTapMs], see below)
 * - two fingers: scroll (wheel + horizontal pan), content follows the fingers like on a phone
 * - tap, then touch again within [doubleTapMs] and drag: scroll with one finger. The first tap's click is
 *   held back for that window so a scroll doesn't click whatever is under the cursor first. Tap-tap without
 *   dragging is a double click.
 */
class PadGestures(
    private val slopPx: Float,
    private val scrollPxPerStep: Float,
    private val doubleTapMs: Long,
    private val longPressMs: Long,
    private val moveGain: Float,
) {
    sealed interface Out {
        data class Move(val dx: Int, val dy: Int) : Out
        data class Scroll(val wheel: Int, val pan: Int) : Out
        data object Click : Out
    }

    private enum class Mode { MOVE, AFTER_TAP, SCROLL }

    private var mode = Mode.MOVE
    private var downAt = 0L
    private var travel = 0f
    private var lastTapUp = Long.MIN_VALUE / 2
    private var clickPending = false
    private val acc = FloatArray(4) // cursor x, y; wheel, pan (sub-step remainders)

    /** When the held-back tap click must be sent if no second touch came; null if none is waiting. */
    val clickDueAt: Long? get() = if (clickPending) lastTapUp + doubleTapMs else null

    fun down(t: Long): List<Out> {
        downAt = t
        travel = 0f
        acc.fill(0f)
        mode = if (clickPending && t - lastTapUp <= doubleTapMs) Mode.AFTER_TAP else Mode.MOVE
        // A held-back click older than the window (timer late) goes out now, before this new gesture.
        return if (mode == Mode.MOVE) flush(t) else emptyList()
    }

    /** [dx]/[dy]: this frame's movement (the average when several fingers are down). */
    fun move(dx: Float, dy: Float, fingers: Int): List<Out> {
        if (fingers >= 2) mode = Mode.SCROLL
        travel += kotlin.math.hypot(dx, dy)
        if (mode == Mode.AFTER_TAP && travel > slopPx) mode = Mode.SCROLL
        return when (mode) {
            Mode.SCROLL -> scroll(dx, dy)
            Mode.MOVE -> if (travel > slopPx) cursor(dx, dy) else emptyList()
            Mode.AFTER_TAP -> emptyList()
        }
    }

    fun up(t: Long): List<Out> {
        val still = travel <= slopPx
        return when (mode) {
            Mode.MOVE -> {
                if (still) {
                    clickPending = true
                    lastTapUp = t
                }
                emptyList()
            }
            Mode.AFTER_TAP -> {
                clickPending = false
                // Quick second tap: double click. Held without moving: just the first tap's click.
                if (t - downAt < longPressMs) listOf(Out.Click, Out.Click) else listOf(Out.Click)
            }
            Mode.SCROLL -> {
                clickPending = false // the tap before a tap-drag scroll was not meant as a click
                emptyList()
            }
        }
    }

    /** The system took the touch away (back gesture from the edge, screen off, call): never a tap or click. */
    fun cancel() {
        mode = Mode.MOVE
        travel = 0f
        clickPending = false
    }

    /** Timer: sends the held-back tap click once its double-tap window has passed. */
    fun flush(t: Long): List<Out> {
        if (!clickPending || t - lastTapUp < doubleTapMs) return emptyList()
        clickPending = false
        return listOf(Out.Click)
    }

    private fun cursor(dx: Float, dy: Float): List<Out> {
        acc[0] += dx * moveGain
        acc[1] += dy * moveGain
        val x = acc[0].toInt()
        val y = acc[1].toInt()
        acc[0] -= x
        acc[1] -= y
        return if (x != 0 || y != 0) listOf(Out.Move(x, y)) else emptyList()
    }

    // Natural scrolling: fingers down → content down → wheel up (+); fingers left → content left → pan right (+).
    private fun scroll(dx: Float, dy: Float): List<Out> {
        acc[2] += dy / scrollPxPerStep
        acc[3] -= dx / scrollPxPerStep
        val wheel = acc[2].toInt()
        val pan = acc[3].toInt()
        acc[2] -= wheel
        acc[3] -= pan
        return if (wheel != 0 || pan != 0) listOf(Out.Scroll(wheel, pan)) else emptyList()
    }
}
