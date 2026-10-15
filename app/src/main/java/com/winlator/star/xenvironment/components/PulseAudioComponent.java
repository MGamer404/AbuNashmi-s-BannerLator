package com.winlator.star.xenvironment.components;

import android.content.Context;
import android.os.Process;

import com.winlator.star.core.AppUtils;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.ProcessHelper;
import com.winlator.star.xconnector.UnixSocketConfig;
import com.winlator.star.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    /**
     * A named pipe carrying microphone audio, or null for no microphone.
     *
     * <p>The bundle ships module-aaudio-sink but no matching source, which is why the Steam client
     * reports "No input devices detected" and its voice chat has nothing to record with. The relay
     * helper already owns an Android input stream - opened under the app's own uid, the only way
     * Android permits recording - so pointing module-pipe-source at a pipe the helper writes turns
     * that one stream into a source the client can see. The helper fans the same microphone out to
     * the games capturing through the relay as well, so both hear the same thing.
     */
    private final String micFifoPath;
    private static int pid = -1;
    private static final Object lock = new Object();

    static {
        try { System.loadLibrary("pasink"); }
        catch (Throwable t) { android.util.Log.w("PulseAudio", "pasink native lib unavailable", t); }
    }

    // Suspend (true) or resume (false) the named sink via the 13.0 libpulse client dlopen'd from
    // pulseDir. server is the native socket address ("unix:/path/to/PS0"). Returns 0 on success.
    // Implemented in cpp/pasink/pasink.c — never restarts the daemon, so the guest's audio connection
    // survives; safe to call on pause/resume (never at startup).
    private static native int nativeSuspendSink(String pulseDir, String server, String sink, boolean suspend);

    // Recreate the guest sink onto the CURRENT output route and move the guest's streams onto it, then
    // unload the previous recovery sink (unloadModuleIdx, or < 0 to skip). Returns the NEW module index
    // (>= 0) to feed back as unloadModuleIdx next time; negative on error. Implemented in pasink.c.
    private static native int nativeRecreateSink(String pulseDir, String server, String newSinkName, String extraArgs, int unloadModuleIdx);

    /**
     * Resolve the module-aaudio-sink argument string from the saved audio settings (written by the
     * in-app audio presets/fine-tune UI). Defaults to the "Auto / Smart" preset: AAudio LOW_LATENCY
     * start + adaptive buffer growth on underruns. Keys live in the PER-ENGINE "banner_audio_pulseaudio"
     * prefs (NOT the shared store) so Pulse settings can never bleed into/out of the ALSA path; the same
     * string is used for the initial default.pa load AND route-change recovery.
     */
    private String resolveSinkArgs() {
        try {
            android.content.SharedPreferences p = environment.getContext()
                    .getSharedPreferences("banner_audio_pulseaudio", android.content.Context.MODE_PRIVATE);
            int perf = p.getInt("perf_mode", 1);            // 0=NONE, 1=LOW_LATENCY, 2=POWER_SAVING
            boolean adaptive = p.getBoolean("adaptive", true);
            int bf = p.getInt("buffer_frames", 0);          // 0 = auto (framesPerBurst*2)
            int mbf = p.getInt("max_buffer_frames", 0);     // 0 = device capacity
            StringBuilder sb = new StringBuilder();
            sb.append("performance_mode=").append(perf).append(" adaptive=").append(adaptive ? 1 : 0);
            if (bf > 0) sb.append(" buffer_frames=").append(bf);
            if (mbf > 0) sb.append(" max_buffer_frames=").append(mbf);
            // ALWAYS pass volume=1.0 (unity). module-aaudio-sink has a latent bug: with no volume arg
            // it defaults u->volume to 0.0 and force-sets the sink to 0% → silence. Passing 1.0 keeps
            // it at unity (the game controls its own volume). GameNative/WinNative also always pass it.
            sb.append(" volume=1.0");
            return sb.toString();
        } catch (Throwable t) {
            return "performance_mode=1 adaptive=1 volume=1.0"; // Auto/Smart fallback (unity volume)
        }
    }

    // Bookkeeping for the recreate path: the module index of the sink we created last (to unload on the
    // next route change) and a monotonic counter for unique sink names. Guarded by recoverLock.
    private static final Object recoverLock = new Object();
    private static int lastRecoverModuleIdx = -1;
    private static int recoverCounter = 0;
    // Live sink name the guest is playing through — starts "AAudioSink", becomes recoverN after
    // any route-change recreate. suspend/resume must target THIS, not a stale hardcoded name.
    private static volatile String currentSinkName = "AAudioSink";

    public PulseAudioComponent(UnixSocketConfig socketConfig) {
        this(socketConfig, null);
    }

    /** As above, with a microphone fed from {@code micFifoPath}; null for output only. */
    public PulseAudioComponent(UnixSocketConfig socketConfig, String micFifoPath) {
        this.socketConfig = socketConfig;
        this.micFifoPath = micFifoPath;
    }

    /** The DirectAudio relay's socket when the daemon's output should go through it, else null. */
    private String relaySocketPath;

    /**
     * Route the daemon's output through the DirectAudio relay at this socket instead of an AAudio stream of its own.
     * Only a Linux session asks for this, when DirectAudio is chosen: the Steam client's own sound then takes the same road as its games.
     * The relay owns the Android stream outside proot with its adaptive buffer, and the daemon only fills a shared ring, which is what cures the choppy client sound.
     * Set before {@link #start()}; the relay may start after the daemon. (module-directaudio-sink, from The412Banner/SteamDeck.)
     */
    public void setRelaySocket(String path) {
        this.relaySocketPath = path;
    }

    private File pulseDir() { return new File(environment.getContext().getFilesDir(), "pulseaudio"); }
    private String pulseServer() { return "unix:" + socketConfig.path; }

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            pid = execPulseAudio();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    /**
     * Recover audio after a background/foreground or HDMI route change, WITHOUT restarting the daemon
     * (which would break the guest's still-open audio connection). Suspending then resuming the sink
     * closes and reopens its AAudio output stream, re-grabbing the current default route — the guest's
     * streams stay attached to the sink the whole time. Driven by the native pasink client talking to
     * the daemon over its native socket (proven on-device: a libpulse client drives the 13.0 daemon
     * fine). Deliberately does NOT restart the daemon on failure. Call off the main thread.
     */
    public void resetAudioSink() {
        String dir = pulseDir().getAbsolutePath(), server = pulseServer();
        try {
            int r1 = nativeSuspendSink(dir, server, currentSinkName, true);
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            int r2 = nativeSuspendSink(dir, server, currentSinkName, false);
            if (r1 != 0 || r2 != 0) android.util.Log.w("PulseAudio", "sink suspend/resume rc=" + r1 + "/" + r2);
        } catch (Throwable t) {
            android.util.Log.w("PulseAudio", "resetAudioSink failed", t);
        }
    }

    /**
     * Suspend or resume the sink around a background transition — GameNative's "prevent the drop":
     * suspend the sink BEFORE the app is frozen, resume AFTER it returns, so the AAudio output stream
     * is cleanly re-opened onto the current route instead of dying while backgrounded. Call off the
     * main thread. No-op safe if the daemon isn't reachable.
     */
    public void setSinkSuspended(boolean suspend) {
        try { nativeSuspendSink(pulseDir().getAbsolutePath(), pulseServer(), currentSinkName, suspend); }
        catch (Throwable t) { android.util.Log.w("PulseAudio", "setSinkSuspended failed", t); }
    }

    /**
     * Recover audio after a MID-PLAY output-route change (headphones/USB/BT/HDMI plug or unplug). Unlike
     * a background drop, a route change DISCONNECTS the AAudio stream, and a disconnected AAudio stream
     * can never be restarted — so suspend/resume (resetAudioSink) cannot recover it. Instead we build a
     * brand-new sink whose AAudio stream opens on the current route, move the guest's streams onto it,
     * make it default, and unload the previous (now-dead) recovery sink. The daemon and the guest's
     * audio connection stay alive throughout (proven live with pactl against the 13.0 daemon). Call off
     * the main thread. No-op safe if the daemon isn't reachable.
     */
    public void recreateSinkForRouteChange() {
        // With the DirectAudio relay sink, the Android stream belongs to the relay outside proot, and the relay reopens it on the new route itself.
        // Building an AAudio recovery sink here would move the client's sound off DirectAudio behind its back.
        if (relaySocketPath != null && !relaySocketPath.isEmpty()) return;
        String dir = pulseDir().getAbsolutePath(), server = pulseServer();
        synchronized (recoverLock) {
            String name = "recover" + (++recoverCounter);
            try {
                int idx = nativeRecreateSink(dir, server, name, resolveSinkArgs(), lastRecoverModuleIdx);
                if (idx >= 0) { lastRecoverModuleIdx = idx; currentSinkName = name; }
                else android.util.Log.w("PulseAudio", "recreateSink rc=" + idx);
            } catch (Throwable t) {
                android.util.Log.w("PulseAudio", "recreateSinkForRouteChange failed", t);
            }
        }
    }
    
    private void copyFromLibraryDir(File dst) {
        String[] libs = new String[] {
            "libltdl.so", "libpulseaudio.so", "libpulse.so", "libpulsecommon-13.0.so", "libpulsecore-13.0.so", "libsndfile.so"
        };
        for (int i = 0; i < libs.length; i++) {
            String path = "lib/" + "arm64-v8a" + "/" + libs[i];
            ClassLoader loader = PulseAudioComponent.class.getClassLoader();
            URL res = loader != null ? loader.getResource(path) : null;
            Path dstDir = Paths.get(dst.getAbsolutePath() + "/" + libs[i]);
            try {
                InputStream is = res != null ? res.openStream() : null;
                if (is != null) {
                    Files.copy(is, dstDir, StandardCopyOption.REPLACE_EXISTING);
                    FileUtils.chmod(dstDir.toFile(), 0771);
                }    
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private int execPulseAudio() {
        Context context = environment.getContext();
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        // Clean up any stale CLI socket from older builds (the cli-module control path is gone — the
        // bundled cli module was a 17.0 build that never loaded into the 13.0 daemon; sink suspend/
        // resume now goes through the native pasink libpulse client instead).
        new File(workingDir, "cli").delete();

        // module-pipe-source creates the pipe with mkfifo and fails outright if one is already
        // there - EEXIST, reported as "Unknown error 17" - and the module then does not load at
        // all, so the source never appears and the client reports no microphone. Ours lives in the
        // app's files directory and survives a session, so after the very first run the path was
        // always occupied. Removed here, before the daemon reads this config: the daemon makes it,
        // and the relay helper starts afterwards and is content to find one already made.
        if (micFifoPath != null && !micFifoPath.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            new File(micFifoPath).delete();
        }

        File configFile = new File(workingDir, "default.pa");
        java.util.List<String> config = new ArrayList<>();
        config.add("load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\""+socketConfig.path+"\"");
        if (relaySocketPath != null && !relaySocketPath.isEmpty()) {
            // The sink's name is what the Steam client's audio settings show as the output device, so it says which road the sound takes.
            // volume=1.0 is passed for the same reason as on the AAudio sink below.
            config.add("load-module module-directaudio-sink sink_name=DirectAudio socket=\"" + relaySocketPath
                    + "\" performance_mode=1 adaptive=1 volume=1.0");
            config.add("set-default-sink DirectAudio");
            // Suspend and resume target the live sink by name.
            currentSinkName = "DirectAudio";
        } else {
            config.add("load-module module-aaudio-sink " + resolveSinkArgs());
            config.add("set-default-sink AAudioSink");
            currentSinkName = "AAudioSink";
        }
        if (micFifoPath != null && !micFifoPath.isEmpty()) {
            // The format is the helper's, fixed at s16le/48000/mono: it resamples when the device
            // grants another input rate, so the daemon is never told a rate the bytes are not.
            // A pipe has no clock, so nothing here corrects drift - acceptable for voice, which is
            // all this is for.
            config.add("load-module module-pipe-source source_name=DirectAudioMic file=\""
                    + micFifoPath + "\" format=s16le rate=48000 channels=1");
            config.add("set-default-source DirectAudioMic");
        }
        FileUtils.writeString(configFile, String.join("\n", config));

        String archName = AppUtils.getArchName();
        File modulesDir = new File(workingDir, "modules/"+archName);
        String systemLibPath = archName.equals("arm64") ? "/system/lib64" : "system/lib";

        ArrayList<String> envVars = new ArrayList<>();
        envVars.add("LD_LIBRARY_PATH="+systemLibPath+":"+modulesDir+":"+workingDir.getAbsolutePath());
        envVars.add("HOME="+workingDir);
        envVars.add("TMPDIR="+environment.getTmpDir());
        
        copyFromLibraryDir(workingDir);

        String command = workingDir.getAbsolutePath() + "/libpulseaudio.so";
        command += " --system=false";
        command += " --disable-shm=true";
        command += " --fail=false";
        command += " -n --file=default.pa";
        command += " --daemonize=false";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";
        // The daemon's own log, kept in its working directory. Its stderr is discarded by
        // ProcessHelper, so until now a module refusing to load, a pipe that could not be created
        // or the daemon exiting at startup left no trace anywhere - the Steam client just reported
        // no microphone. Four separate faults hid behind that one symptom in a single night. One
        // file per start, overwritten each time, small enough never to matter.
        command += " --log-level=info --log-target=file:" + new File(workingDir, "pulse.log").getAbsolutePath();

        return ProcessHelper.exec(command, envVars.toArray(new String[0]), workingDir);
    }
}
