package com.winlator.star.winhandler;

import android.content.Context;
import android.util.Log;

import com.winlator.star.BuildConfig;
import com.winlator.star.container.Container;
import com.winlator.star.core.FileUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Brings the guest helper bltextfocus.exe (app/src/main/cpp/bltextfocus) into the running Wine session and turns
 * its datagrams (request code {@link RequestCodes#TEXT_FOCUS}) into {@link Listener} calls. The Java X server
 * cannot see which Windows control owns the keyboard focus; the helper polls it inside the prefix and reports
 * when a text field gets or loses it, so the XMB keyboard can show itself.
 *
 * The helper is a CI-built asset ({@code assets/bltextfocus.exe}); a build without it (a local build) just makes
 * {@link #start} return false.
 */
public final class TextFocusHelper {
    private static final String TAG = "TextFocusHelper";

    /** Bump when the helper's behaviour changes; the staged copy is also compared byte for byte. */
    private static final String HELPER_VERSION = "1";
    private static final String HELPER_EXE = "bltextfocus.exe";
    private static final String HELPER_MARKER = ".bltextfocus_version";
    /** winhandler.exe runs the exe by full path; the whole command stays under {@link WinHandler#EXEC_RAW_MAX}. */
    private static final String HELPER_PATH = "C:\\windows\\" + HELPER_EXE;

    static final int PACKET_LENGTH = 30;

    public interface Listener {
        /** Called on the MAIN thread when the focused Windows control becomes/stops being a text field. editStyle = EDIT window style flags (ES_NUMBER=0x2000, ES_PASSWORD=0x20, ES_MULTILINE=0x4, ES_READONLY=0x800), 0 if unknown. */
        void onTextFocus(boolean active, int editStyle, int caretX, int caretY, int caretW, int caretH, int pid);
    }

    /** One decoded datagram. The caret rectangle is in Windows screen coordinates, all zero when there is no caret. */
    public static final class Packet {
        public final boolean active;
        public final int editStyle;
        public final int caretX;
        public final int caretY;
        public final int caretW;
        public final int caretH;
        public final int pid;

        Packet(boolean active, int editStyle, int caretX, int caretY, int caretW, int caretH, int pid) {
            this.active = active;
            this.editStyle = editStyle;
            this.caretX = caretX;
            this.caretY = caretY;
            this.caretW = caretW;
            this.caretH = caretH;
            this.pid = pid;
        }
    }

    private final Context context;
    private final Container container;
    private final WinHandler winHandler;
    private Listener listener;
    private boolean started;

    public TextFocusHelper(Context ctx, Container container, WinHandler winHandler) {
        Context app = ctx != null ? ctx.getApplicationContext() : null;
        this.context = app != null ? app : ctx;
        this.container = container;
        this.winHandler = winHandler;
    }

    public synchronized void setListener(Listener l) {
        listener = l;
        if (winHandler != null) winHandler.setTextFocusListener(l);
    }

    /** Stages the guest helper exe into the prefix if needed and launches it inside the running session; false when the asset is missing or the launch failed (never throws). */
    public synchronized boolean start() {
        try {
            if (context == null || container == null || winHandler == null) return false;
            if (started) return true;
            if (!stageHelper()) return false;
            winHandler.setTextFocusListener(listener);
            // A copy left over from an earlier run would report twice, so it goes first (the queue keeps the order).
            winHandler.killProcess(HELPER_EXE);
            // -v (log to C:\bltextfocus.log) only in debug builds.
            if (!winHandler.execRaw(HELPER_PATH, BuildConfig.DEBUG ? "-v" : "")) {
                Log.w(TAG, "helper command too long for winhandler.exe");
                return false;
            }
            started = true;
            Log.i(TAG, "started " + HELPER_EXE + " " + HELPER_VERSION);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "start failed", e);
            return false;
        }
    }

    /** Stops polling/process and drops the listener. Idempotent. */
    public synchronized void stop() {
        try {
            listener = null;
            if (winHandler != null) winHandler.setTextFocusListener(null);
            if (started) {
                started = false;
                winHandler.killProcess(HELPER_EXE);
            }
        } catch (Exception e) {
            Log.w(TAG, "stop failed", e);
        }
    }

    /**
     * Decodes one datagram (the request code first, little endian, as the guest sends it): code, "BTF1", active,
     * editStyle, caret x / y / w / h, pid; 30 bytes. Null for anything shorter, with another code or magic, or
     * with an active byte that is not 0 or 1.
     */
    public static Packet parsePacket(byte[] data, int length) {
        if (data == null || length < PACKET_LENGTH || data.length < PACKET_LENGTH) return null;
        if (data[0] != RequestCodes.TEXT_FOCUS) return null;
        if (data[1] != 'B' || data[2] != 'T' || data[3] != 'F' || data[4] != '1') return null;
        int active = data[5];
        if (active != 0 && active != 1) return null;
        return new Packet(active == 1, readInt(data, 6), readInt(data, 10), readInt(data, 14), readInt(data, 18),
                readInt(data, 22), readInt(data, 26));
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    // Copy assets/bltextfocus.exe into this container's drive_c\windows, the way XServerDisplayActivity stages
    // winhandler.exe and wfm.exe: a marker file beside the exe records the version, and the bytes are compared too
    // because the container pattern re-extract can put an older copy back under an up-to-date marker. False when
    // the asset is not in this build or the copy failed; an exe already there is then left alone.
    private boolean stageHelper() {
        try {
            File windowsDir = new File(container.getRootDir(), ".wine/drive_c/windows");
            if (!windowsDir.isDirectory() && !windowsDir.mkdirs()) {
                Log.w(TAG, "windows dir unavailable: " + windowsDir);
                return false;
            }
            File exeFile = new File(windowsDir, HELPER_EXE);
            File marker = new File(windowsDir, HELPER_MARKER);

            ByteArrayOutputStream bundledBytes = new ByteArrayOutputStream();
            try (InputStream in = context.getAssets().open(HELPER_EXE)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bundledBytes.write(buf, 0, n);
            } catch (java.io.FileNotFoundException e) {
                // A local build has no CI-built helper: the feature is simply unavailable.
                Log.i(TAG, HELPER_EXE + " is not bundled in this build");
                return false;
            }
            byte[] bundled = bundledBytes.toByteArray();
            if (bundled.length == 0) return false;

            boolean upToDate = exeFile.isFile() && marker.isFile()
                    && HELPER_VERSION.equals(FileUtils.readString(marker).trim())
                    && exeFile.length() == bundled.length
                    && Arrays.equals(Files.readAllBytes(exeFile.toPath()), bundled);
            if (upToDate) return true;

            // Copy to a temp sibling then rename, so a crash mid-copy cannot leave a truncated exe.
            File tmp = new File(windowsDir, HELPER_EXE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bundled);
            }
            if (!tmp.isFile() || tmp.length() == 0) {
                Log.w(TAG, "staged " + HELPER_EXE + " empty, skipping");
                tmp.delete();
                return false;
            }
            // renameTo won't overwrite on some Android fs layers: clear the old exe first.
            if (exeFile.exists()) exeFile.delete();
            if (!tmp.renameTo(exeFile)) {
                Log.w(TAG, "rename into place failed for " + HELPER_EXE);
                tmp.delete();
                return false;
            }
            exeFile.setReadable(true, false);
            exeFile.setExecutable(true, false);
            FileUtils.writeString(marker, HELPER_VERSION);
            Log.i(TAG, "staged " + HELPER_EXE + " " + HELPER_VERSION + " -> " + exeFile);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "staging " + HELPER_EXE + " failed", e);
            return false;
        }
    }
}
