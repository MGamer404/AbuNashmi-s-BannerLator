package com.winlator.star.ui.deck

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.R
import com.winlator.star.communityconfigs.CanonicalGame
import com.winlator.star.communityconfigs.GameMatcher
import com.winlator.star.communityconfigs.WorkerConfigEntry
import com.winlator.star.ui.screens.CatalogSort
import com.winlator.star.ui.screens.CommunityCatalog
import com.winlator.star.ui.screens.CommunityPick
import com.winlator.star.ui.screens.ShortcutActionDialogs
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.XMB_CC_GAP_DP
import com.winlator.star.ui.screens.XMB_CC_STRIP_FITS
import com.winlator.star.ui.screens.XMB_CC_STRIP_LIBRARY
import com.winlator.star.ui.screens.XMB_CC_STRIP_REFRESH
import com.winlator.star.ui.screens.XMB_CC_STRIP_SEARCH
import com.winlator.star.ui.screens.XMB_CC_STRIP_SORT
import com.winlator.star.ui.screens.XMB_CC_TO_STRIP
import com.winlator.star.ui.screens.communityFilePick
import com.winlator.star.ui.screens.deviceHeaderLabel
import com.winlator.star.ui.screens.rememberGameConfigs
import com.winlator.star.ui.screens.rememberShortcutActions
import com.winlator.star.ui.screens.xmbCcColumns
import com.winlator.star.ui.screens.xmbCcFittingGames
import com.winlator.star.ui.screens.xmbCcLibraryGames
import com.winlator.star.ui.screens.xmbCcMove
import com.winlator.star.ui.screens.xmbCcNextSort
import com.winlator.star.ui.screens.xmbCcRankConfigs
import com.winlator.star.ui.screens.xmbCcStripStep
import com.winlator.star.ui.screens.xmbCcVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// The Community configs tab of the XMB Home's tabs page: the community configs browser the Games screen's globe button opens as a dialog,
// here as the page itself. A strip of filters over a grid of the games that have configs; A on a game opens its configs; A on a config
// picks the game to apply it to and X reads what it sets (the dialogs of the Games screen's own flow, drawn from here). The rules (the
// filters, the grid's moves, which games the library has or which fit this device) are in screens/XmbCommunityLogic.kt and the list rules
// it shares with the dialog in screens/CommunityBrowserLogic.kt; the pieces drawn are in XmbCommunityParts.kt.
//
// Dropped from the dialog, which had them: the Steam / Title store filter (a game is Steam or not by its art), the "Devices" sort (not a
// thing anyone sorts by), the My account button (the shell's header has it), and the Apply / View details chooser (a config row has both
// buttons, so the extra dialog is gone).

/** How long a note on the refresh pill ("Refreshed", "Couldn't refresh") stays, in ms. */
private const val XMB_CC_NOTICE_MS = 4000L

/** How long the search waits after the last key before the list follows it, in ms. */
private const val XMB_CC_SEARCH_DELAY_MS = 250L

@Composable
internal fun XmbCommunityPage(focus: XmbTabsPageFocus) {
    val resources = LocalContext.current.resources
    val ss = rememberScreenScale()
    val vm: ShortcutsViewModel = viewModel()
    // Apply, details, the game picker and the apply result are the dialogs of the Games screen's flow: this page opens them through its own
    // ShortcutActions, drawn at the end.
    val actions = rememberShortcutActions(vm)
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())

    var catalog by remember { mutableStateOf<CommunityCatalog?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.getCommunityCatalog { catalog = it; loaded = true } }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(XMB_CC_NOTICE_MS)
            notice = null
        }
    }

    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    var onlyFits by remember { mutableStateOf(false) }
    var onlyMine by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(CatalogSort.CONFIGS) }
    // Where the cursor is: on the strip's control [stripSel], or on card [sel] of the grid; inside a game, on its config [cfgSel].
    var inStrip by remember { mutableStateOf(false) }
    var stripSel by remember { mutableIntStateOf(XMB_CC_STRIP_SEARCH) }
    var sel by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
    var cfgSel by remember { mutableIntStateOf(0) }
    val edit = rememberXmbEdit()
    LaunchedEffect(query) {
        delay(XMB_CC_SEARCH_DELAY_MS)
        applied = query
    }

    val cat = catalog
    val games = cat?.games ?: emptyList()
    val userSoc = cat?.userSoc
    val userGpu = cat?.userGpu
    // Which games fit this device and which the library has: worked out off the main thread (null while it is); the chips wait for them.
    val fits by produceState<Set<String>?>(null, cat) {
        value = if (cat == null) null else withContext(Dispatchers.Default) { xmbCcFittingGames(cat.games, cat.userSoc, cat.userGpu) }
    }
    val library by produceState<Set<String>?>(null, cat, shortcuts) {
        value = if (cat == null) null else withContext(Dispatchers.Default) {
            xmbCcLibraryGames(cat.games, shortcuts.map { it.name }, shortcuts.map { it.getExtra("steamAppId", "") })
        }
    }
    val fitSet = fits ?: emptySet()
    val librarySet = library ?: emptySet()
    val visible = remember(games, applied, sort, fits, onlyFits, library, onlyMine) {
        xmbCcVisibleOf(games, applied, sort, fits, onlyFits, library, onlyMine)
    }
    val open = remember(games, openId) { openId?.let { id -> games.firstOrNull { it.identity == id } } }
    LaunchedEffect(open) { cfgSel = 0 }
    // A new list starts at its top.
    val gridState = rememberLazyGridState()
    LaunchedEffect(applied, onlyFits, onlyMine, sort, games) {
        sel = 0
        runCatching { gridState.scrollToItem(0) }
    }

    val refresh: () -> Unit = {
        if (!refreshing) {
            refreshing = true
            vm.refreshCommunityIndex { fresh ->
                refreshing = false
                if (fresh != null) {
                    catalog = fresh
                    loaded = true
                    notice = resources.getQuantityString(R.plurals.xmb_cc_refreshed, fresh.games.size, fresh.games.size)
                } else {
                    notice = resources.getString(R.string.xmb_cc_refresh_failed)
                }
            }
        }
    }

    // The page's width decides the grid's columns and whether the strip takes one row or two.
    BoxWithConstraints(Modifier.fillMaxSize().padding(focus.contentPadding)) {
        val boxW = maxWidth.value
        val wide = boxW >= XMB_CC_WIDE_STRIP_DP
        val cols = remember(boxW) { xmbCcColumns(boxW) }
        val cardH = ss.dp(66f)
        val strip = inStrip || visible.isEmpty()

        // The list of the game on show (its configs, ranked), built here so the keys can reach it.
        val unknownDevice = stringResource(R.string.xmb_cc_unknown_device)
        val cfg = open?.let { rememberGameConfigs(vm, it) }
        val rows = remember(open, cfg?.loading, cfg?.entries, userSoc, userGpu, unknownDevice) {
            if (open == null || cfg == null) emptyList<XmbCcConfigRow>() else xmbCcConfigRows(open, cfg.entries, userSoc, userGpu, unknownDevice)
        }
        val offlineRows = cfg != null && !cfg.loading && cfg.entries.isEmpty() && rows.isNotEmpty()
        LaunchedEffect(rows.size) { cfgSel = cfgSel.coerceIn(0, (rows.size - 1).coerceAtLeast(0)) }
        val listState = rememberLazyListState()
        // The offline note, when there is one, is the list's first item.
        LaunchedEffect(cfgSel, rows.size, open, offlineRows) {
            if (open != null && rows.isNotEmpty()) listState.reveal(cfgSel.coerceIn(0, rows.lastIndex) + (if (offlineRows) 1 else 0))
        }
        LaunchedEffect(sel, strip, open, visible.size, cols) {
            if (open == null && !strip && visible.isNotEmpty()) gridState.reveal(sel.coerceIn(0, visible.lastIndex))
        }

        val stripActivate: (Int) -> Unit = { i ->
            when (i) {
                XMB_CC_STRIP_SEARCH -> edit.start()
                XMB_CC_STRIP_FITS -> { if (fits != null) onlyFits = !onlyFits }
                XMB_CC_STRIP_LIBRARY -> { if (library != null) onlyMine = !onlyMine }
                XMB_CC_STRIP_SORT -> { sort = xmbCcNextSort(sort) }
                XMB_CC_STRIP_REFRESH -> refresh()
                else -> Unit
            }
        }
        val openGame: (Int) -> Unit = { i -> visible.getOrNull(i)?.let { sel = i; openId = it.identity } }
        val applyRow: (Int) -> Unit = { i -> rows.getOrNull(i)?.let { actions.applyPicker = it.pick } }
        val detailsRow: (Int) -> Unit = { i -> rows.getOrNull(i)?.let { actions.detailFor = it.pick to null } }

        XmbTabsPageKeys(focus) { e ->
            val first = e.nativeKeyEvent.repeatCount == 0
            val enter = e.key == Key.ButtonA || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter
            when {
                // Typing: B, Back or Enter ends it; every other key goes to the field (the host keeps the D-pad).
                edit.editing -> when (e.key) {
                    Key.ButtonB, Key.Escape, Key.Back, Key.Enter, Key.NumPadEnter -> { edit.stop(); true }
                    else -> false
                }
                // Loading: the host's rules (Up to the bar, B closes) are all it needs.
                !loaded -> false
                // Nothing to list: A tries again.
                games.isEmpty() -> if (enter) { if (first) refresh(); true } else false
                open != null -> when {
                    e.key == Key.DirectionUp -> { if (cfgSel > 0) cfgSel-- else focus.toBar(); true }
                    e.key == Key.DirectionDown -> { if (cfgSel < rows.lastIndex) cfgSel++; true }
                    enter -> { if (first) applyRow(cfgSel); true }
                    e.key == Key.ButtonX -> { if (first) detailsRow(cfgSel); true }
                    e.key == Key.ButtonB || e.key == Key.Escape -> { if (first) openId = null; true }
                    else -> false
                }
                else -> when {
                    e.key == Key.DirectionLeft -> {
                        if (strip) stripSel = xmbCcStripStep(stripSel, -1) else sel = xmbCcMove(sel, -1, 0, cols, visible.size)
                        true
                    }
                    e.key == Key.DirectionRight -> {
                        if (strip) stripSel = xmbCcStripStep(stripSel, 1) else sel = xmbCcMove(sel, 1, 0, cols, visible.size)
                        true
                    }
                    e.key == Key.DirectionDown -> {
                        if (strip) {
                            if (visible.isNotEmpty()) { inStrip = false; sel = sel.coerceIn(0, visible.lastIndex) }
                        } else sel = xmbCcMove(sel, 0, 1, cols, visible.size)
                        true
                    }
                    e.key == Key.DirectionUp -> {
                        if (strip) focus.toBar() else {
                            val to = xmbCcMove(sel, 0, -1, cols, visible.size)
                            if (to == XMB_CC_TO_STRIP) inStrip = true else sel = to
                        }
                        true
                    }
                    enter -> { if (first) { if (strip) stripActivate(stripSel) else openGame(sel) }; true }
                    // X types a search from anywhere on the page.
                    e.key == Key.ButtonX -> {
                        if (first) { inStrip = true; stripSel = XMB_CC_STRIP_SEARCH; edit.start() }
                        true
                    }
                    else -> false
                }
            }
        }
        BackHandler(enabled = edit.editing || open != null) { if (edit.editing) edit.stop() else openId = null }

        val inUse = focus.contentFocused
        when {
            !loaded -> XmbCcMessage(stringResource(R.string.xmb_cc_loading), "", busy = true, ss = ss)
            games.isEmpty() -> XmbCcMessage(
                title = stringResource(R.string.xmb_cc_offline_title),
                body = stringResource(R.string.xmb_cc_offline_body),
                busy = refreshing,
                ss = ss,
                button = stringResource(R.string.xmb_cc_retry),
                buttonFocused = inUse,
                onButton = refresh,
            )
            open != null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ss.dp(8f))) {
                XmbCcDetailHeader(
                    game = open,
                    counts = xmbCcCounts(resources, open.configCount, open.devices.size),
                    deviceLine = stringResource(R.string.xmb_cc_your_device, deviceHeaderLabel(cat?.deviceModel, cat?.hardwareLabel)),
                    fits = open.identity in fitSet,
                    inLibrary = open.identity in librarySet,
                    tagFits = stringResource(R.string.xmb_cc_tag_fits),
                    tagLibrary = stringResource(R.string.xmb_cc_tag_library),
                    backLabel = stringResource(R.string.xmb_cc_back),
                    height = ss.dp(52f),
                    ss = ss,
                    onBack = { openId = null },
                )
                when {
                    cfg == null || cfg.loading -> XmbCcMessage(stringResource(R.string.xmb_cc_configs_loading), "", busy = true, ss = ss)
                    rows.isEmpty() -> XmbCcMessage(stringResource(R.string.xmb_cc_configs_none), "", busy = false, ss = ss)
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(ss.dp(8f)),
                    ) {
                        if (offlineRows) {
                            item(key = "offline") {
                                Text(
                                    stringResource(R.string.xmb_cc_configs_offline),
                                    color = XMB_CC_DIM,
                                    fontSize = ss.sp(10.5f),
                                )
                            }
                        }
                        itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                            XmbCcConfigRowView(
                                row = r,
                                focused = inUse && i == cfgSel,
                                tagFits = stringResource(R.string.xmb_cc_tag_fits),
                                tagOwnRepo = stringResource(R.string.xmb_cc_tag_own_repo),
                                applyLabel = stringResource(R.string.xmb_cc_apply),
                                detailsLabel = stringResource(R.string.xmb_cc_details),
                                ss = ss,
                                onApply = { cfgSel = i; applyRow(i) },
                                onDetails = { cfgSel = i; detailsRow(i) },
                            )
                        }
                    }
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ss.dp(6f))) {
                XmbCcStrip(
                    wide = wide,
                    query = query,
                    onQuery = { query = it },
                    edit = edit,
                    cursor = if (inUse && strip) stripSel else -1,
                    onlyFits = onlyFits,
                    onlyMine = onlyMine,
                    fitsReady = fits != null,
                    libraryReady = library != null,
                    sort = sort,
                    refreshing = refreshing,
                    status = notice ?: resources.getQuantityString(R.plurals.xmb_cc_games, visible.size, visible.size),
                    ss = ss,
                    onActivate = { i -> inStrip = true; stripSel = i; stripActivate(i) },
                )
                if (visible.isEmpty()) {
                    XmbCcMessage(
                        stringResource(R.string.xmb_cc_no_results),
                        stringResource(R.string.xmb_cc_no_results_hint),
                        busy = false,
                        ss = ss,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(cols),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(XMB_CC_GAP_DP.dp),
                        verticalArrangement = Arrangement.spacedBy(XMB_CC_GAP_DP.dp),
                    ) {
                        itemsIndexed(visible, key = { _, g -> g.identity }) { i, g ->
                            XmbCcGameCard(
                                game = g,
                                counts = xmbCcCounts(resources, g.configCount, g.devices.size),
                                fits = g.identity in fitSet,
                                inLibrary = g.identity in librarySet,
                                tagFits = stringResource(R.string.xmb_cc_tag_fits),
                                tagLibrary = stringResource(R.string.xmb_cc_tag_library),
                                focused = inUse && !strip && i == sel,
                                height = cardH,
                                ss = ss,
                                onClick = { inStrip = false; openGame(i) },
                            )
                        }
                    }
                }
            }
        }
    }

    ShortcutActionDialogs(actions)
}

/** The page is at least this wide (dp) for the whole strip on one row; narrower, the chips go on a second row. */
private const val XMB_CC_WIDE_STRIP_DP = 640f

/** The strip: the search field, then the chips (fits this device, in my library, the sort, and the refresh with the count or a note). */
@Composable
private fun XmbCcStrip(
    wide: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    edit: XmbEditState,
    cursor: Int,
    onlyFits: Boolean,
    onlyMine: Boolean,
    fitsReady: Boolean,
    libraryReady: Boolean,
    sort: CatalogSort,
    refreshing: Boolean,
    status: String,
    ss: ScreenScale,
    onActivate: (Int) -> Unit,
) {
    val search: @Composable (Modifier) -> Unit = { m ->
        XmbCcSearch(
            query = query,
            hint = stringResource(R.string.xmb_cc_search_hint),
            clearLabel = stringResource(R.string.xmb_cc_clear_search),
            edit = edit,
            focused = cursor == XMB_CC_STRIP_SEARCH,
            ss = ss,
            modifier = m,
            onQueryChange = onQuery,
        )
    }
    val chips: @Composable () -> Unit = {
        XmbCcChip(
            label = stringResource(R.string.xmb_cc_filter_fits),
            on = onlyFits && fitsReady,
            focused = cursor == XMB_CC_STRIP_FITS,
            enabled = fitsReady,
            ss = ss,
            onClick = { onActivate(XMB_CC_STRIP_FITS) },
        )
        XmbCcChip(
            label = stringResource(R.string.xmb_cc_filter_library),
            on = onlyMine && libraryReady,
            focused = cursor == XMB_CC_STRIP_LIBRARY,
            enabled = libraryReady,
            ss = ss,
            onClick = { onActivate(XMB_CC_STRIP_LIBRARY) },
        )
        XmbCcChip(
            label = stringResource(R.string.xmb_cc_sort, stringResource(if (sort == CatalogSort.NAME) R.string.xmb_cc_sort_name else R.string.xmb_cc_sort_popular)),
            on = false,
            focused = cursor == XMB_CC_STRIP_SORT,
            enabled = true,
            ss = ss,
            onClick = { onActivate(XMB_CC_STRIP_SORT) },
        )
        XmbCcChip(
            label = status,
            on = false,
            focused = cursor == XMB_CC_STRIP_REFRESH,
            enabled = !refreshing,
            ss = ss,
            leading = {
                if (refreshing) XmbCcSpinner(ss.dp(14f))
                else Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.xmb_cc_refresh), tint = Color.White, modifier = Modifier.size(ss.dp(16f)))
            },
            onClick = { onActivate(XMB_CC_STRIP_REFRESH) },
        )
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ss.dp(8f))) {
            search(Modifier.weight(1f))
            chips()
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            search(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ss.dp(8f))) {
                chips()
            }
        }
    }
}

/** "12 configs · 3 devices". */
private fun xmbCcCounts(resources: Resources, configs: Int, devices: Int): String =
    resources.getString(
        R.string.xmb_cc_counts,
        resources.getQuantityString(R.plurals.xmb_cc_configs, configs, configs),
        resources.getQuantityString(R.plurals.xmb_cc_devices, devices, devices),
    )

/** [xmbCcVisible] with the sets not worked out yet read as empty (their chips are off until they are). */
private fun xmbCcVisibleOf(
    games: List<CanonicalGame>,
    query: String,
    sort: CatalogSort,
    fits: Set<String>?,
    onlyFits: Boolean,
    mine: Set<String>?,
    onlyMine: Boolean,
) = xmbCcVisible(games, query, sort, fits ?: emptySet(), onlyFits && fits != null, mine ?: emptySet(), onlyMine && mine != null)

/**
 * The rows of [game]'s page: its uploaded [entries] (each with the worker folder it came from), those that fit this device first, or, when
 * the worker has none (offline), the game's devices from the index, the ones that fit first (no votes, the best file is picked when
 * applied).
 */
private fun xmbCcConfigRows(
    game: CanonicalGame,
    entries: List<Pair<String, WorkerConfigEntry>>,
    userSoc: String?,
    userGpu: String?,
    unknownDevice: String,
): List<XmbCcConfigRow> {
    val hw = userSoc != null || userGpu != null
    if (entries.isNotEmpty()) {
        val fitsDevice = { e: WorkerConfigEntry ->
            hw && GameMatcher.hardwareMatchesUser(userSoc, userGpu, listOf(e.device, e.soc))
        }
        return xmbCcRankConfigs(entries, fitsDevice).map { (folder, e) ->
            XmbCcConfigRow(
                pick = communityFilePick(game, folder, e),
                key = "f:${e.sha.ifBlank { "$folder/${e.filename}" }}",
                title = e.device.ifBlank { e.soc.ifBlank { e.filename } },
                sub = listOf(e.soc, e.date).filter { it.isNotBlank() }.joinToString(" · "),
                votes = e.votes,
                downloads = e.downloads,
                fits = fitsDevice(e),
                ownRepo = e.appSource == "bannerlator",
            )
        }
    }
    return GameMatcher.rankDevices(game.devices, userSoc, userGpu).mapIndexed { i, d ->
        XmbCcConfigRow(
            pick = CommunityPick.Device(game, d),
            key = "d:$i:${d.label}",
            title = d.model.ifBlank { unknownDevice },
            sub = listOf(d.gpu, d.soc).filter { it.isNotBlank() }.joinToString(" · "),
            votes = null,
            downloads = null,
            fits = hw && GameMatcher.deviceMatchesUser(d, userSoc, userGpu),
            ownRepo = false,
        )
    }
}

/** Scrolls the grid just far enough to bring card [index] fully into view (a card far off is brought to the top). */
private suspend fun LazyGridState.reveal(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val top = item.offset.y
    val bottom = top + item.size.height
    val limit = info.viewportSize.height - info.afterContentPadding
    if (top < info.beforeContentPadding) animateScrollBy((top - info.beforeContentPadding).toFloat())
    else if (bottom > limit) animateScrollBy((bottom - limit).toFloat())
}

/** The same for a column of rows. */
private suspend fun LazyListState.reveal(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index)
        return
    }
    val top = item.offset
    val bottom = top + item.size
    val limit = info.viewportSize.height - info.afterContentPadding
    if (top < info.beforeContentPadding) animateScrollBy((top - info.beforeContentPadding).toFloat())
    else if (bottom > limit) animateScrollBy((bottom - limit).toFloat())
}
