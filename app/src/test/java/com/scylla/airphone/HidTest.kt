package com.scylla.airphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidTest {
    /** Parse the descriptor and check each report's declared size matches the bytes we send. */
    @Test
    fun descriptorMatchesReportBuilders() {
        val d = Hid.DESCRIPTOR
        val bits = mutableMapOf<Int, Int>()
        var reportId = 0
        var size = 0
        var count = 0
        var depth = 0
        var i = 0
        while (i < d.size) {
            val prefix = d[i].toInt() and 0xFF
            val len = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            var value = 0
            for (b in 0 until len) value = value or ((d[i + 1 + b].toInt() and 0xFF) shl (8 * b))
            when (prefix and 0xFC) {
                0x84 -> reportId = value
                0x74 -> size = value
                0x94 -> count = value
                0x80 -> bits[reportId] = (bits[reportId] ?: 0) + size * count // Input
                0xA0 -> depth++
                0xC0 -> depth--
            }
            i += 1 + len
        }
        assertEquals("collections balanced", 0, depth)
        assertEquals(setOf(Hid.ID_KEYBOARD, Hid.ID_MOUSE, Hid.ID_CONSUMER), bits.keys)
        for ((id, b) in bits) assertEquals("report $id length", b / 8, Hid.reportLength(id))
        assertEquals(Hid.reportLength(Hid.ID_KEYBOARD), Hid.keyboard(0, 4).size)
        assertEquals(Hid.reportLength(Hid.ID_MOUSE), Hid.mouse(0, 1, 1).size)
        assertEquals(Hid.reportLength(Hid.ID_CONSUMER), Hid.consumer(Hid.CC_HOME).size)
    }

    @Test
    fun reportBytes() {
        assertEquals(listOf<Byte>(0x23, 0x02), Hid.consumer(Hid.CC_HOME).toList())
        assertEquals(listOf<Byte>(1, 127, -127), Hid.mouse(1, 500, -500).toList())
    }

    @Test
    fun usKeymap() {
        assertEquals(0 to 0x04, Hid.keyFor('a'))
        assertEquals(Hid.MOD_SHIFT to 0x1D, Hid.keyFor('Z'))
        assertEquals(0 to 0x1E, Hid.keyFor('1'))
        assertEquals(0 to 0x27, Hid.keyFor('0'))
        assertEquals(Hid.MOD_SHIFT to 0x1F, Hid.keyFor('@'))
        assertEquals(Hid.MOD_SHIFT to 0x27, Hid.keyFor(')'))
        assertEquals(0 to 0x37, Hid.keyFor('.'))
        assertEquals(Hid.MOD_SHIFT to 0x38, Hid.keyFor('?'))
        assertEquals(0 to 0x31, Hid.keyFor('\\'))
        assertEquals(Hid.MOD_SHIFT to 0x34, Hid.keyFor('"'))
        assertNull(Hid.keyFor('€'))
    }

    @Test
    fun turkishLettersTransliterate() {
        for ((tr, ascii) in "çÇğĞıİöÖşŞüÜ".zip("cCgGiIoOsSuU")) {
            assertEquals("$tr", Hid.keyFor(ascii), Hid.keyFor(tr))
        }
    }

    @Test
    fun textEdits() {
        assertEquals(0 to "lo", Hid.textEdits("hel", "hello"))
        assertEquals(2 to "", Hid.textEdits("hello", "hel"))
        assertEquals(2 to "p", Hid.textEdits("helo", "hep")) // autocorrect-style rewrite
        assertEquals(0 to "", Hid.textEdits("same", "same"))
        // Emoji was never typed on the TV (2 UTF-16 chars, no key): deleting it sends no backspace.
        assertEquals(0 to "", Hid.textEdits("a😀", "a"))
        assertEquals(1 to "", Hid.textEdits("aş", "a"))
    }

    @Test
    fun airMotion() {
        val m = AirMotion(gain = 1000f)
        m.gyro(floatArrayOf(0f, 0f, 0f), 0L) // first sample only sets the clock
        // Flat, screen up: turning left (+z) moves the cursor left.
        assertEquals(-10 to 0, m.gyro(floatArrayOf(0f, 0f, 1.05f + AirMotion.DEAD_ZONE), 10_000_000L))
        // Tilting the top up (+x) moves the cursor up.
        assertEquals(0 to -10, m.gyro(floatArrayOf(1.05f + AirMotion.DEAD_ZONE, 0f, 0f), 20_000_000L))
        // Tremor below the dead zone is ignored.
        assertEquals(0 to 0, m.gyro(floatArrayOf(0.02f, 0f, 0.02f), 30_000_000L))

        // Held upright (gravity along +y): turning left is rotation around y.
        val up = AirMotion(gain = 1000f)
        repeat(50) { up.gravity(floatArrayOf(0f, 9.81f, 0f)) }
        up.gyro(floatArrayOf(0f, 0f, 0f), 0L)
        assertEquals(-10 to 0, up.gyro(floatArrayOf(0f, 1.05f + AirMotion.DEAD_ZONE, 0f), 10_000_000L))

        // Slow motion accumulates sub-count fractions (0.525/sample) instead of dropping them.
        val slow = AirMotion(gain = 50f)
        slow.gyro(floatArrayOf(0f, 0f, 0f), 0L)
        var total = 0
        for (t in 1..10) total += slow.gyro(floatArrayOf(0f, 0f, 1.05f + AirMotion.DEAD_ZONE), t * 10_000_000L).first
        assertEquals(-5, total)
    }
}
