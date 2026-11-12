package com.winlator.star.ui.deck

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.winlator.star.communityconfigs.CanonicalGame
import com.winlator.star.ui.screens.CommunityPick
import com.winlator.star.ui.screens.xmbClick

// The pieces of the XMB Home's Community configs page (XmbCommunityPage.kt): the search field and chips of its filter strip, the game
// cards, a game's config rows and the centred messages. Same look as the Stores page: dark see-through cards, the thin light outline on
// the one under the cursor.

/** The thin light outline of the card under the cursor. */
internal val XMB_CC_OUTLINE = Color.White.copy(alpha = 0.92f)

/** The faint edge of a card or chip that does not hold the cursor. */
private val XMB_CC_LINE = Color.White.copy(alpha = 0.10f)

private val XMB_CC_FILL = Color.White.copy(alpha = 0.07f)
private val XMB_CC_FILL_ON = Color.White.copy(alpha = 0.16f)

/** Secondary text. */
internal val XMB_CC_DIM = Color.White.copy(alpha = 0.72f)

/** A chip that is switched on: light, with dark text. */
private val XMB_CC_PILL_ON = Color.White.copy(alpha = 0.92f)
private val XMB_CC_PILL_INK = Color(0xFF14171C)

/** The green of "fits your device" (the same green as the tabs' badge and the A hint). */
private val XMB_CC_GREEN = Color(0xFF3DDC84)

/** The orange of a config shared through the app's own repo (as the dialog's BANNERLATOR pill). */
private val XMB_CC_ORANGE = Color(0xFFE0701C)

/** A Steam game's wide capsule art. A game without a Steam id, or a picture that does not load, keeps the tinted letter tile under it. */
private fun xmbCcArtUrl(appId: String) = "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg"

/** One uploaded config (or, offline, one device's config) of the game on show, as its row draws it. */
internal data class XmbCcConfigRow(
    val pick: CommunityPick,
    /** A stable key for the lazy list. */
    val key: String,
    val title: String,
    val sub: String,
    /** The votes and downloads, or null for a device row (they are not known offline). */
    val votes: Int?,
    val downloads: Int?,
    val fits: Boolean,
    val ownRepo: Boolean,
)

/**
 * The search field: a pill that is a stop of the page's cursor (the host holds the keys; A or X starts typing through [edit], and the
 * keyboard's Search, Back or B ends it), a tap starts typing, and the cross clears it.
 */
@Composable
internal fun XmbCcSearch(
    query: String,
    hint: String,
    clearLabel: String,
    edit: XmbEditState,
    focused: Boolean,
    ss: ScreenScale,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val lit = focused || edit.active
    Box(modifier.height(48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                // The outer stop takes the focus back when typing ends, so the keys keep reaching the host.
                .focusRequester(edit.outer)
                .focusable()
                .clip(shape)
                .background(if (lit) XMB_CC_FILL_ON else XMB_CC_FILL)
                .border(if (lit) 1.5.dp else 1.dp, if (lit) XMB_CC_OUTLINE else XMB_CC_LINE, shape)
                .padding(start = ss.dp(14f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = XMB_CC_DIM, modifier = Modifier.size(ss.dp(18f)))
            Spacer(Modifier.width(ss.dp(8f)))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(hint, color = XMB_CC_DIM, fontSize = ss.sp(12f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = ss.sp(12.5f)),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { edit.stop() }),
                    modifier = Modifier.fillMaxWidth().xmbEditField(edit),
                )
                XmbEditTapCatcher(edit)
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(48.dp)
                        .focusProperties { canFocus = false }
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = clearLabel, tint = XMB_CC_DIM, modifier = Modifier.size(ss.dp(18f)))
                }
            }
        }
    }
}

/**
 * A pill of the filter strip. [on]: switched on (light, dark text); [focused]: the cursor is on it; a disabled one is dimmed and does nothing.
 * [leading] draws before the label (a spinner or an icon). The cursor keeps it in view while the strip scrolls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun XmbCcChip(
    label: String,
    on: Boolean,
    focused: Boolean,
    enabled: Boolean,
    ss: ScreenScale,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val src = remember { MutableInteractionSource() }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) runCatching { bring.bringIntoView() } }
    Box(
        modifier
            .height(48.dp)
            .bringIntoViewRequester(bring)
            .xmbClick(src) { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                .clip(shape)
                .background(if (on) XMB_CC_PILL_ON else XMB_CC_FILL)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) XMB_CC_OUTLINE else XMB_CC_LINE, shape)
                .padding(horizontal = ss.dp(14f), vertical = ss.dp(8f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ss.dp(6f)),
        ) {
            leading?.invoke()
            Text(
                label,
                color = if (on) XMB_CC_PILL_INK else Color.White,
                fontSize = ss.sp(11.5f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** A spinner sized for a chip's leading slot. */
@Composable
internal fun XmbCcSpinner(size: Dp, color: Color = Color.White) {
    CircularProgressIndicator(modifier = Modifier.size(size), strokeWidth = 2.dp, color = color)
}

/** A small label on a card or row: green for "fits", orange for the app's own repo, light otherwise. */
@Composable
private fun XmbCcTag(text: String, fill: Color, ink: Color, ss: ScreenScale) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .padding(horizontal = ss.dp(5f), vertical = ss.dp(1.5f)),
    ) {
        Text(text, color = ink, fontSize = ss.sp(8.5f), fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** The game's art: its Steam capsule over a tinted tile with the name's first letter (kept for a game without art and while one loads). */
@Composable
internal fun XmbCcArt(game: CanonicalGame, modifier: Modifier) {
    val tint = remember(game.name) { Color.hsv(Math.floorMod(game.name.hashCode(), 360).toFloat(), 0.45f, 0.40f) }
    val letter = remember(game.name) { game.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?" }
    Box(
        modifier.background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = Color.White.copy(alpha = 0.55f), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        game.steamAppId?.let { id ->
            AsyncImage(model = xmbCcArtUrl(id), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** One game: its art on the left, its name, how many configs and devices it has and what the library and this device make of it. */
@Composable
internal fun XmbCcGameCard(
    game: CanonicalGame,
    counts: String,
    fits: Boolean,
    inLibrary: Boolean,
    tagFits: String,
    tagLibrary: String,
    focused: Boolean,
    height: Dp,
    ss: ScreenScale,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val src = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(if (focused) XMB_CC_FILL_ON else XMB_CC_FILL)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) XMB_CC_OUTLINE else XMB_CC_LINE, shape)
            .xmbClick(src, onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        XmbCcArt(game, Modifier.width(height * 1.6f).fillMaxHeight())
        Column(
            Modifier.weight(1f).padding(horizontal = ss.dp(10f), vertical = ss.dp(4f)),
            verticalArrangement = Arrangement.spacedBy(ss.dp(2f), Alignment.CenterVertically),
        ) {
            Text(game.name, color = Color.White, fontSize = ss.sp(12.5f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(counts, color = XMB_CC_DIM, fontSize = ss.sp(10.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (fits || inLibrary) {
                Row(horizontalArrangement = Arrangement.spacedBy(ss.dp(4f))) {
                    if (fits) XmbCcTag(tagFits, XMB_CC_GREEN, Color.Black, ss)
                    if (inLibrary) XmbCcTag(tagLibrary, Color.White.copy(alpha = 0.22f), Color.White, ss)
                }
            }
        }
    }
}

/** The header of a game's page: a Back button, its art, its name, counts and the device line, and its tags. */
@Composable
internal fun XmbCcDetailHeader(
    game: CanonicalGame,
    counts: String,
    deviceLine: String,
    fits: Boolean,
    inLibrary: Boolean,
    tagFits: String,
    tagLibrary: String,
    backLabel: String,
    height: Dp,
    ss: ScreenScale,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ss.dp(10f))) {
        Row(
            Modifier
                .fillMaxHeight()
                .defaultMinSize(minWidth = 48.dp)
                .clip(RoundedCornerShape(50))
                .xmbClick(src, onBack)
                .padding(horizontal = ss.dp(8f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ss.dp(6f)),
        ) {
            DeckGlyph("B", GlyphKind.B)
            Text(backLabel, color = Color.White, fontSize = ss.sp(11.5f), fontWeight = FontWeight.Bold, maxLines = 1)
        }
        XmbCcArt(game, Modifier.width(height * 1.6f).fillMaxHeight().clip(RoundedCornerShape(6.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ss.dp(2f), Alignment.CenterVertically)) {
            Text(game.name, color = Color.White, fontSize = ss.sp(14f), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(counts, color = XMB_CC_DIM, fontSize = ss.sp(10.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(deviceLine, color = XMB_CC_DIM, fontSize = ss.sp(10.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(ss.dp(3f), Alignment.CenterVertically)) {
            if (fits) XmbCcTag(tagFits, XMB_CC_GREEN, Color.Black, ss)
            if (inLibrary) XmbCcTag(tagLibrary, Color.White.copy(alpha = 0.22f), Color.White, ss)
        }
    }
}

/** A button on a config row: the cursor's row shows the controller glyph that runs it. */
@Composable
private fun XmbCcAction(
    label: String,
    glyph: String,
    kind: GlyphKind,
    showGlyph: Boolean,
    filled: Boolean,
    ss: ScreenScale,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(Modifier.height(48.dp).defaultMinSize(minWidth = 48.dp).xmbClick(src, onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(shape)
                .background(if (filled) XMB_CC_PILL_ON else Color.Transparent)
                .border(1.dp, if (filled) Color.Transparent else XMB_CC_LINE, shape)
                .padding(horizontal = ss.dp(10f), vertical = ss.dp(6f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ss.dp(5f)),
        ) {
            if (showGlyph) DeckGlyph(glyph, kind)
            Text(label, color = if (filled) XMB_CC_PILL_INK else Color.White, fontSize = ss.sp(11f), fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * One config of a game: the device it was made on (and its SoC and date), its votes and downloads, whether it fits this device, and the
 * two things to do with it: apply it to a game (the row itself, A) or read what it sets (X).
 */
@Composable
internal fun XmbCcConfigRowView(
    row: XmbCcConfigRow,
    focused: Boolean,
    tagFits: String,
    tagOwnRepo: String,
    applyLabel: String,
    detailsLabel: String,
    ss: ScreenScale,
    modifier: Modifier = Modifier,
    onApply: () -> Unit,
    onDetails: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val src = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(shape)
            .background(if (focused) XMB_CC_FILL_ON else XMB_CC_FILL)
            .border(if (focused) 1.5.dp else 1.dp, if (focused) XMB_CC_OUTLINE else XMB_CC_LINE, shape)
            .xmbClick(src, onApply)
            .padding(start = ss.dp(12f), end = ss.dp(6f), top = ss.dp(4f), bottom = ss.dp(4f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ss.dp(8f)),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ss.dp(2f))) {
            Text(row.title, color = Color.White, fontSize = ss.sp(12.5f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.sub.isNotEmpty()) {
                Text(row.sub, color = XMB_CC_DIM, fontSize = ss.sp(10.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.fits || row.ownRepo) {
                Row(horizontalArrangement = Arrangement.spacedBy(ss.dp(4f))) {
                    if (row.fits) XmbCcTag(tagFits, XMB_CC_GREEN, Color.Black, ss)
                    if (row.ownRepo) XmbCcTag(tagOwnRepo, XMB_CC_ORANGE, Color.White, ss)
                }
            }
        }
        if (row.votes != null && row.downloads != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text("★ ${row.votes}", color = Color.White, fontSize = ss.sp(11.5f), fontWeight = FontWeight.Bold, maxLines = 1)
                Text("↓ ${row.downloads}", color = XMB_CC_DIM, fontSize = ss.sp(10.5f), maxLines = 1)
            }
        }
        XmbCcAction(detailsLabel, "X", GlyphKind.X, focused, filled = false, ss, onDetails)
        XmbCcAction(applyLabel, "A", GlyphKind.A, focused, filled = true, ss, onApply)
    }
}

/**
 * A centred message: [title] and [body] (either may be empty), a spinner while [busy], and a [button] under them that holds the cursor
 * while [focused] (A, or a tap, runs it).
 */
@Composable
internal fun XmbCcMessage(
    title: String,
    body: String,
    busy: Boolean,
    ss: ScreenScale,
    modifier: Modifier = Modifier,
    button: String? = null,
    buttonFocused: Boolean = false,
    onButton: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ss.dp(8f), Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy && button == null) XmbCcSpinner(ss.dp(26f))
        if (title.isNotEmpty()) {
            Text(title, color = Color.White, fontSize = ss.sp(14f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        if (body.isNotEmpty()) {
            Text(body, color = XMB_CC_DIM, fontSize = ss.sp(11.5f), textAlign = TextAlign.Center)
        }
        if (button != null) {
            XmbCcChip(
                label = button,
                on = false,
                focused = buttonFocused,
                enabled = !busy,
                ss = ss,
                leading = if (busy) ({ XmbCcSpinner(ss.dp(14f)) }) else null,
                onClick = onButton,
            )
        }
    }
}
