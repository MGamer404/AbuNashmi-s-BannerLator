package com.winlator.star.ui.simple

// Where a Steam game's art comes from and in which order it is tried, kept free of Compose and Android so it can be unit-tested.

/** Steam's store asset root; a game's constructed (unhashed) art lives under "<appId>/". */
internal const val LAB_STEAM_ASSETS = "https://shared.steamstatic.com/store_item_assets/steam/apps/"

/**
 * Steam's own published art URLs for one app, as its PICS appinfo lists them (SteamLibraryArt). Newer apps use content-hashed
 * paths (".../<appId>/<hash>/library_hero.jpg"): the client shows these, while the constructed ".../<appId>/library_hero.jpg" can
 * be an older picture or missing. Any field is null when the app publishes none.
 */
internal class LabSteamPics(val hero: String?, val header: String?, val libraryHeader: String?, val capsule: String?)

/**
 * Steam's pictures for [slot] of [appId], in the order to try: what the app publishes through PICS ([pics]) first, then the
 * constructed CDN URLs (a background's double size before its normal one), then SteamGridDB's background ([sgdbHero]), which only
 * backgrounds have. Each URL once; nothing for a slot Steam has no picture for. [appId] null (a game not linked to Steam) leaves
 * just [sgdbHero].
 */
internal fun labSteamSources(appId: Int?, slot: String, pics: LabSteamPics?, sgdbHero: String?): List<String> {
    val base = appId?.takeIf { it > 0 }?.let { "$LAB_STEAM_ASSETS$it/" }
    val urls: List<String?> = when (slot) {
        "bg" -> listOf(pics?.hero, base?.plus("library_hero_2x.jpg"), base?.plus("library_hero.jpg"), sgdbHero)
        "wide" -> listOf(pics?.libraryHeader, pics?.header, base?.plus("header.jpg"))
        "cover" -> listOf(pics?.capsule, base?.plus("library_600x900.jpg"))
        "logo" -> listOf(base?.plus("logo.png"))
        else -> emptyList()
    }
    return urls.filterNotNull().filter { it.isNotBlank() }.distinct()
}

/**
 * What a game's automatically found art is remembered under: its name, and its Steam app once it has one. A game linked to another
 * app (or renamed) is therefore looked up again instead of keeping the picture found for what it used to be.
 */
internal fun labHeroKey(name: String, appId: Int?): String = if (appId != null && appId > 0) "$name#$appId" else name
