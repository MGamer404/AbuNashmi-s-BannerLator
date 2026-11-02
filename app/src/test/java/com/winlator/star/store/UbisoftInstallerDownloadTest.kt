package com.winlator.star.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** The pure parts of [UbisoftInstallerDownload]: what counts as a whole, trustworthy download, and where redirects may go. */
class UbisoftInstallerDownloadTest {

    private val mz = byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0x90.toByte())
    private val mb = 1024L * 1024L

    @Test
    fun onlyAnMzHeaderLooksLikeAWindowsProgram() {
        assertTrue(UbisoftInstallerDownload.hasMzHeader(mz))
        assertFalse(UbisoftInstallerDownload.hasMzHeader("<html>".toByteArray()))
        assertFalse(UbisoftInstallerDownload.hasMzHeader(byteArrayOf('M'.code.toByte())))
        assertFalse(UbisoftInstallerDownload.hasMzHeader(ByteArray(0)))
    }

    @Test
    fun theSizeMustEqualAKnownContentLength() {
        assertTrue(UbisoftInstallerDownload.sizeMatches(262_918_936L, 262_918_936L))
        assertFalse(UbisoftInstallerDownload.sizeMatches(262_918_936L, 262_918_935L))
        assertFalse(UbisoftInstallerDownload.sizeMatches(-1L, -1L))
        assertFalse(UbisoftInstallerDownload.sizeMatches(0L, 0L))
    }

    @Test
    fun theDiskNeedsRoomForTheFileAndItsUnpacking() {
        assertTrue(UbisoftInstallerDownload.hasEnoughSpace(400 * mb, 251 * mb))
        assertFalse(UbisoftInstallerDownload.hasEnoughSpace(300 * mb, 251 * mb))
    }

    @Test
    fun aKeptFileIsReusedOnlyWhenItIsBigAndAnExecutable() {
        assertTrue(UbisoftInstallerDownload.isPlausibleInstaller(251 * mb, mz))
        assertFalse(UbisoftInstallerDownload.isPlausibleInstaller(1 * mb, mz))
        assertFalse(UbisoftInstallerDownload.isPlausibleInstaller(251 * mb, "<html>".toByteArray()))
        assertFalse(UbisoftInstallerDownload.isPlausibleInstaller(251 * mb, ByteArray(0)))
    }

    @Test
    fun aFullDiskIsRecognisedFromTheErrorText() {
        assertTrue(UbisoftInstallerDownload.isNoSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(UbisoftInstallerDownload.isNoSpace(IOException("No space left on device")))
        assertFalse(UbisoftInstallerDownload.isNoSpace(IOException("Connection reset")))
        assertFalse(UbisoftInstallerDownload.isNoSpace(IOException()))
    }

    @Test
    fun redirectsStayOnTheSameHostOverHttps() {
        val from = UbisoftInstallerDownload.INSTALLER_URL
        assertEquals(
            "https://static3.cdn.ubi.com/orbit/other/UbisoftConnectInstaller.exe",
            UbisoftInstallerDownload.redirectTarget(from, "/orbit/other/UbisoftConnectInstaller.exe"),
        )
        assertEquals(
            "https://STATIC3.cdn.ubi.com/x.exe",
            UbisoftInstallerDownload.redirectTarget(from, "https://STATIC3.cdn.ubi.com/x.exe"),
        )
        assertNull(UbisoftInstallerDownload.redirectTarget(from, "https://evil.example.com/x.exe"))
        assertNull(UbisoftInstallerDownload.redirectTarget(from, "http://static3.cdn.ubi.com/x.exe"))
        assertNull(UbisoftInstallerDownload.redirectTarget(from, "ftp://static3.cdn.ubi.com/x.exe"))
        assertNull(UbisoftInstallerDownload.redirectTarget(from, ""))
        assertNull(UbisoftInstallerDownload.redirectTarget(from, null))
    }

    @Test
    fun theFixedUrlIsHttpsAndTheWinePathIsUnderTheContainersCDrive() {
        assertTrue(UbisoftInstallerDownload.INSTALLER_URL.startsWith("https://"))
        assertEquals(
            "C:\\bl_installscript\\ubisoft_installer\\UbisoftConnectInstaller.exe",
            UbisoftInstallerDownload.windowsPathOf(File("/data/c/.wine/drive_c/bl_installscript/ubisoft_installer/UbisoftConnectInstaller.exe")),
        )
    }
}
