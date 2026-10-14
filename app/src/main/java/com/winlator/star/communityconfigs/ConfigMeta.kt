package com.winlator.star.communityconfigs

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Provenance of a single community config, read straight from the fields we already fetch — the
 * config JSON's {@code meta} block plus the upload epoch encoded in the file name. There is NO
 * vote/like/comment/author data in the mirror (scanned), so this is deliberately just the honest
 * provenance the detail page shows.
 *
 *  - [appSource] — the BannerHub app that produced it ({@code meta.app_source}).
 *  - [device] / [soc] — the hardware it was captured on ({@code meta.device} / {@code meta.soc}).
 *  - [bhVersion] — the BannerHub version string ({@code meta.bh_version}), shown as a source badge.
 *  - [uploadedEpoch] — seconds since epoch, parsed from the file name's trailing number (the upload
 *    date lives nowhere in {@code meta}); null when the name has no parseable trailing number.
 *  - [uploaderName] / [uploaderAvatarUrl] — the signed-in account attribution (Phase 2), from
 *    {@code meta.uploader}; both null for an anonymous config (the detail page then shows "Anonymous user").
 */
data class ConfigMeta(
    val appSource: String?,
    val device: String?,
    val soc: String?,
    val bhVersion: String?,
    val uploadedEpoch: Long?,
    val uploaderName: String?,
    val uploaderAvatarUrl: String?,
) {
    /**
     * The upload date as a plain {@code yyyy-MM-dd} string in UTC, or null when unknown. Intentionally
     * dumb and locale-independent so the same config reads identically on every device.
     */
    val uploadedDate: String?
        get() = uploadedEpoch?.let { epoch ->
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.format(Date(epoch * 1000L))
        }

    companion object {
        // Trailing epoch in a config file name, e.g. "…-Adreno__TM__740-1778449587.json" -> 1778449587.
        private val EPOCH_IN_NAME = Regex("(\\d+)\\.json$", RegexOption.IGNORE_CASE)

        /** Parse the config's {@code meta} object plus its [fileName] into a [ConfigMeta]. Never throws. */
        fun parse(meta: JSONObject?, fileName: String): ConfigMeta {
            val m = meta ?: JSONObject()
            fun field(key: String): String? = m.optString(key, "").trim().ifBlank { null }
            val uploader = m.optJSONObject("uploader")
            return ConfigMeta(
                appSource = field("app_source"),
                device = field("device"),
                soc = field("soc"),
                bhVersion = field("bh_version"),
                uploadedEpoch = EPOCH_IN_NAME.find(fileName)?.groupValues?.get(1)?.toLongOrNull(),
                uploaderName = uploader?.optString("name", "")?.trim()?.ifBlank { null },
                uploaderAvatarUrl = uploader?.optString("avatarUrl", "")?.trim()?.ifBlank { null },
            )
        }
    }
}
