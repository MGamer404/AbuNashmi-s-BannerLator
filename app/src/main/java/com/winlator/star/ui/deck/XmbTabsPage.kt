package com.winlator.star.ui.deck

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.winlator.star.R
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.LocalTopBarOverlayInset
import com.winlator.star.ui.LocalXmbHintInset
import com.winlator.star.ui.screens.XMB_PAGE_TABS
import com.winlator.star.ui.screens.XmbSteamTab
import com.winlator.star.ui.screens.xmbClick
import com.winlator.star.ui.screens.xmbTabStep

// The XMB Home's tabs page, in the look of the Steam Deck's What's New / Friends / Recommended pages. Down from the games row pushes the
// Home up and out and brings the page up from the bottom: a pill bar of tabs (Stores, Downloads, Friends, Community configs) under the
// shell's floating header, the tab's page under it, and the shell's Back / Search hints (and Start Add Game on the Stores tab) over it.
// B, or Up from the pill bar, or a swipe down slides it back down onto the Home, whose focused game has not moved. The rules (the
// order of the tabs, the Stores grid) are in screens/XmbTabsLogic.kt; what a page gets from the host is XmbTabsPageFocus.kt.

/** How long the Home and the page take to slide past each other, in ms. */
private const val XMB_TABS_SLIDE_MS = 420

/** A downward drag of this many dp on the page closes it. */
private const val XMB_TABS_SWIPE_CLOSE_DP = 64f

/** The tabs' text on the pill bar: light, a step under the selected tab's white. */
private val XMB_TABS_INK = Color.White.copy(alpha = 0.78f)

/** The selected tab's pill: dark, see-through. */
private val XMB_TABS_PILL = Color.Black.copy(alpha = 0.5f)

/** The thin light outline of the cursor, as on the Steam Home's cards. */
private val XMB_TABS_OUTLINE = Color.White.copy(alpha = 0.92f)

/** The green of a count badge (the same green as the A hint). */
private val XMB_TABS_BADGE = Color(0xFF3DDC84)

/**
 * The room the shell's floating header takes at the top of the Home (dp): its top padding and its height, as DeckFloatingBar lays
 * them out (keep in step with it). The camera hole's top edge comes on top of that.
 */
private fun xmbFloatingBarBand(ss: ScreenScale, portrait: Boolean): Dp =
    ss.dp(if (portrait) 2f else 6f) + ss.dp(40f * (if (portrait) 0.8f else 1f))

private fun XmbSteamTab.labelRes(): Int = when (this) {
    XmbSteamTab.STORES -> R.string.xmb_tab_stores
    XmbSteamTab.DOWNLOADS -> R.string.xmb_tab_downloads
    XmbSteamTab.FRIENDS -> R.string.xmb_tab_friends
    XmbSteamTab.COMMUNITY -> R.string.xmb_tab_community
}

/**
 * The Home with the tabs page over it. [open] slides the Home ([home], kept composed, so its focused game stays) up and out and the page
 * in from the bottom, and false slides them back; [tab] is the shown tab's index in [XMB_PAGE_TABS] (any value: it is kept on the list)
 * and [onTab] / [onClose] are asked to change it / to close the page. The Stores tab shows [signedIn] and [games] and opens a store with
 * [onStore]; Start (or the hint) runs [onAddGame]. [badges] are the counts the pill bar shows on a tab (none for a missing or zero one).
 * While the page is up the triggers L2 / R2 step the tab from anywhere (through the shell's DeckActions.subTab, so pads whose triggers
 * are analog work too).
 */
@Composable
internal fun XmbTabsPageHost(
    open: Boolean,
    tab: Int,
    onTab: (Int) -> Unit,
    onClose: () -> Unit,
    signedIn: Map<DeckStore, Boolean>,
    games: List<Shortcut>,
    onStore: (DeckStore) -> Unit,
    onAddGame: () -> Unit,
    badges: Map<XmbSteamTab, Int> = emptyMap(),
    home: @Composable () -> Unit,
) {
    val progress = animateFloatAsState(if (open) 1f else 0f, tween(XMB_TABS_SLIDE_MS, easing = FastOutSlowInEasing), label = "xmbTabsPage")
    val openNow by rememberUpdatedState(open)
    // The page is composed while it is up or still sliding away; derived, so the animation does not recompose this per frame.
    val shown by remember { derivedStateOf { openNow || progress.value > 0.001f } }
    Box(Modifier.fillMaxSize()) {
        // Read in the graphics layers, so the slide re-draws without recomposing.
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = -progress.value * size.height }) { home() }
        if (shown) XmbTabsPageBody(open, progress, tab, onTab, onClose, signedIn, games, onStore, onAddGame, badges)
    }
}

@Composable
private fun XmbTabsPageBody(
    open: Boolean,
    progress: State<Float>,
    tab: Int,
    onTab: (Int) -> Unit,
    onClose: () -> Unit,
    signedIn: Map<DeckStore, Boolean>,
    games: List<Shortcut>,
    onStore: (DeckStore) -> Unit,
    onAddGame: () -> Unit,
    badges: Map<XmbSteamTab, Int>,
) {
    val tabs = XMB_PAGE_TABS
    val sel = tab.coerceIn(0, tabs.lastIndex)
    val current = tabs[sel]
    val ss = rememberScreenScale()
    val deckActions = LocalDeckActions.current
    val focus = remember { XmbTabsPageFocus() }
    val selNow by rememberUpdatedState(sel)
    val onTabNow by rememberUpdatedState(onTab)
    val onCloseNow by rememberUpdatedState(onClose)
    val onAddNow by rememberUpdatedState(onAddGame)
    val onStores = current == XmbSteamTab.STORES

    // The phone layout has no floating header nor hints (the shell keeps its bars there), so it needs no room for them, and shows the
    // Add Game hint itself.
    val compact = isDeckCompact(LocalConfiguration.current)
    val dir = LocalLayoutDirection.current
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val top = maxOf(
        LocalTopBarOverlayInset.current,
        if (compact) 0.dp else cutout.calculateTopPadding() + xmbFloatingBarBand(ss, portrait = !deckRails()),
    )
    // The same gutter on both sides (the wider of the two camera-hole insets), so the pill bar and the pages are centred on the screen.
    val cutoutSide = if (compact) 0.dp else maxOf(cutout.calculateStartPadding(dir), cutout.calculateEndPadding(dir))
    val gutterStart = ss.dp(16f) + cutoutSide
    val gutterEnd = ss.dp(16f) + cutoutSide
    val pad = PaddingValues(start = gutterStart, top = ss.dp(8f), end = gutterEnd, bottom = LocalXmbHintInset.current + ss.dp(8f))
    if (focus.contentPadding != pad) focus.contentPadding = pad

    // L2 / R2 step the tab, wrapping: through the shell, which also turns an analog trigger into the button.
    DisposableEffect(open, deckActions) {
        if (!open) return@DisposableEffect onDispose {}
        val step: (Int) -> Unit = { d -> onTabNow(xmbTabStep(selNow, d, tabs.size, wrap = true)) }
        deckActions.subTab.value = step
        onDispose { if (deckActions.subTab.value === step) deckActions.subTab.value = null }
    }
    // The Stores tab's "Start Add Game" hint in the shell's row of floating hints.
    DisposableEffect(open && onStores && !compact, deckActions) {
        if (!(open && onStores && !compact)) return@DisposableEffect onDispose {}
        val run: () -> Unit = { onAddNow() }
        deckActions.xmbAddGameHint.value = run
        onDispose { if (deckActions.xmbAddGameHint.value === run) deckActions.xmbAddGameHint.value = null }
    }

    // The keys come to one focus target: taken when the page opens and again when the app comes back (a store's own screen).
    val requester = remember { FocusRequester() }
    LaunchedEffect(open) {
        if (open) {
            // It opens with the cursor on the pill bar (also when it is opened again while still sliding away).
            focus.contentFocused = false
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, open) {
        if (!open) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) runCatching { requester.requestFocus() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = open) { onCloseNow() }

    // A swipe down closes it, from anywhere on the page that no list under the finger takes the drag for.
    val closePx = with(LocalDensity.current) { XMB_TABS_SWIPE_CLOSE_DP.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    var dragDone by remember { mutableStateOf(false) }
    val drag = rememberDraggableState { d ->
        if (dragDone || !open) return@rememberDraggableState
        dragged += d
        if (dragged >= closePx) { dragDone = true; onCloseNow() }
    }

    val onKeyEvent: (KeyEvent) -> Boolean = keys@{ e ->
        if (!open || e.type != KeyEventType.KeyDown) return@keys false
        val first = e.nativeKeyEvent.repeatCount == 0
        // Start adds a game while the Stores tab shows, wherever the cursor is.
        if (e.key == Key.ButtonStart && onStores) {
            if (first) onAddNow()
            return@keys true
        }
        if (focus.contentFocused) {
            if (focus.onKey?.invoke(e) == true) return@keys true
            return@keys when (e.key) {
                Key.DirectionUp -> { focus.toBar(); true }
                Key.ButtonB, Key.Escape -> { if (first) onCloseNow(); true }
                Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> true
                else -> false
            }
        }
        when (e.key) {
            Key.DirectionLeft -> { onTabNow(xmbTabStep(selNow, -1, tabs.size, wrap = false)); true }
            Key.DirectionRight -> { onTabNow(xmbTabStep(selNow, 1, tabs.size, wrap = false)); true }
            Key.DirectionDown, Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> { focus.contentFocused = true; true }
            Key.DirectionUp, Key.ButtonB, Key.Escape -> { if (first) onCloseNow(); true }
            else -> false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // From the bottom: 1 = in place, 0 = a whole screen down.
            .graphicsLayer { translationY = (1f - progress.value) * size.height }
            .draggable(drag, Orientation.Vertical, onDragStarted = { dragged = 0f; dragDone = false })
            .onPreviewKeyEvent(onKeyEvent)
            .focusRequester(requester)
            .focusable(),
    ) {
        XmbGlassBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(top = top)) {
            XmbTabsBar(
                tabs = tabs,
                sel = sel,
                barFocused = !focus.contentFocused,
                badges = badges,
                ss = ss,
                modifier = Modifier.padding(start = gutterStart, end = gutterEnd),
                onSelect = { i -> onTabNow(i) },
                onStep = { d -> onTabNow(xmbTabStep(selNow, d, tabs.size, wrap = true)) },
            )
            // The page's own box takes everything under the bar, down to the screen's edge; the host adds no padding (see XmbTabsPageFocus).
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = sel,
                    transitionSpec = {
                        val way = if (targetState >= initialState) 1 else -1
                        (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { w -> way * w / 5 }) togetherWith
                            (fadeOut(tween(140)) + slideOutHorizontally(tween(260)) { w -> -way * w / 5 })
                    },
                    label = "xmbTabsContent",
                    modifier = Modifier.fillMaxSize(),
                ) { i ->
                    when (tabs[i.coerceIn(0, tabs.lastIndex)]) {
                        XmbSteamTab.STORES -> XmbStoresPage(focus, signedIn, games, onStore)
                        XmbSteamTab.DOWNLOADS -> XmbDownloadsPage(focus)
                        XmbSteamTab.FRIENDS -> XmbFriendsPage(focus)
                        XmbSteamTab.COMMUNITY -> XmbCommunityPage(focus)
                    }
                }
                // No floating hints on a phone: the same hint, in the page's corner.
                if (compact && onStores) {
                    DeckFloatingHint(
                        "≡", GlyphKind.Y, stringResource(R.string.xmb_hint_add_game), { onAddNow() },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = gutterEnd, bottom = ss.dp(8f)).defaultMinSize(minHeight = 48.dp),
                    )
                }
            }
        }
    }
}

/**
 * The pill bar: L2, the tabs, R2, centred (a row wider than the screen scrolls, and keeps the selected tab in view). The selected tab is
 * a dark see-through pill with white bold text, ringed in the thin light outline while the bar holds the cursor; the others are bold
 * light text. A tab's [badges] count shows as a small green badge after its name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun XmbTabsBar(
    tabs: List<XmbSteamTab>,
    sel: Int,
    barFocused: Boolean,
    badges: Map<XmbSteamTab, Int>,
    ss: ScreenScale,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
    onStep: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ss.dp(6f), Alignment.CenterHorizontally),
    ) {
        XmbBarBumper("L2") { onStep(-1) }
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ss.dp(4f)),
        ) {
            tabs.forEachIndexed { i, t ->
                val on = i == sel
                val src = remember { MutableInteractionSource() }
                val bring = remember { BringIntoViewRequester() }
                LaunchedEffect(on) { if (on) runCatching { bring.bringIntoView() } }
                val count = badges[t] ?: 0
                Box(
                    Modifier
                        .fillMaxHeight()
                        .defaultMinSize(minWidth = 48.dp)
                        .bringIntoViewRequester(bring)
                        .xmbClick(src) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ss.dp(6f)),
                        modifier = Modifier
                            .clip(shape)
                            .background(if (on) XMB_TABS_PILL else Color.Transparent)
                            .then(if (on && barFocused) Modifier.border(1.5.dp, XMB_TABS_OUTLINE, shape) else Modifier)
                            .padding(horizontal = ss.dp(14f), vertical = ss.dp(7f)),
                    ) {
                        Text(
                            stringResource(t.labelRes()).uppercase(),
                            color = if (on) Color.White else XMB_TABS_INK,
                            fontSize = ss.sp(11f),
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        // The slot for a count (the Friends tab's, later).
                        if (count > 0) {
                            Box(
                                Modifier.defaultMinSize(minWidth = ss.dp(16f), minHeight = ss.dp(16f)).clip(CircleShape).background(XMB_TABS_BADGE).padding(horizontal = ss.dp(4f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(count.toString(), color = Color.Black, fontSize = ss.sp(9f), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        XmbBarBumper("R2") { onStep(1) }
    }
}

/** An L2 / R2 glyph on the pill bar, the same keycap as the header's L1 / R1: tapping it steps the tab as the trigger does. */
@Composable
private fun XmbBarBumper(text: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxHeight()
            .defaultMinSize(minWidth = 40.dp)
            .clip(deckShape(10.dp))
            .xmbClick(src, onClick)
            .padding(horizontal = 4.dp)
            .graphicsLayer { alpha = 0.6f },
    ) {
        DeckGlyph(text, GlyphKind.BUMPER)
    }
}
