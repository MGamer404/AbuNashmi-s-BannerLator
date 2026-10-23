package com.winlator.star.components

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * The DLLs a Windows binary loads by name: its PE import table and delay-load table (32- and
 * 64-bit), and, for a .NET program, the CLR version its metadata asks for and whether it uses XNA.
 * Reads only the headers and the few bytes the tables point at, never the whole file. Pure Kotlin,
 * no Android deps; never throws (null for anything that is not a readable PE).
 */
object PeImports {
    /** [imports] lowercased DLL names, in table order; [clrVersion] e.g. "v4.0.30319" for a .NET program. */
    class Info(val is64: Boolean, val imports: List<String>, val clrVersion: String?, val usesXna: Boolean)

    private const val MAX_DLLS = 512
    private const val MAX_NAME = 260
    /** A .NET program's metadata is searched for XNA up to this much. */
    private const val MAX_METADATA = 8L shl 20

    private class Section(val va: Long, val size: Long, val raw: Long, val rawSize: Long)

    fun read(file: File): Info? = try {
        RandomAccessFile(file, "r").use { read(it) }
    } catch (_: Throwable) {
        null
    }

    private fun read(f: RandomAccessFile): Info? {
        val len = f.length()
        if (len < 0x40) return null
        if (u16(f, 0) != 0x5A4DL) return null // "MZ"
        val pe = u32(f, 0x3C)
        if (pe <= 0 || pe + 24 > len || u32(f, pe) != 0x00004550L) return null // "PE\0\0"
        val sectionCount = u16(f, pe + 6).toInt()
        val optSize = u16(f, pe + 20)
        val opt = pe + 24
        val magic = u16(f, opt)
        val is64 = when (magic) { 0x20BL -> true; 0x10BL -> false; else -> return null }
        val imageBase = if (is64) u64(f, opt + 24) else u32(f, opt + 28)
        val dirCount = u32(f, opt + if (is64) 108 else 92)
        val dirs = opt + if (is64) 112 else 96
        fun dir(i: Int): Pair<Long, Long> = if (i < dirCount) u32(f, dirs + i * 8L) to u32(f, dirs + i * 8L + 4) else 0L to 0L

        val sections = ArrayList<Section>(sectionCount)
        val table = opt + optSize
        for (i in 0 until sectionCount.coerceAtMost(96)) {
            val s = table + i * 40L
            if (s + 40 > len) break
            sections += Section(u32(f, s + 12), u32(f, s + 8), u32(f, s + 20), u32(f, s + 16))
        }
        fun offset(rva: Long): Long? {
            if (rva <= 0) return null
            for (s in sections) {
                val span = maxOf(s.size, s.rawSize)
                if (rva >= s.va && rva < s.va + span) {
                    val off = s.raw + (rva - s.va)
                    return off.takeIf { it < len && rva - s.va < s.rawSize }
                }
            }
            return null
        }

        val names = LinkedHashSet<String>()
        // Import descriptors: 20 bytes each, Name at +12, ended by an all-zero entry.
        offset(dir(1).first)?.let { at ->
            for (i in 0 until MAX_DLLS) {
                val d = at + i * 20L
                if (d + 20 > len) break
                val nameRva = u32(f, d + 12)
                if (nameRva == 0L && u32(f, d) == 0L && u32(f, d + 16) == 0L) break
                offset(nameRva)?.let { cstring(f, it) }?.let { names += it.lowercase(Locale.US) }
            }
        }
        // Delay-load descriptors: 32 bytes each, DllName at +4 (an RVA, or a VA when Attributes bit 0 is clear).
        offset(dir(13).first)?.let { at ->
            for (i in 0 until MAX_DLLS) {
                val d = at + i * 32L
                if (d + 32 > len) break
                val attrs = u32(f, d)
                val name = u32(f, d + 4)
                if (name == 0L) break
                val rva = if ((attrs and 1L) == 0L && name >= imageBase && imageBase > 0) name - imageBase else name
                offset(rva)?.let { cstring(f, it) }?.let { names += it.lowercase(Locale.US) }
            }
        }

        // CLR header (data directory 14) -> metadata root: "BSJB", version string at +16.
        var clr: String? = null
        var xna = false
        offset(dir(14).first)?.let { cli ->
            val mdRva = u32(f, cli + 8)
            val mdSize = u32(f, cli + 12)
            offset(mdRva)?.let { md ->
                if (u32(f, md) == 0x424A5342L) {
                    val vlen = u32(f, md + 12).coerceIn(0L, 255L)
                    clr = cstring(f, md + 16, vlen.toInt())
                    xna = contains(f, md, mdSize.coerceAtMost(MAX_METADATA), "Microsoft.Xna.Framework".toByteArray())
                }
            }
        }
        return Info(is64, names.toList(), clr, xna)
    }

    private fun u16(f: RandomAccessFile, at: Long): Long {
        if (at < 0 || at + 2 > f.length()) return 0
        f.seek(at)
        val b0 = f.read(); val b1 = f.read()
        return ((b1 shl 8) or b0).toLong() and 0xFFFF
    }

    private fun u32(f: RandomAccessFile, at: Long): Long {
        if (at < 0 || at + 4 > f.length()) return 0
        f.seek(at)
        val b = ByteArray(4)
        f.readFully(b)
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun u64(f: RandomAccessFile, at: Long): Long = u32(f, at) or (u32(f, at + 4) shl 32)

    /** A NUL-terminated ASCII string at [at], at most [max] bytes; null when empty or not printable. */
    private fun cstring(f: RandomAccessFile, at: Long, max: Int = MAX_NAME): String? {
        if (at < 0 || at >= f.length()) return null
        f.seek(at)
        val buf = ByteArray(minOf(max.toLong(), f.length() - at).toInt())
        val n = f.read(buf)
        if (n <= 0) return null
        var end = 0
        while (end < n && buf[end] != 0.toByte()) end++
        if (end == 0) return null
        val s = String(buf, 0, end, Charsets.US_ASCII)
        return s.takeIf { t -> t.all { it.code in 0x20..0x7E } }
    }

    /** Whether [needle] occurs in the [size] bytes at [at], read in blocks. */
    private fun contains(f: RandomAccessFile, at: Long, size: Long, needle: ByteArray): Boolean {
        val end = minOf(at + size, f.length())
        val block = ByteArray(64 * 1024)
        var pos = at
        while (pos < end) {
            f.seek(pos)
            val n = f.read(block, 0, minOf(block.size.toLong(), end - pos).toInt())
            if (n <= 0) return false
            outer@ for (i in 0..n - needle.size) {
                for (j in needle.indices) if (block[i + j] != needle[j]) continue@outer
                return true
            }
            if (n < needle.size) return false
            pos += (n - needle.size + 1) // overlap so a match across blocks is seen
        }
        return false
    }
}
