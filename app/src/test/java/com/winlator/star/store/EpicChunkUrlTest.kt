package com.winlator.star.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Pure chunk-URL / failure-reason / ChunksV5 helpers of [EpicDownloadManager]. None of them touch
 * android.util.Log, so they run on the plain JVM. The ChunksV5 expectations were produced with
 * Legendary's formulas (struct '<Q' / '<IIII' + base64.urlsafe_b64encode + strip('=')).
 */
class EpicChunkUrlTest {

    private val guid = intArrayOf(1, 2, 3, 4)
    private val hash = 0x0123456789ABCDEFL

    @Test
    fun chunkDirFollowsFeatureLevel() {
        assertEquals("Chunks", EpicDownloadManager.chunkDirForVersion(2))
        assertEquals("ChunksV2", EpicDownloadManager.chunkDirForVersion(3))
        assertEquals("ChunksV3", EpicDownloadManager.chunkDirForVersion(6))
        assertEquals("ChunksV4", EpicDownloadManager.chunkDirForVersion(15))
        assertEquals("ChunksV4", EpicDownloadManager.chunkDirForVersion(21))
        assertEquals("ChunksV5", EpicDownloadManager.chunkDirForVersion(22))
        assertEquals("ChunksV5", EpicDownloadManager.chunkDirForVersion(24))
        assertTrue(EpicDownloadManager.isChunksV5("ChunksV5"))
        assertFalse(EpicDownloadManager.isChunksV5("ChunksV4"))
    }

    @Test
    fun v4PathIsHexAndDecimalGroup() {
        assertEquals(
            "ChunksV4/05/0123456789ABCDEF_00000001000000020000000300000004.chunk",
            EpicDownloadManager.buildChunkPath("ChunksV4", 5, hash, guid, null)
        )
        // A secret GUID is ignored below ChunksV5.
        assertEquals(
            "ChunksV4/05/0123456789ABCDEF_00000001000000020000000300000004.chunk",
            EpicDownloadManager.buildChunkPath("ChunksV4", 5, hash, guid, intArrayOf(9, 9, 9, 9))
        )
    }

    @Test
    fun v5PlainPathIsBase64() {
        assertEquals(
            "ChunksV5/plain/05/782riWdFIwE_AQAAAAIAAAADAAAABAAAAA.chunk",
            EpicDownloadManager.buildChunkPath("ChunksV5", 5, hash, guid, null)
        )
        assertEquals(
            "ChunksV5/plain/05/782riWdFIwE_AQAAAAIAAAADAAAABAAAAA.chunk",
            EpicDownloadManager.buildChunkPath("ChunksV5", 5, hash, guid, intArrayOf(0, 0, 0, 0))
        )
        // Hash/GUID with high bits set: '-'/'_' alphabet, no '=' padding.
        assertEquals(
            "ChunksV5/plain/123/_v________8_776t3v____8AAAAAAAAAgA.chunk",
            EpicDownloadManager.buildChunkPath(
                "ChunksV5", 123, -2L,
                intArrayOf(0xDEADBEEF.toInt(), -1, 0, Int.MIN_VALUE), null
            )
        )
    }

    @Test
    fun v5SecretPathUsesBase64SecretGuid() {
        val secret = intArrayOf(0xAABBCCDD.toInt(), 0x11223344, 0x55667788, 0x99000011.toInt())
        assertEquals(
            "ChunksV5/3cy7qkQzIhGId2ZVEQAAmQ/05/782riWdFIwE_AQAAAAIAAAADAAAABAAAAA.chunk",
            EpicDownloadManager.buildChunkPath("ChunksV5", 5, hash, guid, secret)
        )
    }

    @Test
    fun chunkInfoGetPathAndSecretFlag() {
        val c = EpicDownloadManager.ChunkInfo()
        c.guid = guid
        c.hash = hash
        c.groupNum = 5
        assertFalse(c.hasSecret())
        assertEquals(
            "ChunksV4/05/0123456789ABCDEF_00000001000000020000000300000004.chunk",
            c.getPath("ChunksV4")
        )
        c.secretGuid = intArrayOf(0, 0, 0, 1)
        assertTrue(c.hasSecret())
    }

    @Test
    fun chunkUrlAppendsSignedQueryOnlyWhenAsked() {
        val cdn = EpicDownloadManager.CdnUrl(
            "https://egdownload.fastly-edge.com", "/Builds/Org/o-x/y/default", "?f_token=ab/cd+ef=="
        )
        val path = "ChunksV4/05/AA_BB.chunk"
        assertEquals(
            "https://egdownload.fastly-edge.com/Builds/Org/o-x/y/default/ChunksV4/05/AA_BB.chunk",
            EpicDownloadManager.buildChunkUrl(cdn, path, false)
        )
        assertEquals(
            "https://egdownload.fastly-edge.com/Builds/Org/o-x/y/default/ChunksV4/05/AA_BB.chunk?f_token=ab/cd+ef==",
            EpicDownloadManager.buildChunkUrl(cdn, path, true)
        )
        val bare = EpicDownloadManager.CdnUrl("https://h", "/Builds/d", "")
        assertEquals("https://h/Builds/d/p.chunk", EpicDownloadManager.buildChunkUrl(bare, "p.chunk", true))
    }

    @Test
    fun authOrderTriesLearnedFormFirst() {
        assertArrayEquals(booleanArrayOf(false), EpicDownloadManager.chunkAuthOrder("", EpicDownloadManager.FORM_UNKNOWN))
        assertArrayEquals(booleanArrayOf(false, true), EpicDownloadManager.chunkAuthOrder("?a=1", EpicDownloadManager.FORM_UNKNOWN))
        assertArrayEquals(booleanArrayOf(false, true), EpicDownloadManager.chunkAuthOrder("?a=1", EpicDownloadManager.FORM_PLAIN))
        assertArrayEquals(booleanArrayOf(true, false), EpicDownloadManager.chunkAuthOrder("?a=1", EpicDownloadManager.FORM_SIGNED))
    }

    @Test
    fun engineStatusIsExtracted() {
        assertEquals(
            404,
            EpicDownloadManager.httpStatusFromEngineError("epic app=X: item 9 failed after 5 attempts: unexpected HTTP status (404)")
        )
        assertNull(EpicDownloadManager.httpStatusFromEngineError("connection reset"))
        assertNull(EpicDownloadManager.httpStatusFromEngineError(null))
    }

    @Test
    fun failureReasonNamesTheRealStatus() {
        assertEquals(
            "chunk download refused by CDN (HTTP 404)",
            EpicDownloadManager.chunkFailureReason(listOf(404, 404), null, 3, 0)
        )
        assertEquals(
            "chunk download refused by CDN (HTTP 404, 403)",
            EpicDownloadManager.chunkFailureReason(listOf(404, 403), null, 3, 0)
        )
        assertEquals(
            "chunk download refused by CDN (HTTP 404)",
            EpicDownloadManager.chunkFailureReason(emptyList(), "item 9 failed after 5 attempts: unexpected HTTP status (404)", 1, 0)
        )
        assertEquals(
            "chunk download failed (native engine: disk full)",
            EpicDownloadManager.chunkFailureReason(emptyList(), "disk full", 1, 0)
        )
        assertEquals(
            "encrypted chunk could not be decrypted (key missing or wrong)",
            EpicDownloadManager.chunkFailureReason(emptyList(), null, 2, 2)
        )
        assertEquals(
            "5 chunk(s) could not be downloaded from any CDN",
            EpicDownloadManager.chunkFailureReason(emptyList(), null, 5, 0)
        )
    }

    @Test
    fun chunkSecretsAreNormalised() {
        val json = """{"manifests":[],"secrets":{"aabbccdd-11223344-55667788-99000011":"0011","AA":""}}"""
        val s = EpicDownloadManager.parseChunkSecrets(json)
        assertEquals("0011", s["AABBCCDD112233445566778899000011"])
        assertEquals(1, s.size)
        assertTrue(EpicDownloadManager.parseChunkSecrets("{}").isEmpty())
        assertTrue(EpicDownloadManager.parseChunkSecrets(null).isEmpty())
    }

    @Test
    fun encryptedChunkRoundTrips() {
        val key = ByteArray(16) { (it * 7 + 1).toByte() }
        val sha = ByteArray(20) { (it + 3).toByte() }            // nonce = first 12 bytes
        val secretGuid = intArrayOf(0x01020304, 0x05060708, 0x090A0B0C, 0x0D0E0F10)
        val plainText = deflate(ByteArray(5000) { (it % 251).toByte() })

        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, sha.copyOfRange(0, 12))
        )
        val ctAndTag = c.doFinal(plainText)
        val cipherText = ctAndTag.copyOfRange(0, ctAndTag.size - 16)
        val tag = ctAndTag.copyOfRange(ctAndTag.size - 16, ctAndTag.size)

        // Header bytes after the first 41: sha(20) hashType(1) uncompressed(4) secretGuid(16) tag(16)
        val extra = ByteBuffer.allocate(57).order(ByteOrder.LITTLE_ENDIAN)
        extra.put(sha).put(3.toByte()).putInt(5000)
        for (g in secretGuid) extra.putInt(g)
        extra.put(tag)

        val keyId = secretGuid.joinToString("") { String.format("%08X", it) }
        val hex = key.joinToString("") { String.format("%02x", it) }
        val secrets = mapOf(keyId to hex)

        val dec = EpicDownloadManager.decryptChunkPayload(cipherText, extra.array(), secrets)
        assertArrayEquals(plainText, dec)
        assertEquals(5000, EpicDownloadManager.inflateAll(dec!!)!!.size)

        // Wrong / missing key -> null (no exception).
        assertNull(EpicDownloadManager.decryptChunkPayload(cipherText, extra.array(), emptyMap()))
        val bad = mapOf(keyId to "00".repeat(16))
        assertNull(EpicDownloadManager.decryptChunkPayload(cipherText, extra.array(), bad))
        // Short header -> null.
        assertNull(EpicDownloadManager.decryptChunkPayload(cipherText, ByteArray(10), secrets))
    }

    @Test
    fun inflateAllRejectsGarbage() {
        assertNull(EpicDownloadManager.inflateAll(byteArrayOf(1, 2, 3, 4, 5)))
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}
