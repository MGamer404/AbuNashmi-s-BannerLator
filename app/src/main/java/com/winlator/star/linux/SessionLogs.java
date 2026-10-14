package com.winlator.star.linux;

import android.content.Context;
import android.util.Log;

import com.winlator.star.core.LogRedactor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One folder per Linux session under {@code Download/Bannerlator-LinuxSteam/}, holding everything
 * that session recorded - the shape the SteamDeck standalone app settled on, kept the same here so
 * a bundle from either reads alike and is safe to attach as it is:
 * <pre>
 *   session-20260921-215022/
 *       device.txt     the device, its GPU and drivers, and the settings the session ran with
 *       network.txt    transport, addresses and DNS as the session started (never the SSID)
 *       session.log    the guest session: proot, gamescope, the client's stdout  (was a loose file)
 *       fake-input.txt the controller rings' diagnostics                        (was a loose file)
 *       app.log        this process's own logcat - only while launch logging is on
 *       audio.log      the PulseAudio daemon's log for the session
 *       crash.log      Android's crash buffer as the session ended
 *       steam/         the Steam client's own logs, SCRUBBED line by line
 * </pre>
 *
 * <p>What costs anything during play is only what already did: {@code session.log} is the same
 * stream as before, with the same 8 MB rotation. {@code app.log} is a {@code logcat --pid}
 * reader and is gated on the launch-logging switch. Everything else is written once at start or
 * once at teardown.
 *
 * <p>Steam's logs used to be copied raw into Download by the session script, and only on a clean
 * exit - a killed session left nothing, and a clean one left {@code connection_log.txt} with the
 * account's session token in it ("Using JWT ..."). They are copied here by the app at teardown
 * whatever ended the session, through {@link LogRedactor}. {@code loginusers.vdf},
 * {@code config.vdf} and {@code ssfn*} are never copied.
 *
 * <p>The redactor is told whose logs these are first ({@link LogRedactor#learnFromRuntime}): the
 * device's public addresses from the session's link file and the account names from the client's
 * {@code loginusers.vdf}, so "external address = '...'" and "OnLoginStateChange &lt;account&gt;"
 * lines come out clean. The folder's own files - written as they happened - go through the same
 * redactor once the session is over ({@link #scrubFolder}). (From The412Banner/DroidDeck.)
 */
public final class SessionLogs {
    private static final String TAG = "SessionLogs";
    private static volatile File current;
    private static volatile Process appLog;

    private SessionLogs() {}

    /** Claims the folder for a session now starting. */
    public static synchronized File begin() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File dir = new File(LinuxRuntime.debugLogDir(), "session-" + stamp);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        current = dir;
        Log.i(TAG, "session logs: " + dir);
        return dir;
    }

    public static File current() {
        return current;
    }

    // ---- start of session -------------------------------------------------------------------

    public static void writeDeviceReport(Context context, File target, String settingsInEffect) {
        StringBuilder b = new StringBuilder();
        b.append("Bannerlator Linux session report\n================================\n");
        kv(b, "Written", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(new Date()));
        h(b, "App");
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            kv(b, "Version", info.versionName + " (" + info.getLongVersionCode() + ")");
        } catch (Exception ignored) {}
        kv(b, "Package", context.getPackageName());
        kv(b, "targetSdk", context.getApplicationInfo().targetSdkVersion);
        h(b, "Device");
        kv(b, "Model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        kv(b, "Device / product", android.os.Build.DEVICE + " / " + android.os.Build.PRODUCT);
        kv(b, "Board / hardware", android.os.Build.BOARD + " / " + android.os.Build.HARDWARE);
        if (android.os.Build.VERSION.SDK_INT >= 31) kv(b, "SoC", android.os.Build.SOC_MANUFACTURER + " " + android.os.Build.SOC_MODEL);
        kv(b, "Android", android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        kv(b, "Security patch", android.os.Build.VERSION.SECURITY_PATCH);
        kv(b, "Build", android.os.Build.DISPLAY);
        kv(b, "Kernel", System.getProperty("os.version"));
        kv(b, "ABIs", String.join(", ", android.os.Build.SUPPORTED_ABIS));
        h(b, "CPU and memory");
        int cores = Runtime.getRuntime().availableProcessors();
        kv(b, "Cores", cores);
        StringBuilder ceilings = new StringBuilder();
        for (int c = 0; c < cores; c++) {
            String khz = readSys("/sys/devices/system/cpu/cpu" + c + "/cpufreq/cpuinfo_max_freq");
            ceilings.append(c > 0 ? ", " : "").append("cpu").append(c).append(' ');
            try { ceilings.append(String.format(Locale.US, "%.2f GHz", Long.parseLong(khz.trim()) / 1e6)); }
            catch (Exception e) { ceilings.append('?'); }
        }
        kv(b, "Core ceilings", ceilings);
        try {
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            kv(b, "RAM total", size(mi.totalMem));
            kv(b, "RAM available", size(mi.availMem));
        } catch (Exception ignored) {}
        try {
            android.os.StatFs fs = new android.os.StatFs(context.getFilesDir().getPath());
            kv(b, "App storage free", size(fs.getAvailableBlocksLong() * fs.getBlockSizeLong()));
            android.os.StatFs sd = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            kv(b, "Shared storage free", size(sd.getAvailableBlocksLong() * sd.getBlockSizeLong()));
        } catch (Exception ignored) {}
        h(b, "GPU");
        kv(b, "KGSL gpu_model", readSys("/sys/class/kgsl/kgsl-3d0/gpu_model").trim());
        kv(b, "KGSL chip id", readSys("/sys/class/kgsl/kgsl-3d0/gpu_chipid").trim());
        kv(b, "System Vulkan ICD", new File("/vendor/lib64/hw/vulkan.adreno.so").exists()
                ? "/vendor/lib64/hw/vulkan.adreno.so" : "not at the usual path");
        h(b, "Runtime");
        kv(b, "Runtime root", LinuxRuntime.rootDir(context).getPath());
        kv(b, "Runtime ready", LinuxRuntime.isInstalled(context));
        File version = new File(LinuxRuntime.rootDir(context), ".version");
        kv(b, "Runtime version", version.isFile() ? readFile(version).trim() : "unversioned");
        File icd = LinuxRuntime.vulkanIcd(context);
        kv(b, "Runtime's own ICD", icd != null ? icd.getPath() : null);
        h(b, "Settings in effect");
        b.append(settingsInEffect == null ? "" : settingsInEffect);
        write(target, b.toString());
    }

    public static void writeNetworkReport(Context context, File target) {
        StringBuilder b = new StringBuilder();
        b.append("Network as the session starts\n=============================\n");
        b.append("The SSID is deliberately not recorded, and addresses appear as their kind, not their numbers.\n\n");
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network network = cm != null ? cm.getActiveNetwork() : null;
            if (cm == null || network == null) {
                kv(b, "Active network", "none - the phone reports no connection");
            } else {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                String transport = caps == null ? "unknown"
                        : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "WiFi"
                        : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular"
                        : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                        : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ? "VPN" : "other";
                kv(b, "Transport", transport);
                kv(b, "Validated", caps == null ? "unknown"
                        : caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        + "   (false with a working link usually means a captive portal)");
                kv(b, "Metered", caps == null ? "unknown" : !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                if (caps != null) kv(b, "Link speed", "down " + caps.getLinkDownstreamBandwidthKbps() + " kbps / up " + caps.getLinkUpstreamBandwidthKbps() + " kbps");
                android.net.LinkProperties link = cm.getLinkProperties(network);
                kv(b, "Interface", link != null ? link.getInterfaceName() : "unknown");
                if (link != null) {
                    kv(b, "MTU", link.getMtu());
                    int v4 = 0, v6 = 0;
                    for (android.net.LinkAddress a : link.getLinkAddresses()) {
                        if (a.getAddress() instanceof java.net.Inet4Address) v4++;
                        else if (a.getAddress() instanceof java.net.Inet6Address) v6++;
                    }
                    kv(b, "Addresses", v4 + " IPv4, " + v6 + " IPv6" + (v4 == 0 ? "   (no IPv4 - Steam's content servers need it)" : ""));
                    StringBuilder dns = new StringBuilder();
                    for (java.net.InetAddress d : link.getDnsServers()) dns.append(dns.length() > 0 ? ", " : "").append(d.getHostAddress());
                    kv(b, "DNS from the system", dns.length() == 0 ? "none" : dns);
                    kv(b, "Search domains", link.getDomains() == null ? "none" : link.getDomains());
                }
            }
        } catch (Exception e) {
            kv(b, "Network", "could not be read: " + e.getMessage());
        }
        b.append("\nWhat the runtime was given\n--------------------------\n");
        File resolv = new File(LinuxRuntime.rootDir(context), "etc/resolv.conf");
        if (resolv.isFile()) {
            b.append("etc/resolv.conf:\n");
            for (String line : readFile(resolv).split("\n")) b.append("    ").append(line).append('\n');
        } else {
            b.append("etc/resolv.conf does not exist yet.\n");
        }
        // Addresses as their kind ("<public IPv6>"), never the numbers: a public address is the
        // user's, and this file goes into a folder meant to be shared as it is.
        write(target, LogRedactor.INSTANCE.describeAddresses(b.toString()));
    }

    /** This process's own logcat, streamed to {@code target} until {@link #stopAppLog()}. */
    public static synchronized void startAppLog(File target) {
        stopAppLog();
        try {
            write(target, "The app's own log for this session (logcat, this process only).\n"
                    + "Lines the app wrote before the session folder existed are in logcat only.\n\n");
            ProcessBuilder pb = new ProcessBuilder("/system/bin/logcat", "-v", "threadtime", "-T", "1",
                    "--pid=" + android.os.Process.myPid());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(target));
            appLog = pb.start();
            Log.i(TAG, "app log -> " + target);
        } catch (Exception e) {
            Log.w(TAG, "could not start the app log", e);
        }
    }

    public static synchronized void stopAppLog() {
        Process p = appLog;
        appLog = null;
        if (p != null) p.destroy();
    }

    // ---- end of session ---------------------------------------------------------------------

    /**
     * Everything that is only known once the session is over. Runs whatever ended it - the
     * previous script-side copy only ran on a clean exit, which is why a crashed session's bundle
     * arrived almost empty.
     */
    public static void collect(Context context, File dir, File pulseLog) {
        if (dir == null) return;
        try {
            // Cheapest and most valuable first: a session that ends while this runs still has these.
            dumpCrashBuffer(new File(dir, "crash.log"));
            if (pulseLog != null && pulseLog.isFile()) copyLines(pulseLog, new File(dir, "audio.log"), false, 0);
            // Whose logs these are, before a line of the client's is copied.
            LogRedactor.INSTANCE.learnFromRuntime(LinuxRuntime.rootDir(context));
            File logs = new File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs");
            File[] files = logs.isDirectory() ? logs.listFiles() : null;
            if (files != null) {
                File out = new File(dir, "steam");
                //noinspection ResultOfMethodCallIgnored
                out.mkdirs();
                // Smallest first, so the many small logs are all in before the few big ones; and a big
                // one is reduced to its tail - the end of a log is what a diagnosis reads, and the first
                // bundle lost twenty files and the crash buffer to scrubbing megabytes of old lines.
                java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::length));
                int n = 0;
                for (File src : files) {
                    if (!src.isFile()) continue;
                    String name = src.getName();
                    if (name.equals("loginusers.vdf") || name.equals("config.vdf") || name.startsWith("ssfn")) continue;
                    boolean big = src.length() > TAIL_ABOVE_BYTES;
                    if (copyLines(src, new File(out, name), true, big ? TAIL_LINES : 0)) n++;
                }
                Log.i(TAG, "collected " + n + " Steam log(s), scrubbed, into " + out);
            }
            // The app's own logcat has to stop before its file is rewritten.
            stopAppLog();
            scrubFolder(dir);
        } catch (Exception e) {
            Log.w(TAG, "collecting session artifacts", e);
        } finally {
            stopAppLog();
            if (current == dir) current = null;
        }
    }

    /**
     * The folder's own files (session.log, app.log, device.txt, fake-input.txt, ...) were written
     * as they happened, unscrubbed; once the session is over every one goes through the redactor
     * so the folder can be shared as it is. A file is rewritten only when a line changed. steam/
     * was scrubbed on the way in, and a file that is not text, or is huge, is left alone.
     */
    private static void scrubFolder(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        int rewritten = 0;
        for (File f : files) {
            if (!f.isFile() || f.getName().startsWith(".") || !LogRedactor.INSTANCE.isText(f)) continue;
            File tmp = new File(dir, "." + f.getName() + ".scrub");
            boolean changed = false;
            try (BufferedReader r = new BufferedReader(new FileReader(f));
                 BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String clean = LogRedactor.INSTANCE.redact(line);
                    if (!changed && !clean.equals(line)) changed = true;
                    w.write(clean);
                    w.newLine();
                }
            } catch (IOException e) {
                Log.w(TAG, "could not scrub " + f.getName(), e);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                continue;
            }
            if (changed && tmp.renameTo(f)) {
                rewritten++;
            } else {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        if (rewritten > 0) Log.i(TAG, "scrubbed " + rewritten + " session file(s) in " + dir.getName());
    }

    private static final long TAIL_ABOVE_BYTES = 512L * 1024;
    private static final int HEAD_LINES = 600;
    private static final int TAIL_LINES = 3000;

    /**
     * Copies {@code src} to {@code dst} line by line, through the redactor when {@code scrub},
     * keeping only the first {@link #HEAD_LINES} and the last {@code tailLines} lines when that is
     * above zero.
     *
     * <p>The head is kept because a whole class of fault only ever shows at start-up and is gone
     * from the tail by the time a session ends: the Steam client's GPU process dying on its way up
     * writes its reason in the first hundreds of lines of {@code cef_log.txt} and nowhere else, and
     * a tail-only bundle could not answer whether that had happened.
     */
    private static boolean copyLines(File src, File dst, boolean scrub, int tailLines) {
        try {
            java.util.List<String> head = null;
            java.util.List<String> lines = null;
            long total = 0;
            if (tailLines > 0) {
                head = new java.util.ArrayList<>(HEAD_LINES);
                java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>(tailLines + 1);
                try (BufferedReader r = new BufferedReader(new FileReader(src))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        total++;
                        if (head.size() < HEAD_LINES) head.add(line);
                        tail.addLast(line);
                        if (tail.size() > tailLines) tail.removeFirst();
                    }
                }
                lines = new java.util.ArrayList<>(tail);
                // Short enough that the two halves would overlap: the head already has all of it.
                if (total <= HEAD_LINES + tailLines) { head = null; }
            }
            try (BufferedWriter w = new BufferedWriter(new FileWriter(dst))) {
                if (lines != null) {
                    if (head != null) {
                        w.write("[first " + head.size() + " lines of " + src.length() + " bytes]");
                        w.newLine();
                        for (String line : head) { w.write(scrub ? LogRedactor.INSTANCE.redact(line) : line); w.newLine(); }
                        w.write("[... " + (total - head.size() - lines.size()) + " lines omitted ...]");
                        w.newLine();
                    }
                    w.write("[last " + lines.size() + " lines of " + src.length() + " bytes]");
                    w.newLine();
                    for (String line : lines) { w.write(scrub ? LogRedactor.INSTANCE.redact(line) : line); w.newLine(); }
                } else {
                    try (BufferedReader r = new BufferedReader(new FileReader(src))) {
                        String line;
                        while ((line = r.readLine()) != null) { w.write(scrub ? LogRedactor.INSTANCE.redact(line) : line); w.newLine(); }
                    }
                }
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "could not copy " + src.getName(), e);
            return false;
        }
    }

    private static void dumpCrashBuffer(File target) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/logcat", "-b", "crash", "-d", "-v", "threadtime", "-t", "400");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line).append('\n');
            }
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.destroy();
            write(target, "Android's crash buffer, as it stood when this session ended.\n"
                    + "Not only this app: anything on the device that crashed is in here, which is the\n"
                    + "point - a session killed by the system leaves its trace here and nowhere else.\n\n"
                    + (body.length() > 0 ? body : "(empty - nothing had crashed)\n"));
        } catch (Exception e) {
            Log.w(TAG, "could not read the crash buffer", e);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void h(StringBuilder b, String title) {
        b.append('\n').append(title).append('\n');
        for (int i = 0; i < title.length(); i++) b.append('-');
        b.append('\n');
    }

    private static void kv(StringBuilder b, String key, Object value) {
        b.append(String.format(Locale.US, "%-24s", key)).append(value == null ? "unknown" : value).append('\n');
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes; String[] u = {"KB", "MB", "GB", "TB"}; int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    private static String readSys(String path) {
        return readFile(new File(path));
    }

    private static String readFile(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void write(File target, String text) {
        try (FileWriter w = new FileWriter(target)) {
            w.write(text);
        } catch (IOException e) {
            Log.w(TAG, "could not write " + target, e);
        }
    }
}
