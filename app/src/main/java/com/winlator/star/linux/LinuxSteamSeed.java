package com.winlator.star.linux;

import android.content.Context;
import android.util.Log;

import com.winlator.star.contents.Downloader;
import com.winlator.star.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * Valve's Proton Experimental (ARM64), placed over the runtime before the Steam client has ever
 * started - so the client finds it installed at its first sign-in, the registrar makes it the
 * default before anyone signs in, and the first game installed maps to it with nothing to fetch.
 *
 * <p>The client will not download a depot before a sign-in, so the first session used to ask for
 * it on the command line, wait for the download, then restart the client once it had landed
 * (bannerlator-session). The package here is that depot's files as the client keeps them, plus
 * its {@code appmanifest_4427310.acf} (last in the tar, {@code LastOwner 0}): with the manifest
 * present the client treats the tool as its own - it verified nothing, prompted for nothing and
 * fetched a delta update when the depot moved on (device-proven in DroidDeck, 2026-09-26).
 * The session script then skips its whole install-and-restart block.
 *
 * <p>Once only. The manifest counts as much as the marker, so a Proton the client fetched itself
 * is never overwritten, and after the first placement the client owns it: a user who removes it
 * from the client is not given it back. Everything here is best effort - a failure is logged and
 * the session goes on, back to the client fetching the depot as before.
 *
 * <p>The catalog is a file of its own beside the other component catalogs, so the row never shows
 * among the packages a user picks from. (From The412Banner/DroidDeck.)
 */
public final class LinuxSteamSeed {
    private static final String TAG = "LinuxSteamSeed";

    public static final String CATALOG_URL =
            "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/steam-seed.json";
    public static final String PROTON_ID = "proton-arm64";
    public static final String PROTON_NAME = "Proton Experimental (ARM64)";

    /** The client's own manifest for the ARM64 Proton depot; its presence means installed. */
    static final String PROTON_MANIFEST = "root/.local/share/Steam/steamapps/appmanifest_4427310.acf";
    /**
     * Holds the placed build. Under the guest's home rather than the rootfs top level because a
     * runtime update replaces the system and carries only {@code root/} across
     * ({@link LinuxRuntimeInstaller}): kept there, "placed once" stays true past an update.
     */
    static final String PROTON_MARKER = "root/.bannerlator-pkg-" + PROTON_ID;

    /** One catalog row: the tar.zst over the rootfs, and what it must hash to. */
    public static final class Entry {
        public final String id;
        public final String name;
        public final String version;
        public final String url;
        public final String sha256;
        public final long size;

        Entry(JSONObject o) {
            id = o.optString("id", "");
            name = o.optString("name", "");
            version = o.optString("version", "");
            url = o.optString("url", "");
            sha256 = o.optString("sha256", "").toLowerCase(Locale.ROOT);
            size = o.optLong("size", 0L);
        }
    }

    private LinuxSteamSeed() {}

    /** The build placed here, or null when this app never placed one. */
    public static String placedVersion(Context context) {
        File marker = new File(LinuxRuntime.rootDir(context), PROTON_MARKER);
        if (!marker.isFile()) return null;
        try {
            String v = FileUtils.readString(marker);
            return v == null || v.trim().isEmpty() ? null : v.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True until the ARM64 Proton has been placed once, by this app or by the client itself. */
    public static boolean protonNeeded(Context context) {
        return placedVersion(context) == null
                && !new File(LinuxRuntime.rootDir(context), PROTON_MANIFEST).isFile();
    }

    /** The catalog's Proton row, or null when the catalog cannot be reached or has none. */
    public static Entry fetchProton() {
        String body = Downloader.downloadString(CATALOG_URL);
        if (body == null || body.isEmpty()) return null;
        try {
            JSONArray rows = new JSONObject(body).optJSONArray("packages");
            if (rows == null) return null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null || !PROTON_ID.equals(o.optString("id", ""))) continue;
                // Only a tar is laid over the rootfs; anything else is not this feature.
                if (!"tar".equals(o.optString("kind", "tar"))) continue;
                Entry e = new Entry(o);
                if (e.url.isEmpty() || e.version.isEmpty()) continue;
                return e;
            }
        } catch (Exception e) {
            Log.w(TAG, "catalog: " + e);
        }
        return null;
    }

    /**
     * Downloads, verifies and lays {@code entry} over the rootfs, then writes the marker. Returns
     * null on success, or a short reason. Worker thread: this is a 400 MB download and a
     * 2 GB unpack, and {@code listener} is the loading screen.
     */
    public static String install(Context context, Entry entry, LinuxRuntimeInstaller.ProgressListener listener) {
        File root = LinuxRuntime.rootDir(context);
        if (!LinuxRuntime.isInstalled(context)) return "the Linux runtime is not installed";
        // A row without a checksum is refused rather than trusted: this lands under the client's
        // own library, and the client verifies none of it.
        if (entry.sha256.isEmpty()) return "the catalog has no checksum for " + entry.name;
        // Room for the archive and the tree it unpacks to, which runs to five times the packed
        // size. Refusing here beats filling the device and failing mid-unpack.
        long need = entry.size > 0 ? entry.size * 6 : 3L << 30;
        if (root.getUsableSpace() < need) {
            return "not enough free space (needs about " + ((need + (1L << 29)) >> 30) + " GB)";
        }
        File download = new File(context.getCacheDir(), "pkg-" + entry.id + ".download");
        final long totalMb = entry.size / 1_000_000L;
        try {
            if (listener != null) listener.onProgress(downloading(entry, 0, totalMb), 0);
            // Resumed: a session started on a poor link leaves a partial behind on purpose (below),
            // and the next one carries on from it rather than starting the 400 MB over.
            boolean ok = Downloader.downloadFile(entry.url, download, true, (fraction) -> {
                if (listener == null) return;
                if (fraction < 0) {
                    listener.onProgress("downloading " + entry.name, -1);
                } else {
                    long doneMb = (long) (fraction * (double) entry.size) / 1_000_000L;
                    listener.onProgress(downloading(entry, doneMb, totalMb), Math.round(fraction * 100f));
                }
            });
            if (!ok) {
                Log.w(TAG, "download failed; the partial is kept for a resume next session");
                return "the download failed";
            }
            if (listener != null) listener.onProgress("checking " + entry.name, -1);
            String actual = LinuxRuntimeInstaller.sha256(download);
            if (!entry.sha256.equalsIgnoreCase(actual)) {
                //noinspection ResultOfMethodCallIgnored
                download.delete();
                Log.w(TAG, "checksum mismatch: wanted " + entry.sha256 + ", got " + actual);
                return "the download did not match its checksum";
            }
            // Over the live rootfs: the same extractor the runtime uses, which keeps the symlinks,
            // hard links and executable bits a Proton tree is full of. Files land writable, owned
            // by the app, so the client's later updates can rewrite them.
            if (listener != null) listener.onProgress("placing " + entry.name, -1);
            LinuxRuntimeInstaller.ProgressListener placing = listener == null ? null
                    : (stage, percent) -> listener.onProgress("placing " + entry.name, -1);
            if (!LinuxRuntimeInstaller.extract(download, root, placing)) {
                //noinspection ResultOfMethodCallIgnored
                download.delete();
                return "the package could not be unpacked";
            }
            if (!FileUtils.writeString(new File(root, PROTON_MARKER), entry.version)) {
                Log.w(TAG, "placed, but the marker could not be written");
            }
            //noinspection ResultOfMethodCallIgnored
            download.delete();
            Log.i(TAG, "placed " + entry.name + " " + entry.version);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "install " + entry.id, e);
            //noinspection ResultOfMethodCallIgnored
            download.delete();
            return e.getMessage() == null ? "the install failed" : e.getMessage();
        }
    }

    /** "downloading Proton Experimental (ARM64) · 120 of 398 MB". */
    private static String downloading(Entry entry, long doneMb, long totalMb) {
        if (totalMb <= 0) return "downloading " + entry.name;
        return String.format(Locale.US, "downloading %s · %d of %d MB", entry.name, doneMb, totalMb);
    }
}
