package com.scylla.airphone

import com.scylla.airphone.PadGestures.Out
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PadGesturesTest {
    private fun pad() = PadGestures(slopPx = 10f, scrollPxPerStep = 20f, doubleTapMs = 300, longPressMs = 500, moveGain = 1f)

    @Test
    fun tapClicksAfterDoubleTapWindow() {
        val g = pad()
        g.down(0); assertEquals(emptyList<Out>(), g.up(80))
        assertEquals(380L, g.clickDueAt)
        assertEquals(emptyList<Out>(), g.flush(300)) // window not over yet
        assertEquals(listOf(Out.Click), g.flush(380))
        assertNull(g.clickDueAt)
    }

    @Test
    fun dragMovesCursorAfterSlop() {
        val g = pad()
        g.down(0)
        assertEquals(emptyList<Out>(), g.move(6f, 0f, 1)) // inside slop
        assertEquals(listOf(Out.Move(6, 0)), g.move(6f, 0f, 1))
        assertEquals(emptyList<Out>(), g.up(200)) // moved: no click
        assertNull(g.clickDueAt)
    }

    @Test
    fun twoFingersScrollNaturally() {
        val g = pad()
        g.down(0)
        assertEquals(listOf(Out.Scroll(2, 0)), g.move(0f, 40f, 2)) // fingers down → wheel up
        assertEquals(listOf(Out.Scroll(-1, 1)), g.move(-25f, -20f, 2)) // up + left → wheel down, pan right
        assertEquals(emptyList<Out>(), g.up(400))
        assertNull(g.clickDueAt)
    }

    @Test
    fun tapThenDragScrollsWithoutClicking() {
        val g = pad()
        g.down(0); g.up(80) // tap: click held back
        g.down(200) // second touch inside the window
        assertEquals(emptyList<Out>(), g.move(0f, 8f, 1)) // inside slop: undecided
        assertEquals(listOf(Out.Scroll(-1, 0)), g.move(0f, -30f, 1)) // past slop: one-finger scroll
        assertEquals(emptyList<Out>(), g.up(900))
        assertEquals(emptyList<Out>(), g.flush(2000)) // the first tap's click was dropped
    }

    @Test
    fun doubleTapIsDoubleClick() {
        val g = pad()
        g.down(0); g.up(80)
        g.down(200)
        assertEquals(listOf(Out.Click, Out.Click), g.up(260))
        assertNull(g.clickDueAt)
    }

    @Test
    fun secondTouchHeldStillIsJustTheFirstClick() {
        val g = pad()
        g.down(0); g.up(80)
        g.down(200)
        assertEquals(listOf(Out.Click), g.up(1200))
    }

    @Test
    fun cancelledTouchNeverClicks() {
        val g = pad()
        g.down(0); g.cancel()
        assertNull(g.clickDueAt)
        g.down(1000); g.up(1080) // tap
        g.down(1200); g.cancel() // second touch taken away (e.g. back swipe)
        assertNull(g.clickDueAt)
        assertEquals(emptyList<Out>(), g.flush(5000))
    }

    @Test
    fun lateTouchSendsPendingClickFirst() {
        val g = pad()
        g.down(0); g.up(80)
        assertEquals(listOf(Out.Click), g.down(500)) // timer didn't fire yet; window is over
        assertEquals(listOf(Out.Move(20, 0)), g.move(20f, 0f, 1)) // and this is a normal move
    }
}
