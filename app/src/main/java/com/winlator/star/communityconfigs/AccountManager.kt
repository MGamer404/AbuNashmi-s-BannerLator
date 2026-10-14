package com.winlator.star.communityconfigs

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.winlator.star.core.HttpUtils
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * PHASE 2 (optional accounts) — the client + local state for the OPTIONAL user-account system whose
 * worker backend is already live. Accounts exist ONLY to let a user recover / manage the community
 * configs they've shared; every anonymous flow keeps working unchanged when logged out.
 *
 * Networking mirrors [CommunityConfigWorker]: every call is BLOCKING (bridged off [HttpUtils]' async
 * executor via a latch) so it must run off the main thread, and every failure degrades to an
 * [AccountResult.Error] / null rather than throwing. Passwords are only ever sent to the worker over
 * HTTPS — they are NEVER logged and NEVER stored locally (only the returned session + the recovery key
 * are persisted).
 *
 * Local state lives in TWO places, same split as [UploadedConfigsStore]:
 *  - SharedPreferences [PREFS] — the fast hot-path copy of the logged-in session.
 *  - a durable backup file [BACKUP] under {@code Download/bannerlator/game-configs/} holding just
 *    {@code {username, user_id, recovery_key}} so the recovery key survives a reinstall (a logout does
 *    NOT delete it — only clears the session).
 */
object AccountManager {

    private const val TAG = "CommunityConfigs"
    private const val BASE = "https://bannerhub-configs-worker.the412banner.workers.dev"
    private const val PREFS = "banner_account"
    private const val BACKUP = "my-account.json"

    // --- result types ----------------------------------------------------------------------------

    /**
     * Outcome of an account network call. [Success] carries the parsed payload; [Error] carries the
     * worker's typed error code (e.g. {@code username_taken}, {@code weak_password}, {@code invalid},
     * {@code rate_limited}) or {@code network} when the request never reached the worker.
     */
    sealed class AccountResult<out T> {
        data class Success<T>(val data: T) : AccountResult<T>()
        data class Error(val code: String) : AccountResult<Nothing>()
    }

    /** Payload of a successful {@code /account/create} — [recoveryKey] is returned ONCE, save it. */
    data class CreateData(
        val userId: String,
        val username: String,
        val session: String,
        val recoveryKey: String,
    )

    /** Payload of a successful {@code /account/login} — includes the profile + the user's upload registry. */
    data class LoginData(
        val userId: String,
        val username: String,
        val session: String,
        val avatarUrl: String?,
        val uploads: List<AccountUpload>,
    )

    /**
     * One entry in the account's server-side upload registry (returned by {@code /account/login}). Carries
     * everything the client needs to restore a "My uploads" row on a fresh device: [sha] the worker key,
     * [game]/[filename] the identity, [token] the {@code upload_token} that minted it (so delete / edit-
     * description work again after recovery), and [ts] the UNIX-SECONDS upload time (0 when the worker
     * omitted it). Display-only provenance (soc/device) is parsed from [filename] by the caller.
     */
    data class AccountUpload(
        val sha: String,
        val game: String,
        val filename: String,
        val token: String,
        val ts: Long,
    )

    /** Payload of a successful {@code /account/reset} — the fresh [session] the new password minted. */
    data class ResetData(val session: String)

    /**
     * The currently signed-in account read from local state, or null when logged out. [avatarVersion] is a
     * locally-tracked timestamp bumped on every avatar change (and stamped at login) — it never comes from
     * the worker; it exists purely to version the otherwise-stable [avatarUrl].
     */
    data class Account(
        val userId: String,
        val username: String,
        val session: String,
        val avatarUrl: String?,
        val avatarVersion: Long = 0L,
    ) {
        /**
         * PHASE 4 (optional accounts) — the avatar URL every LOCAL-user surface should render. The worker's
         * avatar URL is stable per user ({@code /account/avatar?uid=<uid>}), so Coil (and the worker's 5-min
         * HTTP cache) would keep serving the OLD picture after a change. Appending {@code &t=<avatarVersion>}
         * — bumped on each change — makes every surface refetch the new image in lockstep. Null when logged
         * out or the user has no picture (the caller falls back to the person icon).
         */
        val displayAvatarUrl: String?
            get() = avatarUrl?.let { url ->
                if (avatarVersion <= 0L) url
                else url + (if (url.contains("?")) "&" else "?") + "t=" + avatarVersion
            }
    }

    /** The reinstall-proof recovery backup — enough to reset the password after a wipe. */
    data class RecoveryBackup(
        val username: String,
        val userId: String,
        val recoveryKey: String,
    )

    // --- network ---------------------------------------------------------------------------------

    /**
     * POST {@code /account/create {username,password}}. On success persists the new account locally
     * (session in prefs + the recovery backup file) and returns [CreateData] so the UI can show the
     * one-time recovery key. On rejection returns [AccountResult.Error] with the worker's code. Blocking.
     */
    fun createAccount(context: Context, username: String, password: String): AccountResult<CreateData> {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val resp = post("$BASE/account/create", body)
        val json = parseOk(resp) ?: return errorFrom(resp)
        val data = CreateData(
            userId = json.optString("user_id", "").trim(),
            username = json.optString("username", username).trim(),
            session = json.optString("session", "").trim(),
            recoveryKey = json.optString("recovery_key", "").trim(),
        )
        if (data.session.isBlank()) return AccountResult.Error("network")
        saveNewAccount(context, data.userId, data.username, data.session, data.recoveryKey)
        return AccountResult.Success(data)
    }

    /**
     * POST {@code /account/login {username,password}}. On success persists the session locally and
     * returns the [LoginData] profile; on bad creds returns {@code Error("invalid")}, on a locked
     * account {@code Error("rate_limited")}. Blocking.
     */
    fun login(context: Context, username: String, password: String): AccountResult<LoginData> {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val resp = post("$BASE/account/login", body)
        val json = parseOk(resp) ?: return errorFrom(resp)
        val avatar = json.optString("avatarUrl", "").trim().ifBlank { null }
        val uploads = ArrayList<AccountUpload>()
        json.optJSONArray("uploads")?.let { arr ->
            for (i in 0 until arr.length()) {
                // Entries are objects {sha,game,filename,token,ts}; tolerate a bare-sha string form too.
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    val sha = obj.optString("sha", "").trim()
                    if (sha.isEmpty()) continue
                    uploads.add(
                        AccountUpload(
                            sha = sha,
                            game = obj.optString("game", "").trim(),
                            filename = obj.optString("filename", "").trim(),
                            token = obj.optString("token", "").trim(),
                            ts = obj.optLong("ts", 0L),
                        )
                    )
                } else {
                    val sha = arr.optString(i, "").trim()
                    if (sha.isNotEmpty()) uploads.add(AccountUpload(sha, "", "", "", 0L))
                }
            }
        }
        val data = LoginData(
            userId = json.optString("user_id", "").trim(),
            username = json.optString("username", username).trim(),
            session = json.optString("session", "").trim(),
            avatarUrl = avatar,
            uploads = uploads,
        )
        if (data.session.isBlank()) return AccountResult.Error("network")
        saveLogin(context, data.userId, data.username, data.session, data.avatarUrl)
        // Stamp a fresh avatar version on login so the picture is refetched on this device (and the worker's
        // 5-min HTTP cache is bypassed once). Harmless when the account has no avatar.
        if (data.avatarUrl != null) bumpAvatarVersion(context)
        return AccountResult.Success(data)
    }

    /**
     * POST {@code /account/reset {username,recovery_key,new_password}}. On success persists a fresh
     * logged-in session (user_id recovered from the local backup file when present) and returns
     * [ResetData]; on a bad key returns {@code Error("invalid")}. Blocking.
     */
    fun resetPassword(
        context: Context,
        username: String,
        recoveryKey: String,
        newPassword: String,
    ): AccountResult<ResetData> {
        val body = JSONObject()
            .put("username", username)
            .put("recovery_key", recoveryKey)
            .put("new_password", newPassword)
            .toString()
        val resp = post("$BASE/account/reset", body)
        val json = parseOk(resp) ?: return errorFrom(resp)
        val session = json.optString("session", "").trim()
        if (session.isBlank()) return AccountResult.Error("network")
        // Log the user in with the reset session; user_id comes from the durable backup if we still have it.
        val backupUserId = recoveryBackup(context)?.userId.orEmpty()
        saveLogin(context, backupUserId, username, session, null)
        return AccountResult.Success(ResetData(session))
    }

    /**
     * PHASE 3 (optional accounts) — AVATAR. POST {@code /account/avatar {session,image,content_type}} where
     * [bytes] is the already-downscaled avatar (the caller compresses it to ≤512KB JPEG client-side). On
     * success the worker returns the stable {@code avatarUrl} (same URL, overwritten on each change); we
     * mirror it into the locally-stored session so every avatar surface refreshes without a re-login, and
     * hand the URL back. Returns {@code Error("not_signed_in")} when logged out, or the worker's typed code
     * ({@code bad_type}, {@code bad_size}, {@code bad_image}, {@code invalid}) on rejection. Blocking — run
     * off the main thread; degrades to [AccountResult.Error] rather than throwing.
     */
    fun uploadAvatar(context: Context, bytes: ByteArray, contentType: String): AccountResult<String> {
        val session = session(context) ?: return AccountResult.Error("not_signed_in")
        val body = JSONObject()
            .put("session", session)
            .put("image", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("content_type", contentType)
            .toString()
        val resp = post("$BASE/account/avatar", body)
        val json = parseOk(resp) ?: return errorFrom(resp)
        val url = json.optString("avatarUrl", "").trim()
        if (url.isBlank()) return AccountResult.Error("network")
        // The avatar URL is stable per user, so bump the local version FIRST — that's what actually busts
        // Coil's cache (and the worker's 5-min HTTP cache) so every avatar surface refetches the new image.
        bumpAvatarVersion(context)
        // Mirror the new URL into the stored session so the ☰ swap / drawer header / dialogs all update.
        current(context)?.let { saveLogin(context, it.userId, it.username, it.session, url) }
        return AccountResult.Success(url)
    }

    // --- local state -----------------------------------------------------------------------------

    /** The signed-in account, or null when logged out. */
    fun current(context: Context): Account? {
        val sp = prefs(context)
        if (!sp.getBoolean(K_LOGGED_IN, false)) return null
        val session = sp.getString(K_SESSION, null)?.trim().orEmpty()
        val username = sp.getString(K_USERNAME, null)?.trim().orEmpty()
        if (session.isBlank() || username.isBlank()) return null
        return Account(
            userId = sp.getString(K_USER_ID, null)?.trim().orEmpty(),
            username = username,
            session = session,
            avatarUrl = sp.getString(K_AVATAR, null)?.trim()?.ifBlank { null },
            avatarVersion = sp.getLong(K_AVATAR_VERSION, 0L),
        )
    }

    /** Whether an account is currently signed in on this device. */
    fun isLoggedIn(context: Context): Boolean = current(context) != null

    /** The signed-in session token, or null when logged out — passed to {@code /upload} to attribute it. */
    fun session(context: Context): String? = current(context)?.session

    /** Persist a logged-in session in prefs only (login / reset — no recovery key to back up here). */
    fun saveLogin(context: Context, userId: String, username: String, session: String, avatarUrl: String?) {
        prefs(context).edit()
            .putBoolean(K_LOGGED_IN, true)
            .putString(K_USER_ID, userId)
            .putString(K_USERNAME, username)
            .putString(K_SESSION, session)
            .putString(K_AVATAR, avatarUrl)
            .apply()
    }

    /**
     * Persist a freshly-created account: the session in prefs AND {@code {username,user_id,recovery_key}}
     * in the durable backup file so the recovery key survives a reinstall.
     */
    fun saveNewAccount(context: Context, userId: String, username: String, session: String, recoveryKey: String) {
        saveLogin(context, userId, username, session, null)
        writeBackup(RecoveryBackup(username = username, userId = userId, recoveryKey = recoveryKey))
    }

    /** Clear the signed-in session from prefs. Deliberately does NOT touch the recovery backup file. */
    fun logout(context: Context) {
        prefs(context).edit()
            .remove(K_LOGGED_IN)
            .remove(K_USER_ID)
            .remove(K_USERNAME)
            .remove(K_SESSION)
            .remove(K_AVATAR)
            .remove(K_AVATAR_VERSION)
            .apply()
    }

    /** The local avatar version (a timestamp), 0 when never set. Versions the otherwise-stable avatar URL. */
    fun avatarVersion(context: Context): Long = prefs(context).getLong(K_AVATAR_VERSION, 0L)

    /** Bump the local avatar version to now so every avatar surface refetches the just-changed picture. */
    fun bumpAvatarVersion(context: Context): Long {
        val v = System.currentTimeMillis()
        prefs(context).edit().putLong(K_AVATAR_VERSION, v).apply()
        return v
    }

    /** The saved recovery key (from the durable backup file), or null when none was ever backed up. */
    fun recoveryKey(context: Context): String? = recoveryBackup(context)?.recoveryKey

    /** The full recovery backup {@code {username,user_id,recovery_key}}, or null when absent/unreadable. */
    fun recoveryBackup(context: Context): RecoveryBackup? =
        try {
            val file = backupFile()
            if (file == null || !file.exists()) null
            else {
                val o = JSONObject(file.readText())
                val key = o.optString("recovery_key", "").trim()
                val user = o.optString("username", "").trim()
                if (key.isBlank() || user.isBlank()) null
                else RecoveryBackup(
                    username = user,
                    userId = o.optString("user_id", "").trim(),
                    recoveryKey = key,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Account backup read failed", e)
            null
        }

    // --- internals -------------------------------------------------------------------------------

    private const val K_LOGGED_IN = "loggedIn"
    private const val K_USER_ID = "user_id"
    private const val K_USERNAME = "username"
    private const val K_SESSION = "session"
    private const val K_AVATAR = "avatarUrl"
    private const val K_AVATAR_VERSION = "avatar_version"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Parse a 2xx JSON body with {@code success:true}; null for any non-success (caller maps the error). */
    private fun parseOk(resp: HttpUtils.HttpResponse?): JSONObject? {
        if (resp == null || resp.code !in 200..299 || resp.body.isNullOrBlank()) return null
        return try {
            val o = JSONObject(resp.body)
            if (o.optBoolean("success", false)) o else null
        } catch (e: Exception) {
            null
        }
    }

    /** Map a non-success response to a typed error code, reading the worker's {@code {error}} when present. */
    private fun errorFrom(resp: HttpUtils.HttpResponse?): AccountResult.Error {
        if (resp == null || resp.body.isNullOrBlank()) return AccountResult.Error("network")
        return try {
            val code = JSONObject(resp.body).optString("error", "").trim()
            AccountResult.Error(code.ifBlank { "network" })
        } catch (e: Exception) {
            AccountResult.Error("network")
        }
    }

    private fun writeBackup(backup: RecoveryBackup) {
        try {
            val file = backupFile() ?: return
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            file.writeText(
                JSONObject()
                    .put("username", backup.username)
                    .put("user_id", backup.userId)
                    .put("recovery_key", backup.recoveryKey)
                    .toString(2)
            )
            file.setReadable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Account backup write failed", e)
        }
    }

    private fun backupFile(): File? =
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(File(downloads, "bannerlator/game-configs"), BACKUP)
        } catch (e: Exception) {
            null
        }

    /** Blocking status-aware POST bridged off [HttpUtils]' async executor (mirrors [CommunityConfigWorker]). */
    private fun post(url: String, jsonBody: String): HttpUtils.HttpResponse? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<HttpUtils.HttpResponse>(1)
        HttpUtils.postWithStatus(url, jsonBody) { resp ->
            holder[0] = resp
            latch.countDown()
        }
        return try {
            latch.await()
            holder[0]
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
