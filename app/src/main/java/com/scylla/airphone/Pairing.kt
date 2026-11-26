package com.scylla.airphone

/** Pure pairing helpers (no Android calls, so unit tests run on the JVM). */
object Pairing {
    /** "a4b1c2d3e4f5", "A4-B1-…", "a4:b1:…" → "A4:B1:C2:D3:E4:F5"; null if it isn't 12 hex digits. */
    fun normalizeAddress(input: String): String? {
        val hex = input.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
        return hex.chunked(2).joinToString(":")
    }

    /**
     * Could this paired device be a host (TV, box, computer, tablet)? Takes BluetoothClass.getDeviceClass()
     * (major | minor bits). Deny-list on purpose: TV boxes report odd classes (the Android TV 13 emulator says
     * "car audio"), so only things that can never take a keyboard — headsets, speakers, input devices,
     * wearables, health, toys, printers — are pushed down the list. Never used to hide a device.
     */
    fun isLikelyHost(deviceClass: Int?): Boolean {
        val c = deviceClass ?: return true
        return when (c and MAJOR_MASK) {
            MAJOR_AUDIO_VIDEO -> c !in AUDIO_ONLY
            MAJOR_PERIPHERAL, MAJOR_WEARABLE, MAJOR_HEALTH, MAJOR_TOY, MAJOR_IMAGING -> false
            else -> true
        }
    }

    /** A host that closes a fresh connection within this window rejected it (Android 15+ first-connect rule). */
    const val QUICK_DROP_MS = 3_000L

    /** [linkCameUp]: a Bluetooth link to it existed during the attempt, so it's on and in range but said no. */
    fun connectFailure(name: String, linkCameUp: Boolean): String =
        if (linkCameUp) "$name refused the connection — see Can't connect?"
        else "Couldn't reach $name — is it on and nearby?"

    /** [reason]: BluetoothDevice's hidden EXTRA_REASON on BOND_NONE (UNBOND_REASON_* values). */
    fun bondFailure(name: String, reason: Int): String = when (reason) {
        4, 6 -> "Couldn't reach $name to pair — check the address and that it's on" // remote down, auth timeout
        1, 2, 3, 8 -> "Pairing with $name was cancelled or refused" // auth failed/rejected/canceled, remote canceled
        else -> "Pairing with $name didn't finish"
    }

    private const val MAJOR_MASK = 0x1F00
    private const val MAJOR_AUDIO_VIDEO = 0x0400
    private const val MAJOR_PERIPHERAL = 0x0500
    private const val MAJOR_IMAGING = 0x0600
    private const val MAJOR_WEARABLE = 0x0700
    private const val MAJOR_TOY = 0x0800
    private const val MAJOR_HEALTH = 0x0900

    // BluetoothClass.Device.AUDIO_VIDEO_*: headset, hands-free, microphone, loudspeaker, headphones,
    // portable audio, hi-fi. Not car audio (0x0420): the Android TV 13 emulator reports exactly that.
    private val AUDIO_ONLY = setOf(0x0404, 0x0408, 0x0410, 0x0414, 0x0418, 0x041C, 0x0428)
}
