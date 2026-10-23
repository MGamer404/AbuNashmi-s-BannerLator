package com.winlator.star.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Coverage for [GameRecommendations]' evidence ranking, on the DiRT Showdown (elamigos repack) layout
 * seen on device: the install script runs DirectX (by its cabs) and OpenAL, the exe imports
 * d3dx11/d3dcompiler/OpenAL/XInput, and the repack's `_Redist` carries a generic bundle the game
 * never loads. Pure JVM; no Context, so only the game's files are read.
 */
class GameRecommendationsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(root: File, rel: String, bytes: ByteArray = "x".toByteArray()): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    /** A minimal PE32 whose import table names [dlls]. */
    private fun pe(dlls: List<String>): ByteArray {
        val raw = 0x200
        val va = 0x1000
        val out = ByteBuffer.allocate(raw + 0x400).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0, 0x5A4D)
        out.putInt(0x3C, 0x40)
        out.putInt(0x40, 0x00004550)
        out.putShort(0x44, 0x14C)
        out.putShort(0x46, 1)
        out.putShort(0x54, 224)
        val opt = 0x58
        out.putShort(opt, 0x10B)
        out.putInt(opt + 28, 0x400000)
        out.putInt(opt + 92, 16)
        out.putInt(opt + 96 + 8, va) // import directory
        out.putInt(opt + 96 + 12, (dlls.size + 1) * 20)
        val sec = opt + 224
        ".idata".toByteArray().forEachIndexed { i, b -> out.put(sec + i, b) }
        out.putInt(sec + 8, 0x400)
        out.putInt(sec + 12, va)
        out.putInt(sec + 16, 0x400)
        out.putInt(sec + 20, raw)
        var name = (dlls.size + 1) * 20
        dlls.forEachIndexed { i, dll ->
            out.putInt(raw + i * 20 + 12, va + name)
            out.putInt(raw + i * 20 + 16, va + 0x300)
            dll.toByteArray().forEachIndexed { j, b -> out.put(raw + name + j, b) }
            name += dll.length + 1
        }
        return out.array()
    }

    @Test
    fun peImports_readsTheImportTable() {
        val f = touch(tmp.root, "a.exe", pe(listOf("KERNEL32.dll", "d3dx11_43.dll")))
        val info = PeImports.read(f)!!
        assertFalse(info.is64)
        assertEquals(listOf("kernel32.dll", "d3dx11_43.dll"), info.imports)
    }

    @Test
    fun dirtShowdown_scriptAndImportsRecommend_repackRedistIsFolderOnly() {
        val g = tmp.newFolder("DiRT Showdown")
        touch(g, "install.vdf", """
            "InstallScript"
            {
                "Run Process"
                {
                    "DirectX"
                    {
                        "HasRunKey" "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\201700"
                        "process 1" "%INSTALLDIR%\\support\\DirectX\\DXSETUP.exe"
                        "command 1" "/silent"
                    }
                    "OpenAL" { "process 1" "%INSTALLDIR%\\support\\OpenAL\\OpenALwEAX.exe" }
                    "Rapture3D" { "process 1" "%INSTALLDIR%\\support\\Rapture3D\\rapture3d.exe" }
                    "GDF" { "process 1" "%INSTALLDIR%\\support\\game_gdf_install.exe" }
                }
            }
        """.trimIndent().toByteArray())
        listOf(
            "APR2007_xinput_x86.cab", "Jun2010_D3DCompiler_43_x86.cab", "Jun2010_XACT_x86.cab", "Jun2010_XAudio_x86.cab",
            "Jun2010_d3dcsx_43_x86.cab", "Jun2010_d3dx10_43_x86.cab", "Jun2010_d3dx11_43_x86.cab", "Jun2010_d3dx9_43_x86.cab",
            "dxdllreg_x86.cab", "dxupdate.cab", "DXSETUP.exe",
        ).forEach { touch(g, "support/DirectX/$it") }
        touch(g, "support/OpenAL/OpenALwEAX.exe")
        touch(g, "support/Rapture3D/rapture3d.exe")
        touch(g, "support/game_gdf_install.exe")
        listOf(
            "dxwebsetup.exe", "oalinst.exe", "vcredist_2015-2019_x64.exe", "vcredist_2015-2019_x86.exe",
            "vcredist_x64.exe", "vcredist_x86.exe", "xnafx40_redist.msi", "dotNetFx40_Full_setup.exe",
        ).forEach { touch(g, "_Redist/$it") }
        touch(g, "_CommonRedist/.keep")
        touch(g, "elamigos.jpg")
        touch(g, "dbxLive32.dll")
        val exe = touch(g, "showdown.exe", pe(listOf(
            "ADVAPI32.dll", "D3DCOMPILER_43.dll", "DINPUT8.dll", "KERNEL32.dll", "OpenAL32.dll", "USER32.dll",
            "WINHTTP.dll", "XINPUT1_3.dll", "binkw32.dll", "d3d11.dll", "d3dx11_43.dll", "steam_api.dll",
        )))
        touch(g, "binkw32.dll", pe(listOf("KERNEL32.dll")))
        touch(g, "steam_api.dll", pe(listOf("KERNEL32.dll")))

        val recs = GameRecommendations.detect(exe, g)
        fun group(g: GameRecommendations.Group) = recs.filter { GameRecommendations.group(it) == g }.map { it.componentName }

        assertEquals(listOf("d3dx9", "d3dx11", "d3dcompiler_43", "xact", "xaudio2.7", "oalinst"), group(GameRecommendations.Group.RECOMMENDED))
        assertEquals(listOf("XLiveRedist"), group(GameRecommendations.Group.OPTIONAL))
        // The repack's generic bundle: offered folded away, never recommended (the VC++ year is read from
        // the path, so only "some VC++" is asserted - the temp folder's digits can't skew the check).
        val folder = group(GameRecommendations.Group.FOLDER)
        assertTrue(folder.containsAll(listOf("dotnet40", "xna40")))
        assertTrue(folder.any { it.startsWith("vcredist") })
        assertEquals(3, folder.size)
        assertFalse("XInput is the app's own", recs.any { it.componentName == "xinput" })
        assertFalse("only the x86 XACT cab is there", recs.any { it.componentName == "xact_x64" })
        assertEquals("Steam's install script runs it", recs.first { it.componentName == "d3dx11" }.reason)
        assertTrue(GameRecommendations.reason(recs.first { it.componentName == "dotnet40" }).contains("repack"))
    }

    @Test
    fun imports_mapToTheirRuntimes() {
        assertEquals(listOf("vcredist2010"), GameRecommendations.importComponents("MSVCR100.dll", false))
        assertEquals(listOf("vcredist2022"), GameRecommendations.importComponents("vcruntime140_1.dll", true))
        assertEquals(listOf("vcredist2022"), GameRecommendations.importComponents("msvcp140.dll", true))
        assertEquals(listOf("mfc42"), GameRecommendations.importComponents("MFC42.DLL", false))
        assertEquals(listOf("xact_x64"), GameRecommendations.importComponents("X3DAudio1_7.dll", true))
        assertTrue(GameRecommendations.importComponents("xinput1_3.dll", false).isEmpty())
        assertTrue(GameRecommendations.importComponents("d3dcompiler_47.dll", true).isEmpty())
        assertTrue(GameRecommendations.importComponents("xaudio2_9.dll", true).isEmpty())
        assertTrue(GameRecommendations.importComponents("msvcrt.dll", false).isEmpty())
    }
}
