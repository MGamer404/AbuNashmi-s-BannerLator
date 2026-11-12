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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

/** The backdrop's gradient ends in these: dark navy, then nearly black at the bottom edge. */
private val XMB_STEAM_NAVY = Color(0xFF0B1426)
private val XMB_STEAM_NAVY_DEEP = Color(0xFF04060C)

/** The subtitle's text: muted. */
private val XMB_STEAM_MUTED = Color(0xFF9AA4B5)

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
 * the device) crossfading as the cursor moves: as wide as the screen and top aligned on a landscape one (Steam's hero is about 3:1, so
 * it fills the upper part, not zoomed in), cropped to fill a portrait one. A dark scrim over the top fifth keeps the shell's header
 * readable, and a gradient to dark navy at the bottom takes the art under the covers. [picture] (the cover) is the usual blurred
 * backdrop, which stays underneath: it shows while the art loads and when there is none.
 */
@Composable
internal fun XmbSteamBackdrop(game: Shortcut?, picture: Any?, landscape: Boolean) {
    val context = LocalContext.current
    val path = game?.file?.path
    LaunchedEffect(path) { if (game != null) withContext(Dispatchers.IO) { LabArt.resolve(context, game) } }
    val tick = LabArt.tick
    val hero = remember(path, tick) { game?.let { g -> XmbSteamHero(LabArt.models(g, "bg").map { LabArt.Pic(it) }) } }
    Box(Modifier.fillMaxSize()) {
        XmbHomeBackdrop(picture)
        Crossfade(targetState = hero, animationSpec = tween(600), label = "xmbSteamHero") { h ->
            if (h != null) {
                XmbSteamPicture(
                    h.pics, Modifier.fillMaxSize(),
                    contentScale = if (landscape) ContentScale.FillWidth else ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.55f),
                    0.20f to Color.Transparent,
                    0.42f to Color.Transparent,
                    0.62f to XMB_STEAM_NAVY.copy(alpha = 0.85f),
                    0.80f to XMB_STEAM_NAVY_DEEP.copy(alpha = 0.98f),
                    1f to XMB_STEAM_NAVY_DEEP,
                ),
            ),
        )
    }
}

/**
 * The row of covers (the whole library, recent first), the focused game's title and the text tabs. [lift] is 0 normally and 1 while
 * the options are open: the covers lift (the column opens under the focused one), the others dim and the title and tabs fade out.
 * [tabsActive] = the tabs hold the cursor, on tab [tabSel]; [tabsEnabled] = they can be tapped. [onCard] / [onTab] are taps.
 */
@Composable
internal fun XmbSteamHome(
    shortcuts: List<Shortcut>,
    pos: MutableFloatState,
    target: Int,
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
                XmbSteamCover(s, i, pos, sl, row, accent, i == target, lift) { onCard(i) }
            }
        }
        XmbSteamTitle(focused, sdBadge, sl, row, target, pos, accent, ss, lift)
        if (tabs.isNotEmpty()) XmbSteamTabs(tabs, tabSel, tabsActive, tabsEnabled, sl, accent, ss, lift, onTab)
    }
}

// A card's place in the row: its size, then its drawn size, position and fade from its distance to the cursor, read in the graphics
// layer so a swipe re-draws without recomposing. All cards share a bottom edge, so the focused (larger) one grows upward; [lift] raises
// the whole row while the options are open and dims the cards that are not focused.
private fun Modifier.xmbSteamSlot(
    index: Int, pos: MutableFloatState, sl: XmbSteamLayout, row: XmbSteamRow, focused: Boolean, lift: () -> Float,
): Modifier = this
    .zIndex(if (focused) 1f else 0f)
    .size(sl.cardW(index, row.count).dp, sl.fH.dp)
    .graphicsLayer {
        val p = pos.floatValue
        val sc = xmbSteamScale(index, p)
        val l = lift()
        translationX = row.x(index, p).dp.toPx()
        translationY = (sl.cardTop - sl.lift * l).dp.toPx()
        scaleX = sc
        scaleY = sc
        transformOrigin = TransformOrigin(0f, 1f)
        alpha = if (focused) 1f else 1f - 0.45f * l
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
    lift: () -> Float,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(XMB_STEAM_CORNER)
    val src = remember { MutableInteractionSource() }
    val mark = remember(shortcut) { storeMarkOf(shortcut) }
    val chip = (sl.fW * 0.34f).coerceIn(20f, 26f).dp
    Box(
        Modifier
            .xmbSteamSlot(index, pos, sl, row, focused, lift)
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
        // Where the Steam Deck draws its controller badge: the store's mark on a small dark chip, on every card, scaled with it.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(3.dp)
                .size(chip)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.62f)),
            contentAlignment = Alignment.Center,
        ) {
            XmbStoreMark(mark, Modifier, chip * 0.68f)
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
// resolution" in tiny muted capitals behind a small play arrow (an Android game's package name stands in).
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
                    s.name, color = Color.White, fontSize = ss.sp(16f), lineHeight = ss.sp(20f), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (meta.isNotEmpty()) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = ink.copy(alpha = 0.9f), modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            meta.uppercase(), color = XMB_STEAM_MUTED, fontSize = ss.sp(10f), letterSpacing = 0.8.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    sdBadge(s)
                }
            }
        }
    }
}

// The plain text tabs: small, upper case, letter-spaced, evenly spaced and centred on the screen. The one under the cursor (while the
// tabs hold it) is tinted with the accent on a faint accent fill; the rest are dim white.
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
}
