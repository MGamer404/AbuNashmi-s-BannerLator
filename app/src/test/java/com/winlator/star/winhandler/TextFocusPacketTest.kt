package com.winlator.star.winhandler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFocusPacketTest {

    private fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** A datagram laid out as bltextfocus.exe writes it. */
    private fun packet(
        active: Int = 1, style: Int = 0, x: Int = 0, y: Int = 0, w: Int = 0, h: Int = 0, pid: Int = 0,
        code: Int = 17, magic: String = "BTF1",
    ): ByteArray =
        byteArrayOf(code.toByte()) + magic.toByteArray(Charsets.US_ASCII) + byteArrayOf(active.toByte()) +
            le(style) + le(x) + le(y) + le(w) + le(h) + le(pid)

    @Test fun thePacketIsThirtyBytes() {
        assertEquals(30, packet().size)
        assertEquals(TextFocusHelper.PACKET_LENGTH, packet().size)
    }

    @Test fun decodesEveryField() {
        val bytes = packet(active = 1, style = 0x2024, x = 640, y = 360, w = 2, h = 18, pid = 4242)
        val p = TextFocusHelper.parsePacket(bytes, bytes.size)
        assertNotNull(p)
        assertTrue(p!!.active)
        assertEquals(0x2024, p.editStyle)
        assertEquals(640, p.caretX)
        assertEquals(360, p.caretY)
        assertEquals(2, p.caretW)
        assertEquals(18, p.caretH)
        assertEquals(4242, p.pid)
    }

    @Test fun inactiveAndNegativeCoordinates() {
        val bytes = packet(active = 0, x = -5, y = -1)
        val p = TextFocusHelper.parsePacket(bytes, bytes.size)!!
        assertFalse(p.active)
        assertEquals(-5, p.caretX)
        assertEquals(-1, p.caretY)
    }

    @Test fun highBitsOfAnIntDecodeSigned() {
        val bytes = packet(style = -1, pid = Int.MIN_VALUE)
        val p = TextFocusHelper.parsePacket(bytes, bytes.size)!!
        assertEquals(-1, p.editStyle)
        assertEquals(Int.MIN_VALUE, p.pid)
    }

    @Test fun aReceiveBufferLongerThanTheDatagramIsFine() {
        val bytes = packet(pid = 7) + ByteArray(34)
        assertEquals(64, bytes.size)
        assertEquals(7, TextFocusHelper.parsePacket(bytes, 30)!!.pid)
    }

    @Test fun shortInputIsNull() {
        val bytes = packet()
        assertNull(TextFocusHelper.parsePacket(bytes, 29))
        assertNull(TextFocusHelper.parsePacket(bytes.copyOf(29), 30))
        assertNull(TextFocusHelper.parsePacket(bytes, 0))
        assertNull(TextFocusHelper.parsePacket(ByteArray(0), 0))
        assertNull(TextFocusHelper.parsePacket(null, 30))
    }

    @Test fun wrongCodeOrMagicIsNull() {
        assertNull(TextFocusHelper.parsePacket(packet(code = 16), 30))
        assertNull(TextFocusHelper.parsePacket(packet(code = 14), 30))
        assertNull(TextFocusHelper.parsePacket(packet(magic = "BTF2"), 30))
        assertNull(TextFocusHelper.parsePacket(packet(magic = "BXS1"), 30))
    }

    @Test fun anActiveByteOtherThanZeroOrOneIsNull() {
        assertNull(TextFocusHelper.parsePacket(packet(active = 2), 30))
        assertNull(TextFocusHelper.parsePacket(packet(active = 255), 30))
    }

    @Test fun garbageIsNull() {
        val garbage = ByteArray(64) { (it * 37 + 11).toByte() }
        assertNull(TextFocusHelper.parsePacket(garbage, 64))
        assertNull(TextFocusHelper.parsePacket(ByteArray(64), 64))
        assertNull(TextFocusHelper.parsePacket(ByteArray(64) { 0xFF.toByte() }, 64))
    }
}
