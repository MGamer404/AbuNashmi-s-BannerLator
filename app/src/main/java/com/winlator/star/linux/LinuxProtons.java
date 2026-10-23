package com.winlator.star.linux;

import android.content.Context;
import android.util.Log;

import com.winlator.star.contents.Downloader;
import com.winlator.star.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Proton builds a Linux session can run a Windows game with, as a catalog beside the other
 * component catalogs.
 *
 * <p>Two different things are listed, because two different things exist. A <b>tarball</b> row is a
 * build somebody publishes as a file - GE-Proton and proton-cachyos both ship native ARM64 ones -
 * and this class downloads it. A <b>depot</b> row is one of Valve's own, which is not a file
 * anywhere: the Steam client fetches it as an ordinary app, so the row only asks the client to
 * install it and otherwise reports what is already there.
 *
 * <p>Unpacking is deliberately not done here. The app downloads, because it has the progress bar
 * and the user is looking at it; the session unpacks and registers at its next start, because that
 * is where {@code bannerlator-proton-extra} and the registrar live and where a tool has to be
 * adopted anyway - its own manifest asks for a container Android cannot provide. A downloaded row
 * therefore reads "ready, starts next session" rather than "installed", which is the truth.
 */
public final class LinuxProtons {
    private static final String TAG = "LinuxProtons";

    /** Catalog row, beside the other component catalogs in winlator-contents. */
    public static final String CATALOG_URL =
            "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linux-protons.json";

    /** Guest paths, relative to the runtime root; the session sees these under /root. */
    private static final String STEAM = "root/.local/share/Steam";
    private static final String TOOLS = STEAM + "/compatibilitytools.d";
    private static final String STAGING = TOOLS + "/.bannerlator-download";
    /** One set of bannerlator-proton-extra arguments per line; read before the client starts. */
    private static final String REQUESTS = "root/.bl-proton-extra";
    /** One steam:// URL per line, handed to the client on its command line at next start. */
    private static final String URLS = "root/.bl-steam-urls";

    public static final String KIND_TARBALL = "tarball";
    public static final String KIND_DEPOT = "depot";

    public interface ProgressListener {
        /** {@code percent} is -1 while the size is unknown. */
        void onProgress(String stage, int percent);
    }

    public static final class Build {
        public final String name;
        public final String display;
        public final String kind;
        /** Tarball rows: where to get it, how big, and what it must hash to. */
        public final String url;
        public final String sha512;
        public final long size;
        /** The directory the tarball unpacks to, which is also the name shown in Steam. */
        public final String dir;
        /** Depot rows: the app id the client installs. */
        public final String appId;
        /** Wine major, for anything that has to match a Proton's ABI. 0 when not stated. */
        public final int wine;
        public final String notes;

        Build(JSONObject o) {
            name = o.optString("name", "");
            display = o.optString("display", name);
            kind = o.optString("kind", KIND_TARBALL);
            url = o.optString("url", "");
            sha512 = o.optString("sha512", "").toLowerCase(Locale.ROOT);
            size = o.optLong("size", 0L);
            dir = o.optString("dir", name);
            appId = o.optString("appid", "");
            wine = o.optInt("wine", 0);
            notes = o.optString("notes", "");
        }

        public boolean isDepot() { return KIND_DEPOT.equals(kind); }
    }

    /** What the user sees against a row. */
    public enum State {
        /** Unpacked and registered; Steam offers it. */
        INSTALLED,
        /** Downloaded, or asked for; it appears after the next Linux session starts. */
        PENDING,
        /** Nothing here yet. */
        ABSENT
    }

    private LinuxProtons() {}

    /** The catalog, or an empty list when it cannot be reached or read. */
    public static List<Build> fetchCatalog() {
        List<Build> builds = new ArrayList<>();
        String body = Downloader.downloadString(CATALOG_URL);
        if (body == null || body.isEmpty()) return builds;
        try {
            JSONArray rows = new JSONObject(body).optJSONArray("protons");
            if (rows == null) return builds;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) continue;
                Build b = new Build(o);
                // A row that names nothing cannot be acted on, and a tarball with no checksum
                // cannot be verified - neither is worth showing.
                if (b.name.isEmpty()) continue;
                if (!b.isDepot() && (b.url.isEmpty() || b.sha512.isEmpty())) continue;
                if (b.isDepot() && b.appId.isEmpty()) continue;
                builds.add(b);
            }
        } catch (Exception e) {
            Log.w(TAG, "catalog: " + e);
        }
        return builds;
    }

    public static State stateOf(Context context, Build build) {
        File root = LinuxRuntime.rootDir(context);
        if (build.isDepot()) {
            // The client writes a manifest for a depot exactly as it does for a game.
            File manifest = new File(root, STEAM + "/steamapps/appmanifest_" + build.appId + ".acf");
            if (manifest.isFile()) return State.INSTALLED;
            return requested(root, URLS, "steam://install/" + build.appId) ? State.PENDING : State.ABSENT;
        }
        // Unpacked is the only thing that counts as installed: the tool directory carries the
        // entry point the registrar wraps.
        if (new File(root, TOOLS + "/" + build.dir + "/toolmanifest.vdf").isFile()) {
            return State.INSTALLED;
        }
        File tarball = new File(root, STAGING + "/" + fileName(build));
        if (tarball.isFile() && requested(root, REQUESTS, guestTarball(build))) return State.PENDING;
        return State.ABSENT;
    }

    /**
     * Downloads {@code build} and queues it for the next session. Returns null on success, or a
     * sentence to show the user.
     */
    public static String install(Context context, Build build, ProgressListener listener) {
        File root = LinuxRuntime.rootDir(context);
        if (build.isDepot()) {
            // Nothing to fetch: the client owns this one. Asking for it is the whole operation.
            return append(new File(root, URLS), "steam://install/" + build.appId)
                    ? null : "Could not write the request.";
        }
        File staging = new File(root, STAGING);
        if (!staging.isDirectory() && !staging.mkdirs()) {
            return "Could not create the download folder — is the Linux runtime installed?";
        }
        // Room for the tarball and the tree it unpacks to, which runs to about three times the
        // packed size. Refusing here beats filling the device and failing mid-unpack.
        long need = build.size > 0 ? build.size * 4 : 4L << 30;
        if (staging.getUsableSpace() < need) {
            return "Not enough free space — needs about " + (need >> 30) + " GB.";
        }
        File tarball = new File(staging, fileName(build));
        if (listener != null) listener.onProgress("Downloading", -1);
        boolean ok = Downloader.downloadFile(build.url, tarball, true, (fraction) -> {
            if (listener != null) {
                listener.onProgress("Downloading", fraction < 0 ? -1 : Math.round(fraction * 100f));
            }
        });
        if (!ok) {
            return "Download failed.";
        }
        if (listener != null) listener.onProgress("Verifying", -1);
        String actual;
        try {
            actual = sha512(tarball);
        } catch (Exception e) {
            Log.w(TAG, "sha512", e);
            return "Could not check the download.";
        }
        if (!build.sha512.equalsIgnoreCase(actual)) {
            //noinspection ResultOfMethodCallIgnored
            tarball.delete();
            return "The download did not match its checksum and was deleted.";
        }
        if (!append(new File(root, REQUESTS), guestTarball(build))) {
            return "Downloaded, but the request could not be written.";
        }
        return null;
    }

    /**
     * Removes an unpacked build and anything queued for it. Valve's depots are the client's to
     * manage, so a depot row is never removed from here.
     */
    public static void remove(Context context, Build build) {
        if (build.isDepot()) return;
        File root = LinuxRuntime.rootDir(context);
        FileUtils.delete(new File(root, TOOLS + "/" + build.dir));
        //noinspection ResultOfMethodCallIgnored
        new File(root, STAGING + "/" + fileName(build)).delete();
        drop(new File(root, REQUESTS), guestTarball(build));
    }

    /** Bytes the unpacked trees are using, so the tab can say what they cost. */
    public static long installedBytes(Context context) {
        File tools = new File(LinuxRuntime.rootDir(context), TOOLS);
        File[] entries = tools.listFiles();
        if (entries == null) return 0L;
        long total = 0L;
        for (File entry : entries) {
            // Ours is built from the client's own depot and costs nothing; the staging directory
            // is counted by the tarballs still in it.
            if (entry.getName().startsWith(".")) continue;
            if (entry.isDirectory()) total += size(entry);
        }
        return total;
    }

    private static long size(File file) {
        if (file.isFile()) return file.length();
        File[] entries = file.listFiles();
        if (entries == null) return 0L;
        long total = 0L;
        for (File entry : entries) total += size(entry);
        return total;
    }

    private static String fileName(Build build) {
        String path = build.url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** The path the session sees, which is not the path the app wrote to. */
    private static String guestTarball(Build build) {
        return "/root/.local/share/Steam/compatibilitytools.d/.bannerlator-download/"
                + fileName(build);
    }

    /**
     * The file's text, or an empty string when it is not there.
     *
     * <p>{@link FileUtils#readString(File)} cannot be used directly: on a missing or unreadable
     * file the read underneath it returns null and the String constructor throws, so asking
     * whether a request file exists by reading it crashed the tab outright. Every file this class
     * reads is one that legitimately does not exist yet.
     */
    private static String readText(File file) {
        if (!file.isFile()) return "";
        try {
            String body = FileUtils.readString(file);
            return body == null ? "" : body;
        } catch (RuntimeException e) {
            Log.w(TAG, "read " + file + ": " + e);
            return "";
        }
    }

    private static boolean requested(File root, String relative, String line) {
        String body = readText(new File(root, relative));
        if (body.isEmpty()) return false;
        for (String each : body.split("\n")) {
            if (each.trim().equals(line)) return true;
        }
        return false;
    }

    /** Idempotent: a line already there is not added twice. */
    private static boolean append(File file, String line) {
        String body = readText(file);
        for (String each : body.split("\n")) {
            if (each.trim().equals(line)) return true;
        }
        if (!body.isEmpty() && !body.endsWith("\n")) body += "\n";
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        return FileUtils.writeString(file, body + line + "\n");
    }

    private static void drop(File file, String line) {
        String body = readText(file);
        if (body.isEmpty()) return;
        StringBuilder kept = new StringBuilder();
        for (String each : body.split("\n")) {
            if (each.trim().isEmpty() || each.trim().equals(line)) continue;
            kept.append(each).append('\n');
        }
        FileUtils.writeString(file, kept.toString());
    }

    private static String sha512(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 16)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
