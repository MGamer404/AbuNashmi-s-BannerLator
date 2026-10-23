package com.winlator.star.linux;

import android.util.Log;

import com.winlator.star.container.Container;
import com.winlator.star.core.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presents the games this app already downloaded to the native Steam client, so the same install
 * serves both launchers and nothing is downloaded twice.
 *
 * <p>The app writes a real Steam layout into the container's prefix —
 * {@code drive_c/Program Files (x86)/Steam/steamapps} with {@code appmanifest_<appid>.acf} beside
 * {@code common/<InstallDir>} — so the client only needs that directory registered as a library
 * folder. The manifests are copied into the rootfs (they are tiny), and every game directory is
 * <em>bind-mounted</em> rather than copied: one copy on disk, visible to both.
 *
 * <p>Ported from WinNative's gamescope runtime (GPL-3.0).
 */
public final class LinuxSteamLibrary {
    private static final String TAG = "LinuxSteamLibrary";

    
    private static final Pattern MANIFEST = Pattern.compile("appmanifest_(\\d+)\\.acf");
    /** {@code "installdir"  "Half-Life 2"} — the folder the game lives in under common/. */
    private static final Pattern INSTALLDIR =
            Pattern.compile("\"installdir\"\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern BUILD_ID =
            Pattern.compile("^\\s*\"buildid\"\\s*\"(\\d+)\"", Pattern.MULTILINE);
    private static final Pattern NAME =
            Pattern.compile("^\\s*\"name\"\\s*\"([^\"]+)\"", Pattern.MULTILINE);

    private LinuxSteamLibrary() {}

    /** The build a manifest records, or 0 when there is no readable manifest. */
    private static long buildId(File manifest) {
        String text = manifest.isFile() ? FileUtils.readString(manifest) : null;
        if (text == null) return 0L;
        Matcher m = BUILD_ID.matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    /** The container prefix directory the app installs Steam games into. */
    public static File prefixSteamApps(Container container) {
        return new File(container.getRootDir(),
                ".wine/drive_c/Program Files (x86)/Steam/steamapps");
    }

    /**
     * Builds the library inside {@code rootfs} from {@code container}'s installed games and returns
     * the proot bind specs ({@code host:guest}) that put each game in place. Worker thread: this
     * reads and copies files.
     */
    /** The client's own library, as the session sees it: its steamapps/common is internal storage. */
    public static final String GUEST_STEAM_ROOT = "/root/.local/share/Steam";
    /** The client's second library: the card the app installs to, when there is one. */
    public static final String GUEST_ROOT_SD = "/mnt/bannerlator-sd";
    /** The client's tools live in its main library too; they are never games. */
    private static final Set<Integer> NOT_GAMES = new HashSet<>(java.util.Arrays.asList(
            228980, 1493710, 3127680, 4183110, 4427310, 4185400));

    /**
     * The app's game folders, presented to the Linux client as its own library folders.
     *
     * The store installs a game to one of two roots - the app's own steam_games, or
     * bannerlator/steam_games on the card the user chose - and each root is bound in whole as the
     * common/ folder of a Steam library the client sees. So a game the app installed simply appears
     * in the client, a game the client installs into either library lands exactly where the app
     * would have put it, and nothing is ever copied: the client reads the files Steam delivered.
     *
     * A Steam library is a folder with steamapps/common beneath it and a manifest per game in
     * steamapps, and the client will not treat a bare folder as a game. The manifests live on the
     * runtime side of each library. For a game the store's database knows, the manifest the app
     * wrote into a container's prefix is preferred, because it carries the build the app installed,
     * and builds are reconciled both ways with it; a folder the database does not know still counts
     * when it identifies itself, and gets a manifest written from what is known.
     */
    public static List<String> prepare(android.content.Context context, List<Container> containers, File rootfs) {
        List<String> binds = new ArrayList<>();
        java.util.Map<String, File> prefixManifests = prefixManifests(containers);

        // The database's view: app id -> folder, and depot -> app for the journal fallback.
        java.util.Map<Integer, String> depotToApp = new java.util.HashMap<>();
        // Reconcile before presenting anything. This used to run only when a session ended, so a
        // session killed by an APK install - every one of them today - never released what the
        // client had uninstalled, and the next start handed the client a manifest for a folder
        // with nothing in it.
        releaseClientUninstalls(context);
        java.util.Map<Integer, File> recorded = new java.util.HashMap<>();
        java.util.Map<Integer, String> names = new java.util.HashMap<>();
        java.util.Map<Integer, Long> sizes = new java.util.HashMap<>();
        java.util.Map<Integer, java.util.List<com.winlator.star.store.SteamDatabase.DepotManifestRow>> depots =
                new java.util.HashMap<>();
        try {
            for (com.winlator.star.store.SteamDatabase.GameRow row
                    : com.winlator.star.store.SteamDatabase.getInstance(context).getInstalledGames()) {
                if (row.depotIds != null) {
                    for (String d : row.depotIds.split(",")) {
                        try { depotToApp.put(Integer.parseInt(d.trim()), String.valueOf(row.appId)); } catch (NumberFormatException ignored) {}
                    }
                }
                recorded.put(row.appId, installDirOf(context, row));
                names.put(row.appId, row.name);
                sizes.put(row.appId, row.sizeBytes);
                try {
                    depots.put(row.appId,
                            com.winlator.star.store.SteamDatabase.getInstance(context).getDepotManifests(row.appId));
                } catch (Throwable t) {
                    Log.w(TAG, "no depot manifests for " + row.appId, t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "installed games unavailable from the database", t);
        }

        File internalRoot = new File(context.getFilesDir(), "imagefs/steam_games");
        File cardRoot = cardRoot(context);
        // Main = internal storage, like the app. The client's own steamapps/common is bound over
        // with the app's internal root, so its default install location IS the app's, and there is
        // no private folder for an absent-minded install to fall into. Anything the client had
        // already put in that folder is moved into internal storage first - a rename on the same
        // filesystem - so the bind hides nothing.
        migratePrivateLibrary(rootfs, internalRoot);
        retireOldLibrary(rootfs);
        int total = 0;
        total += presentRoot(internalRoot, GUEST_STEAM_ROOT, rootfs, recorded, names, sizes, depots, depotToApp, prefixManifests, binds);
        if (cardRoot != null) {
            total += presentRoot(cardRoot, GUEST_ROOT_SD, rootfs, recorded, names, sizes, depots, depotToApp, prefixManifests, binds);
            // Steam downloads into <library>/steamapps/downloading and renames the finished game
            // into common/. Both must be on the card for that rename to be a rename; the prefixes
            // (compatdata) stay in the runtime root, where symlinks and locks work.
            File downloading = new File(cardRoot.getParentFile(), "steam_downloading");
            File mountPoint = new File(rootfs, GUEST_ROOT_SD.substring(1) + "/steamapps/downloading");
            if ((downloading.isDirectory() || downloading.mkdirs()) && (mountPoint.isDirectory() || mountPoint.mkdirs())) {
                binds.add(downloading.getPath() + ":" + GUEST_ROOT_SD + "/steamapps/downloading");
            }
        }
        Log.i(TAG, "presented " + total + " installed game(s) to the Steam client in "
                + (cardRoot != null ? "two libraries" : "one library"));
        return binds;
    }

    /**
     * One root as one library: bind it whole as the library's common/, and write a manifest for
     * every game folder in it. Returns how many games the client will see there.
     */
    private static int presentRoot(File root, String guestLibrary, File rootfs,
                                   java.util.Map<Integer, File> recorded, java.util.Map<Integer, String> names,
                                   java.util.Map<Integer, Long> sizes,
                                   java.util.Map<Integer, java.util.List<com.winlator.star.store.SteamDatabase.DepotManifestRow>> depots,
                                   java.util.Map<Integer, String> depotToApp,
                                   java.util.Map<String, File> prefixManifests, List<String> binds) {
        if (!root.isDirectory() && !root.mkdirs()) return 0;
        File steamapps = new File(rootfs, guestLibrary.substring(1) + "/steamapps");
        File common = new File(steamapps, "common");
        if (!common.isDirectory() && !common.mkdirs()) {
            Log.w(TAG, "cannot create " + common);
            return 0;
        }
        String realRoot;
        try {
            realRoot = root.getCanonicalPath();
        } catch (java.io.IOException e) {
            Log.w(TAG, "cannot resolve " + root, e);
            return 0;
        }
        binds.add(realRoot + ":" + guestLibrary + "/steamapps/common");

        Set<String> kept = new HashSet<>();
        int count = 0;
        // Games the database records in this root.
        for (java.util.Map.Entry<Integer, File> e : recorded.entrySet()) {
            File dir = e.getValue();
            if (!dir.isDirectory() || !isUnder(dir, root)) continue;
            if (writeManifest(String.valueOf(e.getKey()), names.get(e.getKey()), dir, steamapps, kept, prefixManifests,
                    depots.get(e.getKey()), sizes.get(e.getKey()))) count++;
        }
        // Folders in this root that identify themselves and that the database missed.
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                try {
                    String appId = appIdOfFolder(dir, depotToApp);
                    if (appId == null || recorded.containsKey(Integer.parseInt(appId))) continue;
                    Log.i(TAG, dir.getName() + " identifies itself as " + appId + " without a database row");
                    if (writeManifest(appId, dir.getName(), dir, steamapps, kept, prefixManifests,
                            depots.get(Integer.parseInt(appId)), sizes.get(Integer.parseInt(appId)))) count++;
                } catch (Throwable t) {
                    Log.w(TAG, "skipping " + dir, t);
                }
            }
        }
        // A manifest the client wrote for a game it installed here is the client's to keep; only
        // the ones written for the app's games and now absent are removed.
        // A manifest for a game whose folder is gone is removed, whoever wrote it. The client's
        // tools and any download in progress are left exactly as they are.
        File[] stale = steamapps.listFiles((d, name) -> MANIFEST.matcher(name).matches());
        if (stale != null) {
            for (File file : stale) {
                if (kept.contains(file.getName())) continue;
                Matcher id = MANIFEST.matcher(file.getName());
                if (!id.matches() || NOT_GAMES.contains(Integer.parseInt(id.group(1)))) continue;
                String installDir = installDirOfManifest(file);
                if (installDir != null && new File(root, installDir).isDirectory()) continue;
                // A download in progress has no folder yet and is not fully installed; a game
                // whose folder was removed - by the store or by hand - is.
                String text = file.isFile() ? FileUtils.readString(file) : null;
                if (isInstalled(text)) file.delete();
            }
        }
        return count;
    }

    /**
     * Moves what the client had installed into its own steamapps/common into internal storage, once,
     * before that folder is bound over. Same filesystem, so each move is a rename; a name already
     * present in internal storage is left where it is and reported, rather than merged or replaced.
     */
    private static void migratePrivateLibrary(File rootfs, File internalRoot) {
        File common = new File(rootfs, GUEST_STEAM_ROOT.substring(1) + "/steamapps/common");
        File[] entries = common.listFiles();
        if (entries == null || entries.length == 0) return;
        if (!internalRoot.isDirectory() && !internalRoot.mkdirs()) return;
        for (File entry : entries) {
            File target = new File(internalRoot, entry.getName());
            if (target.exists()) {
                Log.w(TAG, entry.getName() + " exists in both the client's folder and internal storage; leaving the client's copy where it is");
                continue;
            }
            if (entry.renameTo(target)) {
                Log.i(TAG, "moved " + entry.getName() + " from the client's folder into internal storage");
            } else {
                Log.w(TAG, "could not move " + entry + " into internal storage");
            }
        }
    }

    /**
     * Earlier builds gave the client a third library at /mnt/bannerlator for the app's games. That
     * folder is not bound any more; the prefixes and shader caches games made there are moved into
     * the main library, where the client now looks for them, and the rest - manifests this app
     * wrote and an empty mount point - is removed.
     */
    private static void retireOldLibrary(File rootfs) {
        File old = new File(rootfs, "mnt/bannerlator/steamapps");
        if (!old.isDirectory()) return;
        File main = new File(rootfs, GUEST_STEAM_ROOT.substring(1) + "/steamapps");
        for (String kind : new String[] {"compatdata", "shadercache"}) {
            File[] entries = new File(old, kind).listFiles();
            if (entries == null) continue;
            File into = new File(main, kind);
            if (!into.isDirectory() && !into.mkdirs()) continue;
            for (File entry : entries) {
                File target = new File(into, entry.getName());
                if (target.exists()) continue;
                if (entry.renameTo(target)) Log.i(TAG, "moved " + kind + "/" + entry.getName() + " into the main library");
            }
        }
        File[] left = old.listFiles();
        boolean empty = true;
        if (left != null) {
            for (File f : left) {
                if (f.isFile() && MANIFEST.matcher(f.getName()).matches()) f.delete();
                else if (f.isDirectory() && (f.getName().equals("common") || f.getName().equals("compatdata")
                        || f.getName().equals("shadercache") || f.getName().equals("downloading") || f.getName().equals("temp"))) {
                    if (!FileUtils.delete(f)) empty = false;
                } else empty = false;
            }
        }
        if (empty) { old.delete(); old.getParentFile().delete(); }
    }

    private static boolean isUnder(File dir, File root) {
        try {
            return dir.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static String installDirOfManifest(File manifest) {
        String text = manifest.isFile() ? FileUtils.readString(manifest) : null;
        if (text == null) return null;
        Matcher m = INSTALLDIR.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** The card the store installs to, when one is chosen and mounted. LinuxComponents looks there for a Proton depot too. */
    static File cardRoot(android.content.Context context) {
        try {
            com.winlator.star.store.SteamSdInstall.SdTarget sd = com.winlator.star.store.SteamSdInstall.INSTANCE.detect(context);
            if (sd != null && sd.getSteamGamesBase().isDirectory()) return sd.getSteamGamesBase();
        } catch (Throwable t) {
            Log.w(TAG, "card detection failed", t);
        }
        return null;
    }

    /** The manifest the client needs for one game folder, reconciled with the app's when it has one. */
    private static boolean writeManifest(String appId, String name, File dir, File steamapps, Set<String> kept,
                                         java.util.Map<String, File> prefixManifests,
                                         java.util.List<com.winlator.star.store.SteamDatabase.DepotManifestRow> depots,
                                         Long sizeBytes) {
        String manifestName = "appmanifest_" + appId + ".acf";
        if (kept.contains(manifestName)) return false;
        String installDir = dir.getName();
        File runtimeManifest = new File(steamapps, manifestName);
        File prefixManifest = prefixManifests.get(manifestName);
        if (prefixManifest != null) {
            long runtimeBuild = buildId(runtimeManifest), appBuild = buildId(prefixManifest);
            if (runtimeBuild > appBuild) {
                if (!FileUtils.copy(runtimeManifest, prefixManifest)) Log.w(TAG, "could not adopt " + runtimeManifest);
            } else if (runtimeBuild == 0L || appBuild > runtimeBuild) {
                if (!FileUtils.copy(prefixManifest, runtimeManifest)) return false;
            }
        } else if (!runtimeManifest.isFile() || lacksDepots(runtimeManifest)) {
            // A manifest that names no depots tells the client the game is installed without saying
            // which files are there or which build they came from, and the only safe reading of that
            // is "verify everything, then fetch what is missing" - Left 4 Dead 2 sat re-verifying
            // 13 GB that was already on the card, and Lossless Scaling queued its whole download
            // beside 177 MB of its own files. The app knows better: it recorded every depot it
            // installed and the manifest it installed them from. So write that.
            if (!writeManifestWithDepots(runtimeManifest, appId, name != null ? name : installDir,
                    installDir, depots, sizeBytes)) {
                if (runtimeManifest.isFile()) return true;   // leave what is already there
                com.winlator.star.store.RealSteamLauncher.writeAppManifest(
                        runtimeManifest, Integer.parseInt(appId), name != null ? name : installDir, installDir, 0L);
            }
        }
        kept.add(manifestName);
        return true;
    }

    /**
     * The other direction: a game the client installed into one of the app's libraries is recorded
     * in the store's database as installed at that folder, so the store shows it, the app can launch
     * it, and it is treated like any other install from then on. Run when a session starts and again
     * when it ends. The client's own private library is not adopted: those files live inside the
     * runtime, where the app cannot launch them.
     */
    public static int adoptClientInstalls(android.content.Context context, File rootfs) {
        int adopted = 0;
        releaseClientUninstalls(context);
        com.winlator.star.store.SteamDatabase db;
        try {
            db = com.winlator.star.store.SteamDatabase.getInstance(context);
        } catch (Throwable t) {
            Log.w(TAG, "database unavailable", t);
            return 0;
        }
        File internalRoot = new File(context.getFilesDir(), "imagefs/steam_games");
        File cardRoot = cardRoot(context);
        String[][] libraries = cardRoot != null
                ? new String[][] {{GUEST_STEAM_ROOT, internalRoot.getPath()}, {GUEST_ROOT_SD, cardRoot.getPath()}}
                : new String[][] {{GUEST_STEAM_ROOT, internalRoot.getPath()}};
        for (String[] lib : libraries) {
            File steamapps = new File(rootfs, lib[0].substring(1) + "/steamapps");
            File[] manifests = steamapps.listFiles((d, name) -> MANIFEST.matcher(name).matches());
            if (manifests == null) continue;
            for (File manifest : manifests) {
                try {
                    Matcher id = MANIFEST.matcher(manifest.getName());
                    if (!id.matches()) continue;
                    int appId = Integer.parseInt(id.group(1));
                    if (NOT_GAMES.contains(appId)) continue;
                    String text = manifest.isFile() ? FileUtils.readString(manifest) : null;
                    if (text == null) continue;
                    Matcher nmCheck = NAME.matcher(text);
                    if (nmCheck.find()) {
                        String n = nmCheck.group(1);
                        if (n.startsWith("Proton") || n.startsWith("Steam Linux Runtime") || n.startsWith("Steamworks") || n.equals("FEX")) continue;
                    }
                    if (!isInstalled(text)) continue;   // not on disk yet
                    Matcher dir = INSTALLDIR.matcher(text);
                    Matcher nm = NAME.matcher(text);
                    if (!dir.find()) continue;
                    File folder = new File(lib[1], dir.group(1));
                    if (!folder.isDirectory()) continue;
                    com.winlator.star.store.SteamDatabase.GameRow row = db.getGame(appId);
                    // The build stamp is kept in step with the manifest on every pass, before the
                    // row is considered. A game adopted by an earlier run needs nothing recorded
                    // and would skip out below without ever being stamped, and a game the client
                    // has since updated carries a new build the app has not been told about -
                    // both end as an update offered for a copy that is current.
                    com.winlator.star.store.SteamGameUpdater.recordKnownBuild(
                            folder, branchOf(text), buildIdOf(text));
                    if (row != null && row.isInstalled && folder.getPath().equals(row.installDir)) continue;
                    String name = nm.find() ? nm.group(1) : dir.group(1);
                    if (row == null) db.upsertGame(appId, name, "", 0L, "", "game", "", 0, "");
                    // The manifest states the size, so take it from there. Measuring the
                    // folder means walking every file in it, and this pass runs as the session
                    // ends, while the activity is being torn down: Portal 2's twelve gigabytes
                    // took longer than the process survived, so the game was never recorded and
                    // nothing said why.
                    long size = sizeOnDisk(text);
                    db.markInstalled(appId, folder.getPath(), size > 0 ? size : folderSize(folder));
                    Log.i(TAG, "adopted " + name + " (" + appId + ") installed by the Linux client at " + folder);
                    adopted++;
                } catch (Throwable t) {
                    Log.w(TAG, "could not adopt " + manifest, t);
                }
            }
        }
        // Logged even when nothing was adopted, because a pass that runs and finds nothing looked
        // exactly like a pass that never finished.
        Log.i(TAG, "adoption pass: " + adopted + " game(s) taken into the store");
        return adopted;
    }

    /** The build the manifest records, or 0 when it does not say. */
    private static long buildIdOf(String manifest) {
        Matcher m = BUILD_ID.matcher(manifest);
        if (!m.find()) return 0L;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** The branch the install came from. Absent means the default one, which the app calls public. */
    private static String branchOf(String manifest) {
        Matcher m = BETA_KEY.matcher(manifest);
        return m.find() && !m.group(1).trim().isEmpty() ? m.group(1).trim() : "public";
    }

    private static final Pattern BETA_KEY =
            Pattern.compile("^\\s*\"betakey\"\\s*\"([^\"]*)\"", Pattern.MULTILINE);

    /** What the manifest says the install occupies, or 0 when it does not say. */
    private static long sizeOnDisk(String manifest) {
        Matcher m = SIZE_ON_DISK.matcher(manifest);
        if (!m.find()) return 0L;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static final Pattern SIZE_ON_DISK =
            Pattern.compile("^\\s*\"SizeOnDisk\"\\s*\"(\\d+)\"", Pattern.MULTILINE);

    /**
     * Whether a manifest describes a game whose files are on disk. StateFlags is a bit field, not
     * a value: 4 means fully installed, and a game can carry it alongside 2 (an update is
     * available) or 512 (an update is paused) and still be installed and playable - Left 4 Dead 2
     * sat at 6 and Team Fortress 2 at 516 on the test device. Comparing the whole field to "4"
     * therefore missed games that were simply a version behind. A manifest with no StateFlags at
     * all is treated as installed, which is how the app's own manifests read before the client
     * has touched them; one that is being removed (2048) is not.
     */
    private static boolean isInstalled(String manifest) {
        if (manifest == null) return false;
        Matcher st = STATE.matcher(manifest);
        if (!st.find()) return true;
        int flags;
        try {
            flags = Integer.parseInt(st.group(1));
        } catch (NumberFormatException e) {
            return true;
        }
        return (flags & 4) != 0 && (flags & 2048) == 0;
    }

    private static final Pattern STATE =
            Pattern.compile("^\\s*\"StateFlags\"\\s*\"(\\d+)\"", Pattern.MULTILINE);

    /**
     * The store records a game as installed until told otherwise. When the client removes one
     * from a library the app can see right now, the record follows; a library that is not
     * present - the card is out - proves nothing and is left alone.
     */
    /**
     * True when {@code dir} holds nothing a game would need - only bookkeeping and caches left
     * behind by an uninstall.
     *
     * <p>The first version of this accepted only our own build marker, and missed the real case on
     * the device: GTA V Enhanced was left with the marker and a {@code vkd3d-proton.cache} that
     * Proton had written into the game's folder, so the folder was not "empty" and the game stayed
     * installed forever.
     *
     * <p>The test that carries the weight is that an installed game always has at least one
     * subdirectory - every one of them keeps its content in folders - so a flat directory holding
     * nothing but dotfiles and caches has no game in it.
     */
    private static boolean isEmptyShell(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return false;
        for (File entry : entries) {
            if (entry.isDirectory()) return false;
            String name = entry.getName();
            if (name.startsWith(".")) continue;          // our marker and other bookkeeping
            if (name.endsWith(".cache")) continue;       // vkd3d-proton.cache and friends
            return false;
        }
        return true;
    }

    private static void releaseClientUninstalls(android.content.Context context) {
        try {
            com.winlator.star.store.SteamDatabase db = com.winlator.star.store.SteamDatabase.getInstance(context);
            File internalRoot = new File(context.getFilesDir(), "imagefs/steam_games");
            File cardRoot = cardRoot(context);
            for (com.winlator.star.store.SteamDatabase.GameRow row : db.getInstalledGames()) {
                File dir = installDirOf(context, row);
                if (dir == null) continue;
                // The client removes a game's own files and stops there: it will not delete a folder
                // holding a file it did not write, and .bannerlator_build is ours. So after a client
                // uninstall the folder is always still standing, with nothing in it but that marker,
                // and "is the folder gone" was never true - the row stayed installed, the next
                // session presented the empty folder to the client as a game again, and the client
                // showed it installed. A folder with nothing but our own bookkeeping in it is
                // treated as gone, and removed so it cannot be mistaken for anything again.
                boolean gone = !dir.isDirectory();
                boolean shell = !gone && isEmptyShell(dir);
                if (!gone && !shell) continue;
                boolean visible = isUnder(dir, internalRoot) || (cardRoot != null && isUnder(dir, cardRoot));
                if (!visible) continue;
                db.markUninstalled(row.appId);
                if (shell) com.winlator.star.core.FileUtils.delete(dir);
                Log.i(TAG, row.name + " (" + row.appId + ") is " + (shell ? "an empty shell at " : "gone from ")
                        + dir + "; no longer installed");
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not reconcile removed games", t);
        }
    }

    private static long folderSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) total += f.isDirectory() ? folderSize(f) : f.length();
        return total;
    }

    /** The launching container alone; kept for callers that have no context. */
    public static List<String> prepare(Container container, File rootfs) {
        return new ArrayList<>();
    }

    /** Same rule the updater uses: the recorded folder, else the app's own storage by name. */
    private static File installDirOf(android.content.Context context,
                                     com.winlator.star.store.SteamDatabase.GameRow row) {
        if (row.installDir != null && !row.installDir.isEmpty()) return new File(row.installDir);
        return new File(new File(context.getFilesDir(), "imagefs/steam_games"), safeName(row.name));
    }

    private static String safeName(String name) {
        String safe = name == null ? "" : name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "game" : safe;
    }

    /** The app's own steam_games and bannerlator/steam_games on every mounted volume. */
    private static List<File> storeRoots(android.content.Context context) {
        List<File> roots = new ArrayList<>();
        roots.add(new File(context.getFilesDir(), "imagefs/steam_games"));
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File volume : volumes) roots.add(new File(volume, "bannerlator/steam_games"));
        }
        return roots;
    }

    /** An existing manifest whose InstalledDepots block is empty: installed, but contents unknown. */
    private static boolean lacksDepots(File manifest) {
        if (!manifest.isFile()) return false;
        String text = FileUtils.readString(manifest);
        return text != null && EMPTY_DEPOTS.matcher(text).find();
    }

    private static final Pattern EMPTY_DEPOTS =
            Pattern.compile("\"InstalledDepots\"\\s*\\{\\s*\\}");

    /**
     * The manifest a game deserves when the app installed it: every depot with the manifest id it
     * came from, so the client can see it already holds that build and asks for nothing. Without
     * the depots there is nothing worth writing, so this reports failure and the caller keeps
     * whatever was there.
     */
    private static boolean writeManifestWithDepots(File acf, String appId, String name, String installDir,
                                                   java.util.List<com.winlator.star.store.SteamDatabase.DepotManifestRow> depots,
                                                   Long sizeBytes) {
        if (depots == null || depots.isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        long total = 0L;
        for (com.winlator.star.store.SteamDatabase.DepotManifestRow d : depots) {
            if (d.manifestId == 0L) continue;
            long size = d.realSizeBytes > 0 ? d.realSizeBytes : d.sizeBytes;
            total += size;
            sb.append("\t\t\"").append(d.depotId).append("\"\n\t\t{\n")
              .append("\t\t\t\"manifest\"\t\t\"").append(d.manifestId).append("\"\n")
              .append("\t\t\t\"size\"\t\t\"").append(size).append("\"\n")
              .append("\t\t}\n");
        }
        if (sb.length() == 0) return false;
        // Steam's own manifests carry the depot total here - FlatOut's, written by the app's
        // downloader, matches its single depot to the byte - so prefer that over the store's
        // catalogue estimate, which is a different number for the same game.
        long onDisk = total > 0 ? total : (sizeBytes != null ? sizeBytes : 0L);
        String text = "\"AppState\"\n{\n"
                + "\t\"appid\"\t\t\"" + appId + "\"\n"
                + "\t\"universe\"\t\t\"1\"\n"
                + "\t\"name\"\t\t\"" + vdf(name) + "\"\n"
                + "\t\"StateFlags\"\t\t\"4\"\n"
                + "\t\"installdir\"\t\t\"" + vdf(installDir) + "\"\n"
                + "\t\"SizeOnDisk\"\t\t\"" + onDisk + "\"\n"
                + "\t\"LastOwner\"\t\t\"0\"\n"
                + "\t\"InstalledDepots\"\n\t{\n" + sb + "\t}\n"
                + "\t\"UserConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n"
                + "\t\"MountedConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n"
                + "}\n";
        if (!FileUtils.writeString(acf, text)) return false;
        Log.i(TAG, "wrote " + name + " (" + appId + ") with " + depots.size() + " depot(s), " + onDisk + " bytes");
        return true;
    }

    private static String vdf(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** steam_appid.txt from a launch, else the downloader's journal mapped through the database. */
    private static String appIdOfFolder(File dir, java.util.Map<Integer, String> depotToApp) {
        // readString throws on a file that is not there rather than returning null, and a folder
        // that has never been launched has no steam_appid.txt: check first, every time.
        File idFile = new File(dir, "steam_appid.txt");
        String text = idFile.isFile() ? FileUtils.readString(idFile) : null;
        if (text != null && text.trim().matches("\\d+")) return text.trim();
        File journalFile = new File(dir, ".bl_depot/depot.config");
        String journal = journalFile.isFile() ? FileUtils.readString(journalFile) : null;
        if (journal == null) return null;
        Matcher m = Pattern.compile("\"(\\d+)\"\\s*:").matcher(journal);
        while (m.find()) {
            String app = depotToApp.get(Integer.parseInt(m.group(1)));
            if (app != null) return app;
        }
        return null;
    }

    /** Every container's prefix manifests by file name; the first container that has one wins. */
    private static java.util.Map<String, File> prefixManifests(List<Container> containers) {
        java.util.Map<String, File> found = new java.util.HashMap<>();
        if (containers == null) return found;
        for (Container container : containers) {
            File[] manifests = prefixSteamApps(container).listFiles((dir, name) -> MANIFEST.matcher(name).matches());
            if (manifests == null) continue;
            for (File m : manifests) found.putIfAbsent(m.getName(), m);
        }
        return found;
    }

    /**
     * The other direction: a game the Linux client installed for itself gets an entry in the Games
     * tab that launches it through the client. Nothing is copied into the Wine prefix, because the
     * game lives in the runtime and runs on ARM64 Proton there, not on Wine; the entry carries the
     * app id and the client's own title. Entries are only ever added, never removed here: a game
     * the client has since uninstalled keeps its card until the user removes it, which is how the
     * app's own store behaves too.
     */
    public static int syncClientGames(Container container, File rootfs) {
        if (container == null) return 0;
        File steamapps = new File(rootfs, "root/.local/share/Steam/steamapps");
        File[] manifests = steamapps.listFiles((dir, name) -> MANIFEST.matcher(name).matches());
        if (manifests == null) return 0;
        int added = 0;
        for (File manifest : manifests) {
            Matcher id = MANIFEST.matcher(manifest.getName());
            if (!id.matches()) continue;
            String appId = id.group(1);
            String text = manifest.isFile() ? FileUtils.readString(manifest) : null;
            if (text == null) continue;
            Matcher name = NAME.matcher(text);
            Matcher dir = INSTALLDIR.matcher(text);
            if (!name.find() || !dir.find()) continue;
            // The client's own tools are manifests too; only a title with an installed folder that
            // is not one of them is a game.
            if (!new File(steamapps, "common/" + dir.group(1)).isDirectory()) continue;
            String title = name.group(1);
            if (title.startsWith("Proton") || title.startsWith("Steam Linux Runtime")
                    || title.startsWith("Steamworks") || title.equals("FEX")) continue;
            if (LinuxShortcuts.createClientGameShortcut(container, appId, title)) added++;
        }
        if (added > 0) Log.i(TAG, "added " + added + " game(s) the Linux client installed");
        return added;
    }
}
