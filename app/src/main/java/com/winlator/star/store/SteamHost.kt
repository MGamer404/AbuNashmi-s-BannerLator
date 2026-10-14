package com.winlator.star.store

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.winlator.star.store.blsteam.CaBundleExtractor
import java.io.File

/**
 * Supervisor for the app's genuine Steam session host — the `bl-steam-host` executable
 * (packaged as `libblsteamhost.so`, exec'd from `nativeLibraryDir` like the unpack tools) that
 * loads Valve's `androidarm64` `libsteamclient.so` ([SteamHostComponent]) in its OWN process,
 * logs the account in with the engine's refresh token and serves the `Steam3Master` /
 * `SteamClientService` loopback listeners for a `launchMode=AppSteam` game.
 *
 * Why a separate process: the client's private vtables can move with a Valve build; a bad slot
 * or a Valve-side abort then kills the host, never the app (GameNative runs its bootstrap the
 * same way). The host reports over the [SteamAgentChannel] the launch activity already owns
 * (`BL_AGENT_PORT`; events `started` / `logged_in` / `login_failed` / `appinfo` / `ownership` /
 * `host_ready` / `session_lost` / `shutdown`), so the launch overlay's reassurance lines and
 * failure cards work unchanged.
 *
 * Credentials go to the host as environment variables (never argv), which the host scrubs from
 * its environment block immediately and makes itself non-dumpable — nothing in this class logs
 * them (the token is a registered [SteamLogRedactor] secret regardless).
 *
 * One host at a time (the loopback ports are fixed). [start] reaps a stale host left by a crash
 * before launching a new one; [stop] sends SIGTERM (the host logs off cleanly) and falls back to
 * destroy.
 */
object SteamHost {

    private const val TAG = "BH_STEAMHOST"
    private const val BINARY = "libblsteamhost.so"
    private const val PID_PREFS = "steam_host"

    /** Everything the host needs for one launch. Built by [AppSteamLauncher]. */
    class Config(
        val appId: Int,
        /** Per-SteamID HOME for the client (`<HOME>/Steam/config/…` = its persistent state). */
        val home: File,
        /** Where the host's stdout goes (the per-launch host log, collected into the SteamLite bundle). */
        val logFile: File,
        val steam3Master: String,
        val steamClientService: String,
        /** Agent-channel port the host connects its status stream to (0 = none). */
        val agentPort: Int,
        /** SetPersonaState(Online) after logon — only when the user opted into friends/chat. */
        val persona: Boolean,
        /** `STEAM_BASE_FOLDER` / `BREAKPAD_DUMP_LOCATION` etc. passed through to the client. */
        val extraEnv: Map<String, String>,
    )

    @Volatile private var process: Process? = null
    @Volatile private var pid: Int = 0
    @Volatile var lastConfig: Config? = null
        private set

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, BINARY)
    fun isAvailable(context: Context): Boolean = binary(context).isFile
    fun isAlive(): Boolean = process?.isAlive == true

    /** Per-SteamID state root: `{filesDir}/imagefs/.steamhost/<steamid64>/`. */
    fun stateDir(context: Context, steamId64: Long): File =
        File(context.filesDir, "imagefs/.steamhost/$steamId64")

    /**
     * Launch the host. MUST be called off the main thread. Returns false (with the reason logged)
     * when the binary / Valve client / credentials are missing or the process could not start.
     * The caller waits for the `logged_in` / `host_ready` events on its agent channel.
     */
    fun start(context: Context, cfg: Config, token: String, account: String, steamId64: Long): Boolean {
        val app = context.applicationContext
        val bin = binary(app)
        if (!bin.isFile) { Log.w(TAG, "host binary missing at ${bin.absolutePath}"); return false }
        val lib = SteamHostComponent.libSteamClient(app)
        if (!lib.isFile) { Log.w(TAG, "Valve client not installed (${lib.absolutePath})"); return false }
        if (token.isEmpty() || account.isEmpty() || steamId64 == 0L) { Log.w(TAG, "no credentials for the host"); return false }
        stop("restart")
        reapStale(app)

        cfg.home.mkdirs()
        cfg.logFile.parentFile?.mkdirs()
        cfg.logFile.delete()
        val cacert = runCatching { CaBundleExtractor.ensureBundle(app) }.getOrDefault(File(app.filesDir, "blsteam_cacert.pem").absolutePath)

        val pb = ProcessBuilder(bin.absolutePath)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(cfg.logFile))
        pb.directory(cfg.home)
        val env = pb.environment()
        // Start from a clean slate: nothing of the app process's own env (Wine-ish leftovers) leaks.
        env.clear()
        env["PATH"] = "/system/bin"
        env["HOME"] = cfg.home.absolutePath
        env["TMPDIR"] = File(app.filesDir, "imagefs/usr/tmp").absolutePath
        // Valve's siblings next to libsteamclient.so — and NOTHING else. The host links libc++
        // statically and needs only libc/libdl/libm/liblog, so the app's own nativeLibraryDir
        // must stay off the path: it ships a liblzma.so (archive tools) that would shadow
        // Android's /system/lib64/liblzma.so for the system libunwindstack that Valve's libs pull
        // in, and the preloads then fail with `cannot locate symbol "Xzs_Construct"`.
        env["LD_LIBRARY_PATH"] = lib.parentFile!!.absolutePath
        env["BL_STEAM_HOST_LIB"] = lib.absolutePath
        env["BL_STEAM_HOST_HOME"] = cfg.home.absolutePath
        env["BL_STEAM_HOST_CACERT"] = cacert
        env["BL_STEAM_HOST_LIB_VERSION"] = SteamHostComponent.installedVersion(app)
        env["BL_STEAM_HOST_APPID"] = cfg.appId.toString()
        env["BL_STEAM_HOST_PERSONA"] = if (cfg.persona) "1" else "0"
        env["Steam3Master"] = cfg.steam3Master
        env["SteamClientService"] = cfg.steamClientService
        if (cfg.agentPort > 0) env["BL_AGENT_PORT"] = cfg.agentPort.toString()
        // The client's own identity gate ("preallocated environment variable 'SteamUser' not
        // found") — same value the game gets.
        env["SteamUser"] = account
        env["SteamAppUser"] = account
        for ((k, v) in cfg.extraEnv) env[k] = v
        // Credentials last; the host scrubs them from its environment block on startup.
        env["BL_STEAM_TOKEN"] = token
        env["BL_STEAM_ACCOUNT"] = account
        env["BL_STEAM_STEAMID64"] = steamId64.toString()

        return try {
            val p = pb.start()
            process = p
            pid = pidOf(p) ?: 0
            lastConfig = cfg
            rememberPid(app, pid)
            Log.i(TAG, "host started (pid $pid, appId ${cfg.appId}, home ${cfg.home.absolutePath}, log ${cfg.logFile.name}, agentPort ${cfg.agentPort})")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "host start failed", t)
            process = null
            false
        }
    }

    /**
     * Stop the host: SIGTERM (clean `Steam_LogOff` → `ReleaseUser` → `ReleasePipe`), wait up to
     * [gracefulMs], then destroy. Idempotent; safe from any thread (blocks up to gracefulMs + 2 s).
     */
    @JvmOverloads
    fun stop(why: String, gracefulMs: Long = 6_000L) {
        val p = process ?: return
        process = null
        val id = pid
        pid = 0
        Log.i(TAG, "stopping host (pid $id, $why)")
        if (id > 0) runCatching { Os.kill(id, OsConstants.SIGTERM) }
        waitExit(p, gracefulMs)
        if (p.isAlive) {
            Log.w(TAG, "host did not exit in ${gracefulMs}ms — destroying")
            runCatching { p.destroy() }
            waitExit(p, 2_000L)
            if (p.isAlive) runCatching { p.destroyForcibly() }
        }
        Log.i(TAG, "host stopped (exit ${runCatching { p.exitValue() }.getOrDefault(-1)})")
    }

    /**
     * Kill any host left behind by an earlier app process: the pid remembered in prefs, plus ANY
     * live process of ours running the host binary (a force-stop can leave the pref cleared while
     * the host lingers for a few seconds holding :57343 — the new host then exits "port_busy" and
     * the game launches without a Steam session). Then wait, bounded, for the port to free.
     */
    private fun reapStale(app: Context) {
        val prefs = app.getSharedPreferences(PID_PREFS, Context.MODE_PRIVATE)
        val pids = LinkedHashSet<Int>()
        prefs.getInt("pid", 0).takeIf { it > 0 }?.let { pids.add(it) }
        prefs.edit().remove("pid").apply()
        try {
            val me = android.os.Process.myPid()
            File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }?.forEach { d ->
                val pid = d.name.toInt()
                if (pid == me) return@forEach
                val cmdline = runCatching { File(d, "cmdline").readText() }.getOrDefault("")
                if (cmdline.contains(BINARY)) pids.add(pid)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stale-host scan failed", t)
        }
        for (stale in pids) {
            try {
                val cmdline = File("/proc/$stale/cmdline").takeIf { it.exists() }?.readText().orEmpty()
                if (!cmdline.contains(BINARY)) continue
                Log.w(TAG, "reaping stale host pid $stale")
                runCatching { Os.kill(stale, OsConstants.SIGTERM) }
                Thread.sleep(300)
                if (File("/proc/$stale").exists()) runCatching { Os.kill(stale, OsConstants.SIGKILL) }
            } catch (t: Throwable) {
                Log.w(TAG, "stale-host check failed", t)
            }
        }
        // The kernel may keep the listener a moment after the owner dies; the host refuses to start
        // while it is held, so wait (bounded) for Steam3Master to be bindable again.
        // A force-stopped session's host does its 6 s graceful logoff first; wait that out.
        val deadline = System.currentTimeMillis() + 12_000L
        while (System.currentTimeMillis() < deadline) {
            if (portFree(57343)) return
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
        Log.w(TAG, "Steam3Master port still busy after the stale-host reap")
    }

    private fun portFree(port: Int): Boolean =
        runCatching { java.net.ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress("127.0.0.1", port)); true } }.getOrDefault(false)

    private fun rememberPid(app: Context, id: Int) {
        runCatching { app.getSharedPreferences(PID_PREFS, Context.MODE_PRIVATE).edit().putInt("pid", id).apply() }
    }

    private fun waitExit(p: Process, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (p.isAlive && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { return }
        }
    }

    private fun pidOf(p: Process): Int? =
        runCatching { p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) }.getOrNull()
}
