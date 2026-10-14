package com.winlator.star.store

import android.content.Context

/**
 * Single owner of Amazon's native install record (the `bh_amazon_prefs` keys that encode
 * "this game is installed"). Amazon has no DB — install-state lives entirely in these prefs,
 * and THREE surfaces read them: the store list ([AmazonGamesActivity], `amazon_exe_<id> != null`),
 * the detail page ([AmazonGameDetailActivity.refreshActionState]) and the seed
 * ([AmazonLibrarySync]). So every uninstall — from the store list, the detail page, OR the
 * cross-store Download Manager — must clear the SAME keys, or a surface goes stale (the
 * device-observed bug: files deleted from the Manager, but the store list still showed
 * "✓ Installed" because only the Steam DB was cleared, never Amazon's prefs).
 *
 * This is the Amazon-side analogue of Steam clearing its DB (`SteamRepository.markUninstalled`).
 * It lives on the Amazon side ON PURPOSE so the store-agnostic `download` package keeps
 * importing zero store engines — the Manager Activity / hooks call INTO this, never the reverse.
 */
object AmazonInstallState {

    private const val PREFS_NAME = "bh_amazon_prefs"

    /**
     * Remove every per-game install record for [productId]: the launch exe, the install dir,
     * the recorded manifest version, and the cached install size. Idempotent — removing an
     * already-absent key is a no-op — so it's safe to call from any uninstall path (or the
     * seed self-heal) without first checking what exists. Does NOT touch the files on disk
     * (the caller's uninstall worker already deletes those) nor the registry row.
     */
    fun purge(ctx: Context, productId: String) {
        ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("amazon_exe_$productId")
            .remove("amazon_dir_$productId")
            .remove("amazon_manifest_version_$productId")
            .remove("amazon_size_$productId")
            .apply()
    }
}
