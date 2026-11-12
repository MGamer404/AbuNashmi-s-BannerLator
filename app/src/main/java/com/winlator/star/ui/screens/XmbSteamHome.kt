package com.winlator.star.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.winlator.star.R
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.deck.ScreenScale
import com.winlator.star.ui.deck.XmbStoreMark
import com.winlator.star.ui.deck.storeMarkOf
import com.winlator.star.ui.deck.xmbInkColor
import com.winlator.star.ui.simple.LabArt
import com.winlator.star.ui.simple.LabPicture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

// The XMB Home in the Steam Deck library's look: the focused game's own landscape art fills the screen under a gradient to dark navy,
// one row of covers sits low (the first game as a wide card, the focused card larger and ringed with a thin light outline, its store
// mark at the corner), the focused game's title and "container · resolution" under it, and plain text tabs under those. The geometry
// is in XmbSteamLogic.kt; ShortcutsXmbView keeps the focus model, the keys and the options column and draws this in place of the
// centred row.

/** The thin light outline of the focused card. */
private val XMB_STEAM_OUTLINE = Color.White.copy(alpha = 0.92f)

/** The backdrop's gradient ends in these: dark navy, then nearly black at the bottom edge. */
private val XMB_STEAM_NAVY = Color(0xFF0B1426)
private val XMB_STEAM_NAVY_DEEP = Color(0xFF04060C)

/** A tab's text sits this far (dp) in from the edge of its highlight. */
private const val XMB_STEAM_TAB_PAD = 10f

/** A game's landscape art for the backdrop: the pictures to try in order, made once per game (and again when its art settings change). */
private class XmbSteamHero(val pics: List<LabArt.Pic>)

/** [pics] faded in once one has loaded (nothing shows until then, nor when none loads), so what is under it stays until it is ready. */
@Composable
private fun XmbSteamPicture(pics: List<LabArt.Pic>, modifier: Modifier) {
    var loaded by remember(pics) { mutableStateOf(false) }
    val a by animateFloatAsState(if (loaded) 1f else 0f, tween(350), label = "xmbSteamArt")
    LabPicture(pics, modifier.graphicsLayer { alpha = a }, onLoaded = { loaded = true })
}

/**
 * The Home backdrop: [game]'s own landscape art (its own pick, Steam's, SteamGridDB's, found by LabArt off the main thread and kept on
 * the device) full-bleed and crossfading as the cursor moves, under a gradient to dark navy at the bottom. [picture] (the cover, or
 * the All Games collage) is the usual blurred backdrop, which stays underneath: it shows while the art loads and when there is none.
 */
@Composable
internal fun XmbSteamBackdrop(game: Shortcut?, picture: Any?) {
    val context = LocalContext.current
    val path = game?.file?.path
    LaunchedEffect(path) { if (game != null) withContext(Dispatchers.IO) { LabArt.resolve(context, game) } }
    val tick = LabArt.tick
    val hero = remember(path, tick) { game?.let { g -> XmbSteamHero(LabArt.models(g, "bg").map { LabArt.Pic(it) }) } }
    Box(Modifier.fillMaxSize()) {
        XmbHomeBackdrop(picture)
        Crossfade(targetState = hero, animationSpec = tween(600), label = "xmbSteamHero") { h ->
            if (h != null) XmbSteamPicture(h.pics, Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.28f to Color.Transparent,
                    0.55f to XMB_STEAM_NAVY.copy(alpha = 0.70f),
                    0.85f to XMB_STEAM_NAVY_DEEP.copy(alpha = 0.96f),
                    1f to XMB_STEAM_NAVY_DEEP,
                ),
            ),
        )
    }
}

/**
 * The row of covers, the focused game's title and the text tabs. [lift] is 0 normally and 1 while the options are open: the covers
 * lift (the column opens under the focused one), the others dim and the title and tabs fade out. [tabsActive] = the tabs hold the
 * cursor, on tab [tabSel]; [tabsEnabled] = they can be tapped. [onCard] / [onTab] are taps.
 */
@Composable
internal fun XmbSteamHome(
    shortcuts: List<Shortcut>,
    hasAll: Boolean,
    pos: MutableFloatState,
    target: Int,
    sl: XmbSteamLayout,
    accent: Color,
    ss: ScreenScale,
    mosaic: XmbMosaic<Shortcut>,
    allCount: String,
    focused: Shortcut?,
    sdBadge: @Composable (Shortcut) -> Unit,
    lift: () -> Float,
    tabs: List<XmbAction>,
    tabSel: Int,
    tabsActive: Boolean,
    tabsEnabled: Boolean,
    onCard: (Int) -> Unit,
    onTab: (Int) -> Unit,
) {
    val n = shortcuts.size
    val slots = n + if (hasAll) 1 else 0
    // Only the covers near the cursor are composed; each one's place is read from `pos` in its graphics layer.
    val baseIdx by remember { derivedStateOf { floor(pos.floatValue).toInt() } }
    val ahead = xmbSteamAhead(sl.w, sl.fW * XMB_STEAM_REST_SCALE, sl.gap)
    Box(Modifier.fillMaxSize()) {
        for (i in (baseIdx - 4).coerceAtLeast(0)..(baseIdx + ahead).coerceAtMost(slots - 1)) {
            if (i >= n) {
                key(XMB_ALL_PATH) {
                    XmbSteamAllCard(i, pos, sl, n, accent, allCount, mosaic, i == target, lift) { onCard(i) }
                }
            } else {
                val s = shortcuts[i]
                key(s.file.path) {
                    XmbSteamCover(s, i, pos, sl, n, accent, i == target, lift) { onCard(i) }
                }
            }
        }
        XmbSteamTitle(focused, allCount, sdBadge, sl, ss, lift)
        if (tabs.isNotEmpty()) XmbSteamTabs(tabs, tabSel, tabsActive, tabsEnabled, sl, accent, ss, lift, onTab)
    }
}

// A card's place in the row: its full size, then its drawn size, position and fade from its distance to the cursor, read in the
// graphics layer so a swipe re-draws without recomposing. All cards share a bottom edge, so the focused (larger) one grows upward;
// [lift] raises the whole row while the options are open and dims the cards that are not focused.
private fun Modifier.xmbSteamSlot(
    index: Int, pos: MutableFloatState, sl: XmbSteamLayout, games: Int, focused: Boolean, lift: () -> Float,
): Modifier = this
    .zIndex(if (focused) 1f else 0f)
    .size(sl.cardW(index, games).dp, sl.fH.dp)
    .graphicsLayer {
        val p = pos.floatValue
        val sc = xmbSteamScale(index, p)
        val l = lift()
        translationX = (sl.anchorX + xmbSteamOffset(index, p, { sl.cardW(it, games) }, sl.gap)).dp.toPx()
        translationY = (sl.cardTop - sl.lift * l).dp.toPx()
        scaleX = sc
        scaleY = sc
        transformOrigin = TransformOrigin(0f, 1f)
        val t = (sc - XMB_STEAM_REST_SCALE) / (1f - XMB_STEAM_REST_SCALE)
        alpha = (0.78f + 0.22f * t) * (if (focused) 1f else 1f - 0.45f * l)
    }

@Composable
private fun XmbSteamCover(
    shortcut: Shortcut,
    index: Int,
    pos: MutableFloatState,
    sl: XmbSteamLayout,
    games: Int,
    accent: Color,
    focused: Boolean,
    lift: () -> Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val src = remember { MutableInteractionSource() }
    val mark = remember(shortcut) { storeMarkOf(shortcut) }
    Box(
        Modifier
            .xmbSteamSlot(index, pos, sl, games, focused, lift)
            .clip(shape)
            .background(Color(0xFF111111))
            .then(if (focused) Modifier.border(2.dp, XMB_STEAM_OUTLINE, shape) else Modifier)
            .xmbClick(src, onClick),
    ) {
        if (index == 0) {
            XmbSteamWideArt(shortcut, accent)
        } else {
            val bmp = shortcut.icon
            if (bmp != null) {
                Image(
                    bitmap = remember(bmp) { bmp.asImageBitmap() },
                    contentDescription = shortcut.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                XmbMonogram(shortcut.name, accent)
            }
        }
        // Where the Steam Deck draws its controller badge: only on the focused card, scaled with it.
        if (focused) XmbStoreMark(mark, Modifier.align(Alignment.BottomEnd).padding(6.dp), (sl.fW * 0.25f).coerceIn(22.5f, 32.5f).dp)
    }
}

/**
 * The first game's wide card: its landscape art (its own pick, Steam's header, then its background art) over the centre-crop of its
 * cover, which shows until the art has loaded and when the game has none.
 */
@Composable
private fun XmbSteamWideArt(s: Shortcut, accent: Color) {
    val context = LocalContext.current
    val path = s.file.path
    LaunchedEffect(path) { withContext(Dispatchers.IO) { LabArt.resolve(context, s) } }
    val tick = LabArt.tick
    val pics = remember(path, tick) { xmbSteamWideOrder(LabArt.models(s, "wide"), LabArt.models(s, "bg")).map { LabArt.Pic(it) } }
    Box(Modifier.fillMaxSize()) {
        val bmp = s.icon
        if (bmp != null) {
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = s.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            XmbMonogram(s.name, accent)
        }
        XmbSteamPicture(pics, Modifier.fillMaxSize())
    }
}

// The row's last card: the library's folder-like preview, as on the usual row.
@Composable
private fun XmbSteamAllCard(
    index: Int,
    pos: MutableFloatState,
    sl: XmbSteamLayout,
    games: Int,
    accent: Color,
    count: String,
    mosaic: XmbMosaic<Shortcut>,
    focused: Boolean,
    lift: () -> Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val src = remember { MutableInteractionSource() }
    val description = stringResource(R.string.xmb_all_games) + ", " + count
    Box(
        Modifier
            .xmbSteamSlot(index, pos, sl, games, focused, lift)
            .clip(shape)
            .background(Brush.linearGradient(XMB_PLAIN_CARD))
            .border(if (focused) 2.dp else 1.dp, if (focused) XMB_STEAM_OUTLINE else Color.White.copy(alpha = 0.14f), shape)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .xmbClick(src, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (mosaic.covers.isEmpty()) {
            Icon(Icons.Filled.GridView, contentDescription = null, tint = Color.White, modifier = Modifier.size((sl.fW * 0.44f).dp))
        } else {
            XmbAllGamesMosaic(mosaic, accent, Modifier.fillMaxSize().padding(6.dp))
        }
    }
}

// Under the focused card: its name, bold, and below it "container · resolution" (an Android game's package name stands in). On the All
// Games card: its name and the number of games.
@Composable
private fun XmbSteamTitle(
    focused: Shortcut?,
    allCount: String,
    sdBadge: @Composable (Shortcut) -> Unit,
    sl: XmbSteamLayout,
    ss: ScreenScale,
    lift: () -> Float,
) {
    val context = LocalContext.current
    AnimatedContent(
        targetState = focused,
        contentKey = { it?.file?.path ?: XMB_ALL_PATH },
        transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 }) togetherWith fadeOut(tween(90)) },
        label = "xmbSteamTitle",
        modifier = Modifier
            .offset(sl.anchorX.dp, sl.titleY.dp)
            .width((sl.w - sl.anchorX - 16f).coerceAtLeast(80f).dp)
            .graphicsLayer { alpha = 1f - lift() },
    ) { s ->
        val subtitleSize = ss.sp(12f)
        if (s == null) {
            Column {
                XmbSteamTitleText(stringResource(R.string.xmb_all_games), ss)
                Text(allCount, color = Color(0xFFC4C4C4), fontSize = subtitleSize, maxLines = 1)
            }
        } else {
            val meta = remember(s) { AndroidGames.packageOf(s) ?: buildLaunchSpec(s, context).meta }
            Column {
                XmbSteamTitleText(s.name, ss)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotEmpty()) {
                        Text(
                            meta, color = Color(0xFFC4C4C4), fontSize = subtitleSize, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    sdBadge(s)
                }
            }
        }
    }
}

@Composable
private fun XmbSteamTitleText(text: String, ss: ScreenScale) {
    Text(
        text, color = Color.White, fontSize = ss.sp(20f), lineHeight = ss.sp(24f), fontWeight = FontWeight.Bold,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

// The plain text tabs: small, upper case, letter-spaced. The one under the cursor (while the tabs hold it) is tinted with the accent on a
// faint accent fill; the rest are dim white.
@Composable
private fun XmbSteamTabs(
    tabs: List<XmbAction>,
    sel: Int,
    active: Boolean,
    enabled: Boolean,
    sl: XmbSteamLayout,
    accent: Color,
    ss: ScreenScale,
    lift: () -> Float,
    onTap: (Int) -> Unit,
) {
    val ink = remember(accent) { xmbInkColor(accent, Color.White, XMB_STEAM_NAVY) }
    val shape = RoundedCornerShape(8.dp)
    // Starts a little left of the title so the first tab's text lines up with it.
    Row(
        Modifier
            .offset((sl.anchorX - XMB_STEAM_TAB_PAD).dp, sl.tabsTop.dp)
            .width((sl.w - sl.anchorX + XMB_STEAM_TAB_PAD).coerceAtLeast(48f).dp)
            .height(XMB_STEAM_TABS_H.dp)
            .graphicsLayer { alpha = 1f - lift() }
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, tab ->
            key(tab.label) {
                val on = active && i == sel
                val src = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .widthIn(min = 48.dp)
                        .then(if (enabled) Modifier.xmbClick(src) { onTap(i) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label.uppercase(),
                        color = if (on) ink else Color.White.copy(alpha = 0.66f),
                        fontSize = ss.sp(11f),
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(shape)
                            .background(if (on) accent.copy(alpha = 0.18f) else Color.Transparent)
                            .padding(horizontal = XMB_STEAM_TAB_PAD.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
