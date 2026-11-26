package com.scylla.airphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    @Test
    fun normalizeAddress() {
        assertEquals("A4:B1:C2:D3:E4:F5", Pairing.normalizeAddress("a4b1c2d3e4f5"))
        assertEquals("A4:B1:C2:D3:E4:F5", Pairing.normalizeAddress(" a4:b1:c2:d3:e4:f5 "))
        assertEquals("A4:B1:C2:D3:E4:F5", Pairing.normalizeAddress("A4-B1-C2-D3-E4-F5"))
        assertNull(Pairing.normalizeAddress("a4b1c2d3e4")) // too short
        assertNull(Pairing.normalizeAddress("a4b1c2d3e4f5a6")) // too long
        assertNull(Pairing.normalizeAddress("g4b1c2d3e4f5")) // not hex
    }

    @Test
    fun likelyHost() {
        assertTrue(Pairing.isLikelyHost(0x043C)) // TV: video display and loudspeaker
        assertTrue(Pairing.isLikelyHost(0x0424)) // set-top box
        assertTrue(Pairing.isLikelyHost(0x010C)) // laptop
        assertTrue(Pairing.isLikelyHost(0x020C)) // smartphone (some TV boxes report this)
        assertTrue(Pairing.isLikelyHost(0x1F00)) // uncategorized
        assertTrue(Pairing.isLikelyHost(null))
        assertFalse(Pairing.isLikelyHost(0x0418)) // headphones
        assertTrue(Pairing.isLikelyHost(0x0420)) // "car audio": what the Android TV 13 emulator reports
        assertFalse(Pairing.isLikelyHost(0x0540)) // keyboard
        assertFalse(Pairing.isLikelyHost(0x0704)) // watch
    }

    @Test
    fun failureMessages() {
        assertTrue(Pairing.connectFailure("TV", linkCameUp = true).contains("refused"))
        assertTrue(Pairing.connectFailure("TV", linkCameUp = false).startsWith("Couldn't reach TV"))
        assertTrue(Pairing.bondFailure("TV", 4).contains("check the address")) // remote device down
        assertTrue(Pairing.bondFailure("TV", 6).contains("check the address")) // auth timeout
        assertTrue(Pairing.bondFailure("TV", 3).contains("cancelled")) // auth canceled
        assertEquals("Pairing with TV didn't finish", Pairing.bondFailure("TV", 0)) // no reason extra
    }
}
