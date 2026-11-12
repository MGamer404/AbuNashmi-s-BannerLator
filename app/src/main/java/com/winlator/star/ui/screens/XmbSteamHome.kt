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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
// one row of square-cornered covers sits low (the first game as a wide capsule, the focused card a touch larger and ringed with a thin
// light outline, a store mark chip at every card's corner), the focused game's title and "container · resolution" under it, and
// centred plain text tabs near the bottom. The geometry is in XmbSteamLogic.kt; ShortcutsXmbView keeps the focus model, the keys and
// the options column and draws this in place of the centred row.

/** The thin light outline of the focused card. */
private val XMB_STEAM_OUTLINE = Color.White.copy(alpha = 0.92f)

/** The backdrop's gradient ends in this dark navy, at [XMB_STEAM_FADE_MAX] of full strength at the bottom edge. */
private val XMB_STEAM_NAVY = Color(0xFF0B1426)
private const val XMB_STEAM_FADE_MAX = 0.35f

/** The covers that are not the focused one are dimmed this much (an opaque black overlay, so the backdrop never shows through) while the options are open. */
private const val XMB_STEAM_DIM = 0.45f

/** A soft shadow under the title, the subtitle and the tabs: they sit over art that is no longer darkened. */
private val XMB_STEAM_TEXT = TextStyle(
    shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), 6f),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Tight lines: no padding above or below a text's glyphs, so the title and the subtitle under it are exactly their gap apart. */
private val XMB_STEAM_TIGHT = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** The title's line box hugs its glyphs from above: the line is cut from below (the descenders hang out of it), so the subtitle can sit right under. */
private val XMB_STEAM_TOP = LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.Both)

/** The subtitle's text: nearly white, a hair under the title's white. */
private val XMB_STEAM_MUTED = Color.White.copy(alpha = 0.9f)

/** The tabs' text: near white; the one under the cursor is pure white in a thin white outline pill. */
private val XMB_STEAM_TAB_INK = Color.White.copy(alpha = 0.97f)

/** The cards' corners are nearly square, as on the Steam Deck. */
private val XMB_STEAM_CORNER = 2.dp

/** A tab's text sits this far (dp) in from the edge of its highlight. */
private const val XMB_STEAM_TAB_PAD = 10f

/** A game's landscape art for the backdrop: the pictures to try in order, made once per game (and again when its art settings change). */
private class XmbSteamHero(val pics: List<LabArt.Pic>)

/** [pics] faded in once one has loaded (nothing shows until then, nor when none loads), so what is under it stays until it is ready. */
@Composable
private fun XmbSteamPicture(
    pics: List<LabArt.Pic>,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
) {
    var loaded by remember(pics) { mutableStateOf(false) }
    val a by animateFloatAsState(if (loaded) 1f else 0f, tween(350), label = "xmbSteamArt")
    LabPicture(pics, modifier.graphicsLayer { alpha = a }, contentScale = contentScale, alignment = alignment, onLoaded = { loaded = true })
}

/**
 * The Home backdrop: [game]'s own landscape art (its own pick, Steam's, SteamGridDB's, found by LabArt off the main thread and kept on
 * the device) crossfading as the cursor moves, cropped to fill the whole screen (a landscape hero is scaled to the screen's height, a
 * portrait or low-res one to its width, so no band of anything else shows under it and there is no seam). A dark scrim over the top
 * fifth keeps the shell's header readable, and a soft gradient to dark navy starts at [fadeFrom] (a share of the screen's height: the
 * top edge of the text tabs) and is darkest, but only about a third opaque, at the bottom edge. [picture] (the cover) is the usual
 * blurred backdrop, which stays underneath: it shows only while the art loads and when there is none.
 */
@Composable
internal fun XmbSteamBackdrop(game: Shortcut?, picture: Any?, fadeFrom: Float) {
    val from = fadeFrom.coerceIn(0.25f, 0.97f)
    val context = LocalContext.current
    val path = game?.file?.path
    LaunchedEffect(path) { if (game != null) withContext(Dispatchers.IO) { LabArt.resolve(context, game) } }
    val tick = LabArt.tick
    val hero = remember(path, tick) { game?.let { g -> XmbSteamHero(LabArt.models(g, "bg").map { LabArt.Pic(it) }) } }
    Box(Modifier.fillMaxSize()) {
        XmbHomeBackdrop(picture)
        Crossfade(targetState = hero, animationSpec = tween(600), label = "xmbSteamHero") { h ->
            if (h != null) {
                XmbSteamPicture(h.pics, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                // The navy's strength follows a smoothstep from 0 at the tabs' top edge to its maximum at the bottom: no visible start.
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    0.20f to Color.Transparent,
                    from to XMB_STEAM_NAVY.copy(alpha = 0f),
                    from + (1f - from) * 0.25f to XMB_STEAM_NAVY.copy(alpha = XMB_STEAM_FADE_MAX * 0.156f),
                    from + (1f - from) * 0.50f to XMB_STEAM_NAVY.copy(alpha = XMB_STEAM_FADE_MAX * 0.5f),
                    from + (1f - from) * 0.75f to XMB_STEAM_NAVY.copy(alpha = XMB_STEAM_FADE_MAX * 0.844f),
                    1f to XMB_STEAM_NAVY.copy(alpha = XMB_STEAM_FADE_MAX),
                ),
            ),
        )
    }
}

/**
 * The row of covers (the whole library, recent first), the focused game's title and the text tabs. [lift] is 0 normally and 1 while
 * the options are open: the card [liftIdx] (the focused one when they opened) glides to the left margin, lifted and larger, with the
 * column opening under it and the other covers continuing in a row to its right (dimmed with an opaque overlay), and the title and
 * tabs fade out. Going back, the row glides to where it rested. [tabsActive] = tab [tabSel], the one the host's tabs page last showed, is
 * highlighted (the tabs are the peek of that page; they never hold the cursor); [tabsEnabled] = they can be tapped. [onCard] / [onTab]
 * are taps.
 */
@Composable
internal fun XmbSteamHome(
    shortcuts: List<Shortcut>,
    pos: MutableFloatState,
    target: Int,
    liftIdx: Int,
    sl: XmbSteamLayout,
    accent: Color,
    ss: ScreenScale,
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
    val row = remember(sl, n) { sl.row(n) }
    // Only the covers near the cursor are composed; each one's place is read from `pos` in its graphics layer.
    val base by remember { derivedStateOf { floor(pos.floatValue).toInt() } }
    Box(Modifier.fillMaxSize()) {
        for (i in xmbSteamWindow(base.toFloat(), n, sl.w, sl.fW + sl.gap)) {
            val s = shortcuts[i]
            key(s.file.path) {
                XmbSteamCover(s, i, pos, sl, row, accent, i == target, i == liftIdx, liftIdx, lift) { onCard(i) }
            }
        }
        XmbSteamTitle(focused, sdBadge, sl, row, target, pos, accent, ss, lift)
        if (tabs.isNotEmpty()) XmbSteamTabs(tabs, tabSel, tabsActive, tabsEnabled, sl, accent, ss, lift, onTab)
    }
}

// A card's place in the row: its size, then its drawn size and position from its distance to the cursor, read in the graphics layer so a
// swipe re-draws without recomposing. All cards share a bottom edge, so the focused (larger) one grows upward. [lift] (0 at rest, 1 with
// the options open) glides the whole row to the options layout: raised, the card [liftIdx] at the left margin and drawn larger, the
// cards after it in a row to its right (see XmbSteamRow.xOpts). Nothing here fades: the dimming is an opaque overlay on the cover.
private fun Modifier.xmbSteamSlot(
    index: Int, pos: MutableFloatState, sl: XmbSteamLayout, row: XmbSteamRow, focused: Boolean, lifted: Boolean, liftIdx: Int, lift: () -> Float,
): Modifier = this
    .zIndex(if (focused || lifted) 1f else 0f)
    .size(sl.cardW(index, row.count).dp, sl.fH.dp)
    .graphicsLayer {
        val p = pos.floatValue
        val l = lift()
        val sc0 = xmbSteamScale(index, p)
        val sc = if (lifted) sc0 + (XMB_STEAM_OPTS_SCALE - sc0) * l else sc0
        translationX = row.xOpts(index, p, liftIdx, XMB_STEAM_OPTS_SCALE, l).dp.toPx()
        translationY = (sl.cardTop - sl.lift * l).dp.toPx()
        scaleX = sc
        scaleY = sc
        transformOrigin = TransformOrigin(0f, 1f)
    }

@Composable
private fun XmbSteamCover(
    shortcut: Shortcut,
    index: Int,
    pos: MutableFloatState,
    sl: XmbSteamLayout,
    row: XmbSteamRow,
    accent: Color,
    focused: Boolean,
    lifted: Boolean,
    liftIdx: Int,
    lift: () -> Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(XMB_STEAM_CORNER)
    val src = remember { MutableInteractionSource() }
    val mark = remember(shortcut) { storeMarkOf(shortcut) }
    // The store's mark is small and scales with the card (it is sized in dp inside the card's graphics layer).
    val markSize = (sl.fW * 0.20f).coerceIn(13f, 22f).dp
    Box(
        Modifier
            .xmbSteamSlot(index, pos, sl, row, focused, lifted, liftIdx, lift)
            .clip(shape)
            .background(Color(0xFF111111))
            .then(if (focused) Modifier.border(1.5.dp, XMB_STEAM_OUTLINE, shape) else Modifier)
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
        // Where the Steam Deck draws its controller badge: the store's mark straight on the cover (no chip, shadow or elevation behind
        // it), on every card, small, scaled with it.
        XmbStoreMark(mark, Modifier.align(Alignment.BottomEnd).padding(4.dp), markSize)
        // The cards that are not the lifted one are dimmed while the options are open: an opaque black overlay over the whole cover
        // (not a fade of the card, which would show the backdrop through it).
        if (!lifted) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = XMB_STEAM_DIM * lift() }.background(Color.Black))
        }
    }
}

/**
 * The first game's wide card, in the shape of Steam's capsule: its landscape art (its own pick, Steam's header, then its background
 * art) over the centre-crop of its cover, which shows until the art has loaded and when the game has none.
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

// Under the focused card, left aligned with it (it follows the card as the row glides): its name, bold, and below it "container ·
// resolution" in tiny light capitals behind a small play arrow (an Android game's package name stands in).
@Composable
private fun XmbSteamTitle(
    focused: Shortcut?,
    sdBadge: @Composable (Shortcut) -> Unit,
    sl: XmbSteamLayout,
    row: XmbSteamRow,
    target: Int,
    pos: MutableFloatState,
    accent: Color,
    ss: ScreenScale,
    lift: () -> Float,
) {
    val context = LocalContext.current
    val ink = remember(accent) { xmbInkColor(accent, Color.White, XMB_STEAM_NAVY) }
    AnimatedContent(
        targetState = focused,
        contentKey = { it?.file?.path },
        transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 }) togetherWith fadeOut(tween(90)) },
        label = "xmbSteamTitle",
        modifier = Modifier
            .offset(0.dp, sl.titleY.dp)
            .width(sl.titleW.dp)
            .graphicsLayer {
                translationX = sl.titleX(row, target, pos.floatValue).dp.toPx()
                alpha = 1f - lift()
            },
    ) { s ->
        if (s != null) {
            val meta = remember(s) { AndroidGames.packageOf(s) ?: buildLaunchSpec(s, context).meta }
            Column {
                Text(
                    s.name, color = Color.White, fontSize = ss.sp(16f), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = XMB_STEAM_TEXT.copy(lineHeight = ss.sp(15f), lineHeightStyle = XMB_STEAM_TOP),
                )
                // 2 dp under the title's line box (its descenders hang over it): the two nearly touch.
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotEmpty()) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = ink.copy(alpha = 0.9f), modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            meta.uppercase(), color = XMB_STEAM_MUTED, fontSize = ss.sp(10f), letterSpacing = 0.8.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            style = XMB_STEAM_TEXT.copy(lineHeight = ss.sp(10f), lineHeightStyle = XMB_STEAM_TIGHT),
                        )
                    }
                    sdBadge(s)
                }
            }
        }
    }
}

// The plain text tabs: small, upper case, letter-spaced, evenly spaced and centred on the screen. The one under the cursor (while the
// tabs hold it) is pure white in a thin white outline pill on a faint accent fill; the rest are bold light grey.
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
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .offset(0.dp, sl.tabsTop.dp)
            .fillMaxWidth()
            .height(XMB_STEAM_TABS_H.dp)
            .graphicsLayer { alpha = 1f - lift() },
        contentAlignment = Alignment.Center,
    ) {
        // Wider than the screen (a narrow one), the row scrolls.
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            color = if (on) Color.White else XMB_STEAM_TAB_INK,
                            fontSize = ss.sp(11f),
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            style = XMB_STEAM_TEXT,
                            modifier = Modifier
                                .clip(shape)
                                .background(if (on) accent.copy(alpha = 0.22f) else Color.Transparent)
                                .then(if (on) Modifier.border(1.5.dp, Color.White, shape) else Modifier)
                                .padding(horizontal = XMB_STEAM_TAB_PAD.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
