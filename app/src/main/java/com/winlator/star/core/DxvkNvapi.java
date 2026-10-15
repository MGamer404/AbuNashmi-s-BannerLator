package com.winlator.star.core;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * The bundled dxvk-nvapi and its place in a prefix — what {@link UnrealHdr}'s DirectX 11 mode swaps in.
 * X11 and Wayland alike.
 *
 * <p><b>The bundle.</b> The APK carries one dxvk-nvapi build (jp7677/dxvk-nvapi, MIT; assets
 * {@code dxvk-nvapi/}: nvapi64.dll x64, nvapi.dll x86, manifest.json with the version and hashes,
 * LICENSE). CI fetches the dlls from the pinned upstream release and checks their sha256 (see
 * {@code _build.yml}); nothing binary is committed. x64/x86 builds go where DXVK's x86_64/x86 builds
 * go, arm64ec prefixes included: an x64 game loads system32's x64 dll under emulation.
 *
 * <p><b>The prefix.</b> While the mode is on, every launch makes sure the prefix's
 * system32\nvapi64.dll and syswow64\nvapi.dll are that build ({@link #sync}), and the session gets
 * {@code WINEDLLOVERRIDES} nvapi,nvapi64=n plus {@code DXVK_NVAPI_ALLOW_OTHER_DRIVERS=1} (dxvk-nvapi
 * otherwise refuses a Vulkan driver that isn't NVIDIA's). Before the first copy into a slot, whatever
 * file is there (Wine may ship an nvapi stub) is moved into {@code <prefix>/bannerlator-nvapi/backup/},
 * once. What was installed is recorded in {@code <prefix>/bannerlator-nvapi/installed.properties},
 * written before each file is put in place, and every copy lands through a temporary file and a
 * rename, so a launch that dies half-way leaves nothing the next launch can't finish or undo. With
 * the mode left, the next launch puts the prefix's own files back. A slot counts as "ours" only when
 * the file's sha256 is one we installed; a file that replaced ours since (a Wine update, a DXVK package
 * carrying its own) is never deleted on restore.
 */
public final class DxvkNvapi {
    private DxvkNvapi() {}

    private static final String TAG = "DxvkNvapi";

    /** Where the bundled build lives in the APK. */
    static final String ASSET_DIR = "dxvk-nvapi";

    public static final String ENV_DXVK_ENABLE_NVAPI = "DXVK_ENABLE_NVAPI";
    public static final String ENV_ALLOW_OTHER_DRIVERS = "DXVK_NVAPI_ALLOW_OTHER_DRIVERS";

    /** The DLLs the WINEDLLOVERRIDES entry covers. */
    static final String[] OVERRIDE_DLLS = {"nvapi", "nvapi64"};

    // ── The bundled build ────────────────────────────────────────────────────────────────────────

    /** One dll of the bundled build: its asset name, where it goes in the prefix, its sha256. */
    static final class Slot {
        final String asset;
        final String prefixPath; // relative to drive_c/windows, e.g. "system32/nvapi64.dll"
        final String sha256;     // lowercase hex, "" when the manifest has none

        Slot(String asset, String prefixPath, String sha256) {
            this.asset = asset;
            this.prefixPath = prefixPath;
            this.sha256 = sha256;
        }
    }

    /** The bundled build as manifest.json describes it, with every dll present in the APK. */
    public static final class Bundle {
        public final String version;
        final List<Slot> slots;

        Bundle(String version, List<Slot> slots) {
            this.version = version;
            this.slots = slots;
        }
    }

    private static volatile Bundle cachedBundle;
    private static volatile boolean bundleRead;

    /** The bundled build, or null when this APK carries none (a build without the CI fetch step). */
    public static Bundle bundle(Context context) {
        if (bundleRead) return cachedBundle;
        Bundle b = null;
        try {
            JSONObject m = new JSONObject(FileUtils.readString(context, ASSET_DIR + "/manifest.json"));
            JSONArray files = m.getJSONArray("files");
            List<Slot> slots = new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                Slot s = new Slot(f.getString("asset"), f.getString("prefix"),
                        f.optString("sha256", "").toLowerCase(Locale.ROOT));
                try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + s.asset)) {
                    if (in.read() < 0) throw new java.io.IOException("empty");
                }
                slots.add(s);
            }
            if (!slots.isEmpty()) b = new Bundle(m.getString("version"), slots);
        } catch (Exception e) {
            Log.w(TAG, "no bundled dxvk-nvapi in this build: " + e);
        }
        cachedBundle = b;
        bundleRead = true;
        return b;
    }

    /** The bundled version ("v0.9.2"), or null when this APK carries none. */
    public static String bundledVersion(Context context) {
        Bundle b = bundle(context);
        return b != null ? b.version : null;
    }

    // ── The prefix ───────────────────────────────────────────────────────────────────────────────

    /** What {@link #sync} did, for the session log. */
    public static final class Result {
        /** The setting's value for this launch. */
        public final boolean on;
        /** On: the bundled dlls are in place, so the env may be set. */
        public final boolean installed;
        /** One-line account of what happened, or null when there is nothing to say (off, never on). */
        public final String detail;

        Result(boolean on, boolean installed, String detail) {
            this.on = on;
            this.installed = installed;
            this.detail = detail;
        }
    }

    private static final String STATE_DIR = "bannerlator-nvapi";
    private static final String MARKER = "installed.properties";

    /**
     * Bring the prefix at {@code prefixDir} (the .wine folder) in line with the setting: on = the
     * bundled dlls in place (backing up what they replace, once); off = anything we installed removed
     * and the backups put back. Safe to call on every launch and after an interrupted one. Never throws.
     */
    public static Result sync(Context context, File prefixDir, boolean on) {
        File windows = new File(prefixDir, "drive_c/windows");
        File state = new File(prefixDir, STATE_DIR);
        File marker = new File(state, MARKER);
        try {
            return on ? install(context, windows, state, marker) : restore(context, windows, state, marker);
        } catch (Throwable t) {
            Log.w(TAG, "sync failed", t);
            return new Result(on, false, (on ? "could not install dxvk-nvapi: " : "could not restore the prefix's files: ") + t);
        }
    }

    private static Result install(Context context, File windows, File state, File marker) throws Exception {
        Bundle b = bundle(context);
        if (b == null) return new Result(true, false, "not in this build (no bundled dxvk-nvapi), nothing installed");
        Properties m = load(marker);
        List<String> did = new ArrayList<>();     // put in place by this launch
        List<String> placed = new ArrayList<>();  // every slot that holds our dll now
        List<String> skipped = new ArrayList<>();
        for (Slot s : b.slots) {
            File target = new File(windows, s.prefixPath);
            File parent = target.getParentFile();
            // A prefix without the folder (no syswow64 = no 32-bit side) doesn't get one made for us.
            if (parent == null || !parent.isDirectory()) {
                skipped.add(s.prefixPath + " skipped (no " + (parent != null ? parent.getName() : "folder") + " in this prefix)");
                continue;
            }
            placed.add(s.prefixPath);
            File backup = new File(state, "backup/" + s.prefixPath);
            boolean ours = isOurs(target, s, m);
            // An original we haven't kept yet: keep it before anything overwrites it. Once per original;
            // a backup already there (from the first install or an interrupted one) is never replaced.
            if (target.isFile() && !ours && !backup.isFile()) {
                copyAtomically(new FileInputStream(target), backup);
                m.setProperty(s.prefixPath, "backup");
                store(m, marker);
            } else if (m.getProperty(s.prefixPath) == null) {
                m.setProperty(s.prefixPath, backup.isFile() ? "backup" : "absent");
                store(m, marker);
            }
            if (ours && b.version.equals(m.getProperty("version"))) continue;
            // Copy into a temporary file, record its hash, then rename it into place: whatever the
            // marker says we installed is always either absent from the slot or exactly that file.
            File tmp = new File(parent, "." + target.getName() + ".bannerlator-tmp");
            String sha;
            try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + s.asset)) {
                sha = copyTo(in, tmp);
            }
            if (!s.sha256.isEmpty() && !s.sha256.equals(sha)) {
                tmp.delete();
                throw new IllegalStateException(s.asset + " in the APK does not match its manifest hash");
            }
            addSha(m, s.prefixPath, sha);
            store(m, marker);
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new java.io.IOException("could not put " + s.prefixPath + " in place");
            }
            did.add(s.prefixPath + ("backup".equals(m.getProperty(s.prefixPath)) ? " (the prefix's own kept)" : " (none before)"));
        }
        if (placed.isEmpty()) return new Result(true, false, "nothing installed: " + String.join(", ", skipped));
        m.setProperty("version", b.version);
        store(m, marker);
        String detail = (did.isEmpty()
                ? "dxvk-nvapi " + b.version + " in place (" + String.join(" + ", placed) + ")"
                : "dxvk-nvapi " + b.version + " installed: " + String.join(", ", did))
                + (skipped.isEmpty() ? "" : "; " + String.join(", ", skipped));
        Log.i(TAG, detail);
        return new Result(true, true, detail);
    }

    private static Result restore(Context context, File windows, File state, File marker) throws Exception {
        if (!marker.isFile()) return new Result(false, false, null); // never installed here
        Properties m = load(marker);
        Bundle b = bundle(context);
        List<String> did = new ArrayList<>();
        for (String path : slotPaths(m)) {
            File target = new File(windows, path);
            File backup = new File(state, "backup/" + path);
            Slot s = null;
            if (b != null) for (Slot x : b.slots) if (x.prefixPath.equals(path)) s = x;
            if (target.isFile() && !isOurs(target, s != null ? s : new Slot("", path, ""), m)) {
                // Not ours: either the original is already back (an interrupted restore) or something
                // replaced our dll since (a Wine update, a DXVK package's own). Leave it; the backup
                // is older than what is there now.
                if (backup.isFile()) backup.delete();
                did.add(path + " left as is (not ours)");
            } else {
                if (target.isFile() && !target.delete()) throw new java.io.IOException("could not remove " + path);
                if (backup.isFile()) {
                    if (!backup.renameTo(target)) {
                        copyAtomically(new FileInputStream(backup), target);
                        backup.delete();
                    }
                    did.add("the prefix's own " + path + " back");
                } else {
                    did.add(path + " removed (none before)");
                }
            }
            m.remove(path);
            m.remove(path + ".sha256");
            store(m, marker);
        }
        marker.delete();
        deleteEmptyDirs(new File(state, "backup"));
        state.delete(); // only if empty
        String detail = "dxvk-nvapi removed, restored: " + String.join(", ", did);
        Log.i(TAG, detail);
        return new Result(false, false, detail);
    }

    /** The file in a slot is one we installed: its sha256 is the bundled one or one the marker records. */
    private static boolean isOurs(File target, Slot s, Properties m) throws Exception {
        if (!target.isFile()) return false;
        Set<String> known = new LinkedHashSet<>();
        if (s != null && !s.sha256.isEmpty()) known.add(s.sha256);
        String rec = m.getProperty((s != null ? s.prefixPath : "") + ".sha256", "");
        for (String h : rec.split(",")) if (!h.isEmpty()) known.add(h);
        return !known.isEmpty() && known.contains(sha256(target));
    }

    /** Remember every hash we ever put in a slot, so an older bundled build still counts as ours. */
    private static void addSha(Properties m, String path, String sha) {
        String key = path + ".sha256";
        String rec = m.getProperty(key, "");
        for (String h : rec.split(",")) if (h.equals(sha)) return;
        m.setProperty(key, rec.isEmpty() ? sha : rec + "," + sha);
    }

    /** Slot paths the marker knows (keys without a '.' suffix other than the file's own extension). */
    private static List<String> slotPaths(Properties m) {
        List<String> r = new ArrayList<>();
        for (String k : m.stringPropertyNames()) if (k.endsWith(".dll")) r.add(k);
        return r;
    }

    // ── Environment ─────────────────────────────────────────────────────────────────────────────

    /**
     * {@code existing} (a WINEDLLOVERRIDES value, may be null/empty) with "nvapi,nvapi64=n" added for
     * the dlls it doesn't already name — an override the user set for either stays theirs. Grouped
     * entries ("a,b=n") are understood. Returns the dlls it could not add (the user's) via
     * {@code keptOut} when non-null.
     */
    public static String withDllOverrides(String existing, List<String> keptOut) {
        Set<String> named = new LinkedHashSet<>();
        String e = existing == null ? "" : existing.trim();
        for (String entry : e.split(";")) {
            int eq = entry.indexOf('=');
            String dlls = eq >= 0 ? entry.substring(0, eq) : entry;
            for (String d : dlls.split(",")) {
                String n = d.trim().toLowerCase(Locale.ROOT);
                if (n.endsWith(".dll")) n = n.substring(0, n.length() - 4);
                if (!n.isEmpty()) named.add(n);
            }
        }
        List<String> add = new ArrayList<>();
        for (String d : OVERRIDE_DLLS) {
            if (named.contains(d)) { if (keptOut != null) keptOut.add(d); }
            else add.add(d);
        }
        if (add.isEmpty()) return e;
        String ours = String.join(",", add) + "=n";
        if (e.isEmpty()) return ours;
        return e.endsWith(";") ? e + ours : e + ";" + ours;
    }

    // ── Files ───────────────────────────────────────────────────────────────────────────────────

    private static Properties load(File marker) {
        Properties p = new Properties();
        if (marker.isFile()) {
            try (InputStream in = new FileInputStream(marker)) { p.load(in); } catch (Exception ignored) {}
        }
        return p;
    }

    private static void store(Properties p, File marker) throws Exception {
        File dir = marker.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        File tmp = new File(dir, marker.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "Bannerlator: dxvk-nvapi installed into this prefix (see core/DxvkNvapi)");
            out.getFD().sync();
        }
        if (!tmp.renameTo(marker)) throw new java.io.IOException("could not write " + marker);
    }

    /** Copy {@code in} to {@code dst} through a temporary file and a rename. Returns the sha256. */
    private static String copyAtomically(InputStream in, File dst) throws Exception {
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        File tmp = new File(dir, "." + dst.getName() + ".bannerlator-tmp");
        String sha;
        try (InputStream src = in) { sha = copyTo(src, tmp); }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new java.io.IOException("could not write " + dst);
        }
        return sha;
    }

    /** Write {@code in} to {@code dst} (synced), returning its sha256. */
    private static String copyTo(InputStream in, File dst) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
            }
            out.getFD().sync();
        }
        return hex(md.digest());
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static void deleteEmptyDirs(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory()) deleteEmptyDirs(k);
        dir.delete(); // only succeeds when empty
    }
}
