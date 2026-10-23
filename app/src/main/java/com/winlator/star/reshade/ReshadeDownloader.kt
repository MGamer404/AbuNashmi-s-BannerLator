package com.winlator.star.reshade

import android.content.Context
import android.util.Log
import com.winlator.star.contents.Downloader
import com.winlator.star.core.TarCompressorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Downloads a ReShade effect archive (the entry's explicit "url" — a GitHub release asset) and
 * installs it into the drop-in folder, so ReshadeManager's scanner then picks it up exactly like a
 * hand-dropped effect. Reuses the same primitives the rest of the app uses for content:
 * Downloader.downloadFile (HTTP + progress) and TarCompressorUtils.extract (zstd tar), rather than
 * the heavyweight ContentProfile/DownloadCoordinator pipeline (these effect archives are small and
 * not versioned content the container tracks).
 *
 * The .tzst's tar already contains the "<id>/..." folder, so it extracts into the ReShade ROOT dir
 * (getExternalFilesDir/ReShade/) → yielding ReShade/<id>/. The published catalog supplies an
 * UPPERCASE MD5 (file_checksum) which is verified before extraction.
 */
object ReshadeDownloader {
    private const val TAG = "ReshadeDownloader"

    enum class Phase { DOWNLOAD, EXTRACT }

    /** Download + verify + extract [entry] into the drop-in folder. progress(phase, 0..1). Returns
     *  true on success (the effect is then on disk under getReshadeDir/<id>/). Runs on IO. */
    suspend fun install(
        context: Context,
        entry: ReshadeCatalogEntry,
        progress: (Phase, Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "reshade_dl").apply { mkdirs() }
        val archive = File(cacheDir, "${safe(entry.id)}.tzst")
        try {
            if (!Downloader.downloadFile(entry.url, archive) { f -> progress(Phase.DOWNLOAD, f.coerceIn(0f, 1f)) }) {
                Log.w(TAG, "download failed: ${entry.url}")
                return@withContext false
            }
            // Verify the UPPERCASE MD5 from the catalog before trusting the archive (blank = skip).
            if (entry.checksum.isNotBlank()) {
                val actual = md5Upper(archive)
                if (!actual.equals(entry.checksum, ignoreCase = true)) {
                    Log.w(TAG, "checksum mismatch for ${entry.id}: expected ${entry.checksum} got $actual")
                    return@withContext false
                }
            }
            progress(Phase.EXTRACT, 0f)
            // The tar carries "<id>/...", so extract straight into the ReShade root → ReShade/<id>/.
            val reshadeRoot = ReshadeManager.getReshadeDir(context)
            // Replace any stale copy so a re-download is clean.
            File(reshadeRoot, entry.id).takeIf { it.isDirectory }?.deleteRecursively()
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, reshadeRoot)) {
                Log.w(TAG, "extract failed: $archive")
                return@withContext false
            }
            progress(Phase.EXTRACT, 1f)
            // Sanity: a usable effect must end up with at least one .fx under ReShade/<id>/.
            val ok = ReshadeManager.findFxFile(File(reshadeRoot, entry.id)) != null
            if (!ok) Log.w(TAG, "no .fx after install for ${entry.id}")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "install failed for ${entry.id}", t)
            false
        } finally {
            archive.delete()
        }
    }

    private fun md5Upper(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02X".format(it) }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
