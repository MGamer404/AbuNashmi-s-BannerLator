package com.winlator.star.communityconfigs

/**
 * A pointer to ONE specific uploaded config on the BannerHub configs worker — the unit the per-game
 * config list, the Apply / View-details chooser, the apply path and the detail page all act on. Unlike
 * a [CanonicalDevice] (which only names a hardware target, leaving "which file" to be resolved), this
 * names the exact file:
 *
 *  - [workerGame] — the `/list?game=` key the file lives under (already resolved, so apply / detail
 *    hit the same KV bucket without re-probing).
 *  - [filename] — the exact config file to download (`/download?game=&file=`).
 *  - [sha] — the social key (votes / desc), when the `/list` entry carried one; null otherwise.
 *  - [ns] — the namespace the file lives in: "" = the default BannerHub repo, "bannerlator" = OUR own
 *    `bannerlator-game-configs` repo. Threaded into the per-file download / list so the detail + apply
 *    path reads from the SAME repo the `/list` entry came from (a Bannerlator file is invisible to the
 *    no-ns BannerHub download).
 */
data class CommunityConfigRef(
    val game: CanonicalGame,
    val workerGame: String,
    val filename: String,
    val sha: String?,
    val ns: String = "",
)
