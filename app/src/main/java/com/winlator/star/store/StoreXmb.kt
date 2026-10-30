package com.winlator.star.store

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.deck.ScreenScale
import com.winlator.star.ui.deck.XmbScreenWindow
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.rememberScreenScale
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.deck.xmbFrame
import com.winlator.star.ui.theme.LocalAccentDim

// The store screens in the Deck interface's XMB-home mode: edge to edge in an even frame, a slim rail, a compact search
// field that types only on A, and portrait covers that pop when focused. Everything here is used only while
// [LocalStoreXmb] is true; the other interfaces keep the storefront exactly as it was.

/** True inside a store screen running in XMB-home mode (set by [StoreXmbScreen]). */
internal val LocalStoreXmb = staticCompositionLocalOf { false }

/**
 * Wraps a store screen. In XMB-home mode the window goes edge to edge with no system bars, the background runs to the
 * screen edges and the content sits in the even camera frame, clear of the keyboard; otherwise [content] is untouched.
 */
@Composable
internal fun StoreXmbScreen(content: @Composable () -> Unit) {
    val xmb = rememberXmbHome()
    XmbScreenWindow(xmb)
    CompositionLocalProvider(LocalStoreXmb provides xmb) {
        if (!xmb) {
            content()
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.ime)
                    .padding(horizontal = xmbFrame()),
            ) { content() }
        }
    }
}

/** The slim rail's width: just room for an icon over a short label. */
internal fun ScreenScale.xmbRailWidth(): Dp = w(0.07f, min = 60.dp, max = 88.dp)

/** Width of one cover in the store rails and grids. */
internal fun ScreenScale.xmbCoverWidth(): Dp = w(0.12f, min = 96.dp, max = 170.dp)

/** The catalog stores' library grid column: the XMB cover in XMB home, else their own 132 / 118 dp. */
@Composable
internal fun storeGridCell(wide: Boolean): Dp =
    if (LocalStoreXmb.current) rememberScreenScale().xmbCoverWidth() else if (wide) 132.dp else 118.dp

/** Steam's 600x900 library cover: the published one when the snapshot has it, else the CDN path. */
internal fun steamTallArt(appId: Int): String =
    SteamLibraryArt.libraryCapsule(appId)
        ?: "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900.jpg"

/** One rail entry: icon over a one-line label (the count, if any, on a second line). */
@Composable
internal fun XmbRailItem(label: String, icon: ImageVector, selected: Boolean, count: Int?, onClick: () -> Unit) {
    val s = rememberScreenScale()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(s.dp(10f))
    val ink = if (selected) cs.primary else cs.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .steamFocusRing(shape)
            .clip(shape)
            .background(if (selected) LocalAccentDim.current else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = s.dp(6f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(s.dp(20f)))
        Spacer(Modifier.height(s.dp(3f)))
        Text(
            label, color = ink, fontSize = s.sp(10f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (count != null) Text("$count", color = cs.onSurfaceVariant, fontSize = s.sp(9f), maxLines = 1)
    }
}

/**
 * A portrait (2:3) cover with the name small at its bottom, the way the Xbox library draws its games. Focused, it pops
 * a little (scale, accent ring and glow) and shows [focusDetail] (a rating) under the name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun XmbStoreCover(
    art: List<String>,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** A small pill over the art's top-left corner ("Gen 2", "-50%"); null = none. */
    badge: String? = null,
    /** A line under the name ("Installed", "Downloading 34%"); null = none. */
    status: String? = null,
    statusAccent: Boolean = false,
    /** Shown under the name only while the cover is focused; null = nothing. */
    focusDetail: String? = null,
    /** Extra lines under the name (a compact price row). */
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val s = rememberScreenScale()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(s.dp(10f))
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .deckFocusRing(shape, scaleTo = 1.06f)
            .clip(shape)
            .border(1.dp, cs.outline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        CatalogArt(candidates = art, title = title, modifier = Modifier.fillMaxWidth(), aspectRatio = 2f / 3f)
        if (!badge.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(s.dp(5f))
                    .clip(RoundedCornerShape(5.dp))
                    .background(cs.surfaceContainerHighest.copy(alpha = 0.9f))
                    .padding(horizontal = s.dp(5f), vertical = 1.dp),
            ) {
                Text(badge, fontSize = s.sp(9.5f), fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1)
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                .padding(start = s.dp(7f), end = s.dp(7f), top = s.dp(18f), bottom = s.dp(6f)),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title, fontSize = s.sp(11f), fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (status != null) {
                Text(
                    status, fontSize = s.sp(9.5f), maxLines = 1,
                    color = if (statusAccent) cs.primary else Color.White.copy(alpha = 0.8f),
                )
            }
            footer?.invoke(this)
            if (focused && focusDetail != null) {
                Text(focusDetail, fontSize = s.sp(9.5f), color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A game as a wide row: its portrait cover beside the eyebrow, name and [footer] (price, rating). [compact] is the
 * smaller row for lists ("Top Free Games"); otherwise it is the featured hero.
 */
@Composable
internal fun XmbFeatureCard(
    art: List<String>,
    title: String,
    eyebrow: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val s = rememberScreenScale()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(s.dp(12f))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .deckFocusRing(shape, scaleTo = 1.03f)
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline, shape)
            .clickable(onClick = onClick)
            .padding(s.dp(8f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.dp(12f)),
    ) {
        CatalogArt(
            candidates = art, title = title, aspectRatio = 2f / 3f,
            modifier = Modifier.width(if (compact) s.w(0.05f, min = 44.dp, max = 80.dp) else s.w(0.09f, min = 76.dp, max = 140.dp)),
            shape = RoundedCornerShape(s.dp(8f)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(s.dp(if (compact) 3f else 5f))) {
            if (eyebrow.isNotBlank()) {
                Text(eyebrow, fontSize = s.sp(10f), fontWeight = FontWeight.Bold, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                title, fontSize = s.sp(if (compact) 12.5f else 16f), fontWeight = if (compact) FontWeight.Bold else FontWeight.ExtraBold,
                color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            footer()
        }
    }
}

/**
 * The compact search field. D-pad focus only highlights it; A / Enter / a tap starts typing (keyboard up), and the
 * keyboard's Search, Back or B ends typing and leaves focus on the field, so passing over it never opens the keyboard.
 */
@Composable
internal fun XmbSearchField(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = rememberScreenScale()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(s.dp(9f))
    val keyboard = LocalSoftwareKeyboardController.current
    val outer = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var lit by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }
    val stopEditing = {
        editing = false
        keyboard?.hide()
        runCatching { outer.requestFocus() }
        Unit
    }
    Row(
        modifier = modifier
            .widthIn(max = s.w(0.45f, min = 240.dp, max = 520.dp))
            .height(s.h(0.09f, min = 40.dp, max = 52.dp))
            .onPreviewKeyEvent { e ->
                when {
                    !editing && e.type == KeyEventType.KeyDown &&
                        (e.key == Key.ButtonA || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter) -> {
                        editing = true; true
                    }
                    editing && (e.key == Key.Back || e.key == Key.ButtonB || e.key == Key.Escape) -> {
                        if (e.type == KeyEventType.KeyUp) stopEditing()
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { lit = it.hasFocus }
            .focusRequester(outer)
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(if (lit || editing) 2.dp else 1.dp, if (lit || editing) cs.primary else cs.outline, shape)
            .clickable { editing = true }
            .padding(horizontal = s.dp(10f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(s.dp(18f)))
        Spacer(Modifier.width(s.dp(8f)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(placeholder, fontSize = s.sp(12.5f), color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = s.sp(12.5f)),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(); stopEditing() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        // Leaving the field (a tap elsewhere) ends typing; the first report (not focused yet) does not.
                        if (fieldFocused && !it.isFocused) editing = false
                        fieldFocused = it.isFocused
                    }
                    .focusRequester(field)
                    .focusProperties { canFocus = editing },
            )
            // Not typing: the text field can't take focus, so a tap on it is caught here and starts typing.
            if (!editing) {
                Box(
                    Modifier
                        .matchParentSize()
                        .focusProperties { canFocus = false }
                        .clickable { editing = true },
                )
            }
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close, contentDescription = "Clear search", tint = cs.onSurfaceVariant,
                modifier = Modifier
                    .size(s.dp(26f))
                    .clip(RoundedCornerShape(50))
                    .focusProperties { canFocus = false }
                    .clickable(onClick = onClear)
                    .padding(s.dp(4f)),
            )
        }
    }
}
