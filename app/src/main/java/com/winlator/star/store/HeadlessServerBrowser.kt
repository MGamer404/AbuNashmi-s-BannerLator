package com.winlator.star.store

import android.util.Log
import com.winlator.star.store.blsteam.BlSteamEngine
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

/**
 * Server-list service for the Headless Steam server browser.
 *
 * Valve's Android `libsteamclient.so` has no working `ISteamMatchmakingServers`: the copy inside the
 * game hangs on its first `Request*ServerList` (tier0 "Thread synchronization object is unuseable")
 * and the copy inside our session host never completes a list either. The proton layer therefore
 * carries its own browser (`lsteamclient/unix_bl_server_browser.cpp`, `BL_SERVER_BROWSER=1`) which
 * asks THIS service for the list over loopback and pings the servers itself (A2S).
 *
 * The list comes from `IGameServersService/GetServerList` with a web-audience access token minted
 * by the engine from the saved refresh token — the same Web API the current Steam client's own
 * server browser uses — so no master-server protocol and no Valve browser code is involved.
 *
 * Protocol (one request per connection, text):
 *   `LIST <appid> <filter>` → `S <ip> <gameport> <queryport>` per server, then `END`
 *   (or `ERR <reason>`). `<filter>` is the Steam `\key\value` filter string the game passed.
 *
 * Lifetime = the host's: [start] from [AppSteamLauncher] once the host is up, [stop] with it.
 */
object HeadlessServerBrowser {
    private const val TAG = "BH_APPSTEAM_SB"
    private const val MAX_SERVERS = 5000
    private const val TOKEN_TTL_MS = 50 * 60 * 1000L

    private val server = AtomicReference<ServerSocket?>(null)
    @Volatile private var thread: Thread? = null

    private var cachedToken: String? = null
    private var cachedTokenAt = 0L

    @JvmStatic
    fun start(port: Int): Boolean {
        stop()
        val ss = try {
            // Explicit IPv4: getLoopbackAddress() is ::1 on some devices and the layer connects to 127.0.0.1.
            ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).also { it.reuseAddress = true }
        } catch (t: Throwable) {
            Log.w(TAG, "listen on 127.0.0.1:$port failed: $t"); return false
        }
        server.set(ss)
        thread = Thread({
            Log.i(TAG, "server-list service listening on 127.0.0.1:$port")
            while (server.get() === ss) {
                val s = try { ss.accept() } catch (_: Throwable) { break }
                Thread({ serve(s) }, "bl-sb-req").start()
            }
            Log.i(TAG, "server-list service stopped")
        }, "bl-sb-listen").apply { isDaemon = true; start() }
        return true
    }

    @JvmStatic
    fun stop() {
        val ss = server.getAndSet(null) ?: return
        try { ss.close() } catch (_: Throwable) {}
        thread = null
    }

    private fun serve(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 10_000
            val line = try {
                BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8)).readLine()
            } catch (_: Throwable) { null } ?: return
            val out = sock.getOutputStream()
            fun send(text: String) { out.write(text.toByteArray(Charsets.UTF_8)); out.flush() }
            val m = Regex("^LIST (\\d+) ?(.*)$").find(line.trim())
            if (m == null) { send("ERR bad request\n"); return }
            val appId = m.groupValues[1].toIntOrNull() ?: 0
            val filter = m.groupValues[2].trim()
            val t0 = System.currentTimeMillis()
            try {
                val servers = fetch(appId, filter)
                val sb = StringBuilder()
                for (sv in servers) sb.append("S ").append(sv.ip).append(' ').append(sv.gamePort).append(' ').append(sv.queryPort).append('\n')
                sb.append("END\n")
                send(sb.toString())
                Log.i(TAG, "LIST app=$appId filter=$filter -> ${servers.size} servers in ${System.currentTimeMillis() - t0} ms")
            } catch (t: Throwable) {
                Log.w(TAG, "LIST app=$appId failed: $t")
                try { send("ERR ${t.message ?: t.javaClass.simpleName}\n") } catch (_: Throwable) {}
            }
        }
    }

    private class Server(val ip: String, val gamePort: Int, val queryPort: Int)

    /** Blocking. Throws with a one-line reason on failure. */
    private fun fetch(appId: Int, filter: String): List<Server> {
        val token = mintToken() ?: throw IllegalStateException("no web access token (engine not signed in)")
        // The game's filter already carries \appid\N (the bridge adds it when missing); keep it first.
        val f = if (filter.contains("\\appid\\")) filter else "\\appid\\$appId$filter"
        val url = "https://api.steampowered.com/IGameServersService/GetServerList/v1/?access_token=" +
                URLEncoder.encode(token, "UTF-8") + "&filter=" + URLEncoder.encode(f, "UTF-8") + "&limit=$MAX_SERVERS"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        if (code == 401 || code == 403) { cachedToken = null }
        if (code != 200) throw IllegalStateException("GetServerList HTTP $code")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(body).optJSONObject("response")?.optJSONArray("servers") ?: return emptyList()
        val out = ArrayList<Server>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val addr = o.optString("addr", "")           // "ip:queryport"
            val colon = addr.lastIndexOf(':')
            if (colon <= 0) continue
            val ip = addr.substring(0, colon)
            val qport = addr.substring(colon + 1).toIntOrNull() ?: continue
            val gport = o.optInt("gameport", qport)
            out.add(Server(ip, if (gport > 0) gport else qport, qport))
        }
        return out
    }

    /** Web-audience access token from the engine (cached ~50 min). Null when the engine isn't signed in. */
    private fun mintToken(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now - cachedTokenAt < TOKEN_TTL_MS) return it }
        val repo = SteamRepository.getInstance()
        val steamId = try { repo.steamId64 } catch (_: Throwable) { 0L }
        val refresh = try { repo.refreshToken } catch (_: Throwable) { "" }
        if (steamId == 0L || refresh.isNullOrEmpty()) return null
        if (!repo.isRustEngine) return null
        try { repo.ensureLoggedIn(8_000L) } catch (_: Throwable) {}
        val session = BlSteamEngine.session() ?: return null
        val token = session.generateWebAccessToken(refresh, steamId) ?: return null
        cachedToken = token; cachedTokenAt = now
        return token
    }
}
