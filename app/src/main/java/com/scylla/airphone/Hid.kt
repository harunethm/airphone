package com.scylla.airphone

/**
 * HID protocol: one composite report descriptor (keyboard + mouse + consumer keys) and the
 * report bytes it defines. Report lengths here MUST match the descriptor; HidTest checks it.
 */
object Hid {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2
    const val ID_CONSUMER = 3

    // Consumer usages (HID Usage Tables, page 0x0C). Linux hid-input + Android Generic.kl map them
    // to the same keycodes a TV remote sends.
    const val CC_POWER = 0x30
    const val CC_MENU = 0x40
    const val CC_OK = 0x41 // KEY_SELECT -> DPAD_CENTER
    const val CC_UP = 0x42
    const val CC_DOWN = 0x43
    const val CC_LEFT = 0x44
    const val CC_RIGHT = 0x45
    const val CC_PLAY_PAUSE = 0xCD
    const val CC_MUTE = 0xE2
    const val CC_VOL_UP = 0xE9
    const val CC_VOL_DOWN = 0xEA
    const val CC_HOME = 0x223
    const val CC_BACK = 0x224

    const val KEY_ENTER = 0x28
    const val KEY_BACKSPACE = 0x2A
    const val MOD_SHIFT = 0x02

    val DESCRIPTOR = bytes(
        // Keyboard: modifiers byte, reserved byte, 6 key slots
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x75, 0x08, 0x95, 0x01, 0x81, 0x01,
        0x19, 0x00, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65, 0x75, 0x08, 0x95, 0x06, 0x81, 0x00,
        0xC0,
        // Mouse: 3 buttons + 5 pad bits, X, Y (relative, -127..127)
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE,
        0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
        0x75, 0x05, 0x95, 0x01, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x02, 0x81, 0x06,
        0xC0, 0xC0,
        // Consumer control: one 16-bit usage (0 = released)
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
        0x19, 0x00, 0x2A, 0xFF, 0x03, 0x15, 0x00, 0x26, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    )

    fun keyboard(mod: Int, usage: Int) = byteArrayOf(mod.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0)

    fun mouse(buttons: Int, dx: Int, dy: Int) =
        byteArrayOf(buttons.toByte(), dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte())

    fun consumer(usage: Int) = byteArrayOf(usage.toByte(), (usage shr 8).toByte())

    fun reportLength(id: Int): Int? = when (id) {
        ID_KEYBOARD -> 8
        ID_MOUSE -> 3
        ID_CONSUMER -> 2
        else -> null
    }

    private const val PLAIN_SYMBOLS = "-=[]\\;'`,./"
    private const val SHIFT_SYMBOLS = "_+{}|:\"~<>?"
    private val SYMBOL_USAGES = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
    private const val SHIFT_DIGITS = "!@#\$%^&*()" // shift + 1..0

    // HID sends key positions, not characters, and the TV reads them with its US layout, so Turkish
    // letters go out as their closest ASCII letter (ş -> s). Real Turkish needs a Turkish-Q layout on the TV.
    private const val TR_LETTERS = "çÇğĞıİöÖşŞüÜ"
    private const val TR_ASCII = "cCgGiIoOsSuU"

    /** (modifier, usage) on a US layout, or null when a US keyboard can't type [c]. */
    // ponytail: characters with no US key and no transliteration (emoji, €, other scripts) are skipped.
    fun keyFor(c: Char): Pair<Int, Int>? = when (c) {
        in 'a'..'z' -> 0 to 0x04 + (c - 'a')
        in 'A'..'Z' -> MOD_SHIFT to 0x04 + (c - 'A')
        in '1'..'9' -> 0 to 0x1E + (c - '1')
        '0' -> 0 to 0x27
        '\n' -> 0 to KEY_ENTER
        '\t' -> 0 to 0x2B
        ' ' -> 0 to 0x2C
        in PLAIN_SYMBOLS -> 0 to SYMBOL_USAGES[PLAIN_SYMBOLS.indexOf(c)]
        in SHIFT_SYMBOLS -> MOD_SHIFT to SYMBOL_USAGES[SHIFT_SYMBOLS.indexOf(c)]
        in SHIFT_DIGITS -> MOD_SHIFT to 0x1E + SHIFT_DIGITS.indexOf(c)
        in TR_LETTERS -> keyFor(TR_ASCII[TR_LETTERS.indexOf(c)])
        else -> null
    }

    /**
     * Backspaces + text that turn [old] into [new] on the TV, assuming its cursor sits at the end.
     * Only characters that were actually typed count as backspaces, so deleting a skipped
     * character (e.g. an emoji) doesn't erase real text on the TV.
     */
    fun textEdits(old: String, new: String): Pair<Int, String> {
        val common = old.commonPrefixWith(new).length
        return old.substring(common).count { keyFor(it) != null } to new.substring(common)
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
