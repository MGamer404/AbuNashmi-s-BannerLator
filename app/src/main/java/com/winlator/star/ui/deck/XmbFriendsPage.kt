package com.winlator.star.ui.deck

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.winlator.star.R
import com.winlator.star.store.FriendAvatar
import com.winlator.star.store.SteamFriendsStore
import com.winlator.star.store.SteamMainActivity
import com.winlator.star.store.SteamPrefs
import com.winlator.star.store.SteamForegroundService
import com.winlator.star.store.SteamSocialSettingsSheet
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.store.SteamTab
import com.winlator.star.store.UnreadBadge
import com.winlator.star.store.dotColor
import com.winlator.star.store.friendsOnlineCount
import com.winlator.star.store.initSteamFriends
import com.winlator.star.store.openSteamGameDetail
import com.winlator.star.store.openSteamProfileInBrowser
import com.winlator.star.store.rememberFriendsAvailable
import com.winlator.star.store.rememberSteamLoggedIn
import com.winlator.star.store.rememberSteamOnlineFriends
import com.winlator.star.ui.screens.XMB_FRIENDS_TO_BAR
import com.winlator.star.ui.screens.XmbFriendAction
import com.winlator.star.ui.screens.XmbFriendsRow
import com.winlator.star.ui.screens.xmbClick
import com.winlator.star.ui.screens.xmbFriendActions
import com.winlator.star.ui.screens.xmbFriendsButtonCount
import com.winlator.star.ui.screens.xmbFriendsEmpty
import com.winlator.star.ui.screens.xmbFriendsMove
import com.winlator.star.ui.screens.xmbFriendsResolve
import com.winlator.star.ui.screens.xmbFriendsRows

// The Friends tab of the XMB Home's tabs page: the friends window of the old Steam Friends screen, rebuilt as a page. The roster in sections
// (In game, Online, Away, Offline, with the friend requests on top), each friend a card with their avatar, status, the game they are playing
// and the unread count. D-pad: Up / Down along the rows, Left / Right between the buttons of the row, A opens a friend's actions (Message,
// Game page, Profile, Remove) or folds a section away, B closes the actions, X adds a friend. A tap does the same as A. Chat, profile and
// Add friend open as windows over it (XmbFriendsDialogs.kt). Signed out, it shows a Sign in to Steam card instead.
//
// The rules (the rows, the cursor's moves, a friend's actions) are in screens/XmbFriendsLogic.kt; the logic shared with SteamFriendsActivity
// (signed in, friends available, the online count) is in store/SteamFriendsShared.kt.

/** The widest the list gets (dp): a column in the middle on a wide screen, as the Steam Deck's friends page. */
private val XMB_FR_MAX_WIDTH = 720.dp

/** The online-friends count for the pill bar's Friends badge (XmbTabsPageHost's badges): 0 while signed out or the friends feature is off. */
@Composable
internal fun rememberXmbFriendsBadge(): Int = rememberSteamOnlineFriends()

/** What a state that shows no roster says: a card with a title, a line and one button (A or a tap does it). */
private class XmbFriendsGate(val title: Int, val sub: Int, val button: Int, val onClick: () -> Unit)

/**
 * The Friends page of the XMB Home's tabs page. [focus] is what the host gives a page (see [XmbTabsPageFocus]): where the cursor is, the room to
 * keep clear, and the key hook for the D-pad.
 */
@Composable
internal fun XmbFriendsPage(focus: XmbTabsPageFocus) {
    val ctx = LocalContext.current
    remember(ctx) { initSteamFriends(ctx) }
    val socialEnabled by SteamFriendsStore.socialEnabled.collectAsState()
    val available = rememberFriendsAvailable()
    val signedInEvents = rememberSteamLoggedIn()
    // The sign-in screens are other activities: the state is read again whenever the app comes back.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                SteamFriendsStore.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val signedIn = remember(signedInEvents, resumeTick) { runCatching { SteamPrefs.isLoggedIn }.getOrDefault(false) }

    // A fresh roster as soon as the session is live and the feature on.
    LaunchedEffect(signedIn, available, socialEnabled) {
        if (signedIn && available && socialEnabled) SteamFriendsStore.refresh()
    }

    val gate = when {
        !signedIn -> XmbFriendsGate(R.string.xmb_fr_signin_title, R.string.xmb_fr_signin_sub, R.string.xmb_fr_signin_button) {
            // The store's own screen on its Friends tab: its sign-in first while signed out (as the Stores tab's Steam card).
            runCatching {
                ctx.startActivity(Intent(ctx, SteamMainActivity::class.java).putExtra(SteamMainActivity.EXTRA_TAB, SteamTab.FRIENDS.name))
            }
        }
        !socialEnabled -> XmbFriendsGate(R.string.xmb_fr_off_title, R.string.xmb_fr_off_sub, R.string.xmb_fr_off_button) {
            SteamFriendsStore.setSocialEnabled(ctx, true)
        }
        !available -> XmbFriendsGate(R.string.xmb_fr_disconnected_title, R.string.xmb_fr_disconnected_sub, R.string.xmb_fr_connect) {
            // Brings the Steam session up (initialises it and connects, as the store screens do on opening): the roster follows by itself.
            runCatching { SteamForegroundService.start(ctx) }
        }
        else -> null
    }
    if (gate != null) XmbFriendsGateCard(focus, gate) else XmbFriendsRoster(focus)
}

// ---------------------------------------------------------------------------------------------------------------------------------
// The states without a roster
// ---------------------------------------------------------------------------------------------------------------------------------

@Composable
private fun XmbFriendsGateCard(focus: XmbTabsPageFocus, gate: XmbFriendsGate) {
    val cs = MaterialTheme.colorScheme
    val ss = rememberScreenScale()
    XmbTabsPageKeys(focus) { e ->
        when (e.key) {
            Key.DirectionUp -> { focus.toBar(); true }
            // A held key must not run it twice.
            Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                if (e.nativeKeyEvent.repeatCount == 0) gate.onClick()
                true
            }
            else -> false
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxSize().padding(focus.contentPadding), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(shape)
                .background(deckCardFill())
                .border(1.dp, deckLine(), shape)
                .padding(horizontal = ss.dp(24f), vertical = ss.dp(22f)),
        ) {
            Icon(Icons.Filled.People, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(ss.dp(40f)))
            Spacer(Modifier.height(ss.dp(10f)))
            Text(
                stringResource(gate.title), color = cs.onSurface, fontSize = ss.sp(18f), fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(ss.dp(6f)))
            Text(stringResource(gate.sub), color = cs.onSurfaceVariant, fontSize = ss.sp(13f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(ss.dp(18f)))
            XmbFriendsButton(stringResource(gate.button), focus.contentFocused, ss, gate.onClick)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------------
// The roster
// ---------------------------------------------------------------------------------------------------------------------------------

@Composable
private fun XmbFriendsRoster(focus: XmbTabsPageFocus) {
    val ctx = LocalContext.current
    val ss = rememberScreenScale()
    val friends by SteamFriendsStore.friends.collectAsState()
    val incoming by SteamFriendsStore.incomingRequests.collectAsState()
    val outgoing by SteamFriendsStore.outgoingRequests.collectAsState()
    val collapsed by SteamFriendsStore.collapsedSections.collectAsState()
    val unread by SteamFriendsStore.unread.collectAsState()
    val rows = remember(friends, incoming, outgoing, collapsed) { xmbFriendsRows(friends, incoming, outgoing, collapsed) }
    val online = remember(friends) { friendsOnlineCount(friends) }

    // The store's answer to adding a friend (request sent, errors) as a toast, whichever window the request came from.
    val addFeedback by SteamFriendsStore.addFeedback.collectAsState()
    LaunchedEffect(addFeedback) {
        addFeedback?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            SteamFriendsStore.clearAddFeedback()
        }
    }

    // The cursor: the row it is on (by key, so it stays on its friend while the list changes under it), the button of the row it is on, and
    // the friend whose actions are open (with Remove waiting for its second press).
    var selKey by remember { mutableStateOf(XmbFriendsRow.Toolbar.key) }
    var sub by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val lastIndex = remember { IntArray(1) }
    val selIdx = xmbFriendsResolve(rows, selKey, lastIndex[0])
    SideEffect { lastIndex[0] = selIdx }
    val selRow = rows[selIdx]

    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var chatFriend by remember { mutableStateOf<SteamFriendsStore.SteamFriend?>(null) }
    var profileFriend by remember { mutableStateOf<SteamFriendsStore.SteamFriend?>(null) }
    val listState = rememberLazyListState()

    fun select(index: Int) {
        val row = rows[index.coerceIn(0, rows.lastIndex)]
        if (row.key == selRow.key) return
        selKey = row.key
        sub = 0
        openId = null
        confirmRemove = false
    }

    // What A (or a tap) does on [row]'s [button].
    fun act(row: XmbFriendsRow, button: Int) {
        when (row) {
            is XmbFriendsRow.Toolbar -> if (button == 0) showAdd = true else showSettings = true
            is XmbFriendsRow.Header -> SteamFriendsStore.setSectionCollapsed(row.presence, !row.collapsed)
            is XmbFriendsRow.Incoming -> if (button == 0) SteamFriendsStore.acceptRequest(row.friend.steamId) else SteamFriendsStore.declineRequest(row.friend.steamId)
            is XmbFriendsRow.Outgoing -> SteamFriendsStore.cancelRequest(row.friend.steamId)
            is XmbFriendsRow.RequestsHeader -> {}
            is XmbFriendsRow.Friend -> {
                val f = row.friend
                if (openId != f.steamId) {
                    openId = f.steamId
                    sub = 0
                    confirmRemove = false
                } else {
                    val actions = xmbFriendActions(f)
                    when (actions[button.coerceIn(0, actions.lastIndex)]) {
                        XmbFriendAction.MESSAGE -> { openId = null; chatFriend = f }
                        XmbFriendAction.GAME -> openSteamGameDetail(ctx, f.gameAppId)
                        XmbFriendAction.PROFILE -> profileFriend = f
                        XmbFriendAction.REMOVE -> if (!confirmRemove) {
                            confirmRemove = true
                        } else {
                            openId = null
                            confirmRemove = false
                            SteamFriendsStore.removeFriend(f.steamId)
                        }
                    }
                }
            }
        }
    }

    XmbTabsPageKeys(focus) { e ->
        val first = e.nativeKeyEvent.repeatCount == 0
        when (e.key) {
            Key.DirectionUp, Key.DirectionDown -> {
                val to = xmbFriendsMove(rows, selIdx, if (e.key == Key.DirectionUp) -1 else 1)
                if (to == XMB_FRIENDS_TO_BAR) focus.toBar() else if (to != selIdx) select(to)
                true
            }
            Key.DirectionLeft, Key.DirectionRight -> {
                val count = xmbFriendsButtonCount(selRow, openId)
                val next = (sub + if (e.key == Key.DirectionRight) 1 else -1).coerceIn(0, count - 1)
                if (next != sub) {
                    sub = next
                    confirmRemove = false
                }
                true
            }
            Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                if (first) act(selRow, sub)
                true
            }
            // B steps back from an open row of actions; with none open it is the host's (it closes the page).
            Key.ButtonB, Key.Escape -> {
                if (openId != null) {
                    openId = null
                    confirmRemove = false
                    sub = 0
                    true
                } else {
                    false
                }
            }
            Key.ButtonX -> {
                if (first) showAdd = true
                true
            }
            else -> false
        }
    }

    // The row under the cursor is kept in view: on a move, when its actions open (the row grows) and when the cursor comes into the list.
    LaunchedEffect(selIdx, openId, focus.contentFocused) {
        if (focus.contentFocused) listState.bringRowIntoView(selIdx)
    }

    val selectedKey = if (focus.contentFocused) selRow.key else null
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = focus.contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ss.dp(6f)),
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            val on = row.key == selectedKey
            when (row) {
                is XmbFriendsRow.Toolbar -> XmbFriendsToolbar(
                    summary = stringResource(R.string.xmb_fr_summary, online, friends.size),
                    sub = if (on) sub else -1,
                    ss = ss,
                    onAdd = { select(index); act(row, 0) },
                    onSettings = { select(index); act(row, 1) },
                )
                is XmbFriendsRow.RequestsHeader -> XmbFriendsHeading(
                    stringResource(R.string.xmb_fr_section_requests).uppercase() + " · " + row.count, null, false, false, ss, {},
                )
                is XmbFriendsRow.Header -> XmbFriendsHeading(
                    stringResource(sectionLabel(row.presence)).uppercase() + " · " + row.count,
                    dotColor(row.presence), row.collapsed, on, ss,
                ) { select(index); act(row, 0) }
                is XmbFriendsRow.Incoming -> XmbFriendsRequestCard(
                    friend = row.friend,
                    status = stringResource(R.string.xmb_fr_wants_to_add),
                    buttons = listOf(stringResource(R.string.xmb_fr_accept), stringResource(R.string.xmb_fr_decline)),
                    sub = if (on) sub else -1,
                    ss = ss,
                ) { button -> select(index); act(row, button) }
                is XmbFriendsRow.Outgoing -> XmbFriendsRequestCard(
                    friend = row.friend,
                    status = stringResource(R.string.xmb_fr_request_sent),
                    buttons = listOf(stringResource(R.string.xmb_fr_cancel)),
                    sub = if (on) sub else -1,
                    ss = ss,
                ) { button -> select(index); act(row, button) }
                is XmbFriendsRow.Friend -> {
                    val f = row.friend
                    val open = openId == f.steamId
                    XmbFriendCard(
                        friend = f,
                        unread = unread[f.steamId] ?: 0,
                        selected = on,
                        open = open,
                        sub = sub,
                        confirmRemove = confirmRemove,
                        ss = ss,
                        onTap = {
                            // A tap opens or closes the actions, as A does on a closed card.
                            select(index)
                            openId = if (openId == f.steamId) null else f.steamId
                            sub = 0
                            confirmRemove = false
                        },
                        onAction = { button -> select(index); sub = button; act(row, button) },
                    )
                }
            }
        }
        if (xmbFriendsEmpty(rows)) {
            item(key = "empty") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.xmbFriendsRow().padding(vertical = ss.dp(36f)),
                ) {
                    val cs = MaterialTheme.colorScheme
                    Text(
                        stringResource(R.string.xmb_fr_empty_title), color = cs.onSurface, fontSize = ss.sp(15f), fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.xmb_fr_empty_sub), color = cs.onSurfaceVariant, fontSize = ss.sp(13f),
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = ss.dp(4f)),
                    )
                }
            }
        }
    }

    if (showAdd) XmbAddFriendDialog(onDismiss = { showAdd = false })
    if (showSettings) SteamSocialSettingsSheet(onDismiss = { showSettings = false })
    chatFriend?.let { f ->
        XmbFriendChatDialog(friends.firstOrNull { it.steamId == f.steamId } ?: f, onDismiss = { chatFriend = null })
    }
    profileFriend?.let { f ->
        XmbFriendProfileDialog(
            friend = friends.firstOrNull { it.steamId == f.steamId } ?: f,
            onMessage = { chatFriend = f; profileFriend = null },
            onViewOnSteam = { openSteamProfileInBrowser(ctx, f.steamId) },
            onDismiss = { profileFriend = null },
        )
    }
}

private fun sectionLabel(presence: SteamFriendsStore.Presence): Int = when (presence) {
    SteamFriendsStore.Presence.IN_GAME -> R.string.xmb_fr_section_in_game
    SteamFriendsStore.Presence.ONLINE -> R.string.xmb_fr_section_online
    SteamFriendsStore.Presence.AWAY -> R.string.xmb_fr_section_away
    SteamFriendsStore.Presence.OFFLINE -> R.string.xmb_fr_section_offline
    SteamFriendsStore.Presence.UNKNOWN -> R.string.xmb_fr_section_unknown
}

/** Scrolls the list the least that brings row [index] fully into the area the page's padding leaves free (a row taller than it: to its top). */
private suspend fun LazyListState.bringRowIntoView(index: Int) {
    // After the layout that follows the change (an opened row is taller).
    withFrameNanos { }
    var tries = 0
    while (tries++ < 3) {
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            animateScrollToItem(index)
            continue
        }
        val top = info.viewportStartOffset + info.beforeContentPadding
        val bottom = info.viewportEndOffset - info.afterContentPadding
        val delta = when {
            item.size >= bottom - top || item.offset < top -> item.offset - top
            item.offset + item.size > bottom -> item.offset + item.size - bottom
            else -> 0
        }
        if (delta != 0) animateScrollBy(delta.toFloat())
        return
    }
}

private fun Modifier.xmbFriendsRow(): Modifier = this.widthIn(max = XMB_FR_MAX_WIDTH).fillMaxWidth()

// ---------------------------------------------------------------------------------------------------------------------------------
// The rows
// ---------------------------------------------------------------------------------------------------------------------------------

/** The thin outline of the card or button under the cursor. */
@Composable
private fun xmbFriendsOutline(): androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)

/**
 * A pill button: the one under the cursor is filled with the accent and ringed. [showX] adds the X glyph (the controller's shortcut for it).
 * At least 48 dp tall.
 */
@Composable
private fun XmbFriendsButton(
    label: String,
    focused: Boolean,
    ss: ScreenScale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    showX: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val src = remember { MutableInteractionSource() }
    val ink = if (focused) cs.onPrimary else cs.onSurface
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(if (focused) cs.primary else cs.onSurface.copy(alpha = 0.12f))
            .then(if (focused) Modifier.border(1.5.dp, xmbFriendsOutline(), shape) else Modifier)
            .xmbClick(src, onClick)
            .padding(horizontal = ss.dp(16f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ss.dp(6f), Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(ss.dp(18f)))
        Text(label, color = ink, fontSize = ss.sp(12.5f), fontWeight = FontWeight.Bold, maxLines = 1)
        if (showX) DeckGlyph("X", GlyphKind.X)
    }
}

/** The top row: how many friends are online of how many, and the Add friend / Settings buttons. [sub] is the button the cursor is on, -1 when it is not on this row. */
@Composable
private fun XmbFriendsToolbar(summary: String, sub: Int, ss: ScreenScale, onAdd: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.xmbFriendsRow().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = ss.sp(12.5f), fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        XmbFriendsButton(stringResource(R.string.xmb_fr_add), sub == 0, ss, onAdd, icon = Icons.Filled.Add, showX = true)
        Spacer(Modifier.width(ss.dp(8f)))
        XmbFriendsButton(stringResource(R.string.xmb_fr_settings), sub == 1, ss, onSettings, icon = Icons.Filled.Settings)
    }
}

/**
 * A heading: its text in small capitals and, for a section, the colour of its presence dot and a chevron that shows whether it is folded
 * (a tap or A folds it). The heading of the friend requests is not selectable: it only labels them.
 */
@Composable
private fun XmbFriendsHeading(
    text: String,
    dot: androidx.compose.ui.graphics.Color?,
    collapsed: Boolean,
    selected: Boolean,
    ss: ScreenScale,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val src = remember { MutableInteractionSource() }
    val tappable = dot != null
    Row(
        Modifier
            .xmbFriendsRow()
            .defaultMinSize(minHeight = if (tappable) 48.dp else 32.dp)
            .clip(shape)
            .then(if (selected) Modifier.border(1.5.dp, xmbFriendsOutline(), shape) else Modifier)
            .then(if (tappable) Modifier.xmbClick(src, onClick) else Modifier)
            .padding(horizontal = ss.dp(10f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(ss.dp(9f)).clip(CircleShape).background(dot))
            Spacer(Modifier.width(ss.dp(8f)))
        }
        Text(
            text, color = cs.onSurfaceVariant, fontSize = ss.sp(11.5f), letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (tappable) {
            Icon(
                Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.onSurfaceVariant,
                modifier = Modifier.size(ss.dp(20f)).rotate(if (collapsed) -90f else 0f),
            )
        }
    }
}

/** A friend: the card (avatar, name, status, the game they are playing as small art, the unread count) and, while [open], their actions under it. */
@Composable
private fun XmbFriendCard(
    friend: SteamFriendsStore.SteamFriend,
    unread: Int,
    selected: Boolean,
    open: Boolean,
    sub: Int,
    confirmRemove: Boolean,
    ss: ScreenScale,
    onTap: () -> Unit,
    onAction: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val src = remember { MutableInteractionSource() }
    val inGame = friend.presence == SteamFriendsStore.Presence.IN_GAME
    // With the actions open the buttons carry the cursor and the card keeps a faint ring.
    val ring = when {
        selected && !open -> Modifier.border(1.5.dp, xmbFriendsOutline(), shape)
        open -> Modifier.border(1.dp, cs.onSurface.copy(alpha = 0.35f), shape)
        else -> Modifier.border(1.dp, deckLine(), shape)
    }
    Column(Modifier.xmbFriendsRow(), verticalArrangement = Arrangement.spacedBy(ss.dp(6f))) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clip(shape)
                .background(deckCardFill())
                .then(ring)
                .xmbClick(src, onTap)
                .padding(horizontal = ss.dp(12f), vertical = ss.dp(8f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FriendAvatar(friend = friend, size = ss.dp(44f))
            Spacer(Modifier.width(ss.dp(12f)))
            Column(Modifier.weight(1f)) {
                Text(
                    friend.displayName, color = cs.onSurface, fontSize = ss.sp(15f),
                    fontWeight = if (unread > 0) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    friend.statusText,
                    color = if (inGame) dotColor(friend.presence) else cs.onSurfaceVariant,
                    fontSize = ss.sp(12.5f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (inGame && friend.gameAppId != 0) {
                Spacer(Modifier.width(ss.dp(10f)))
                AsyncImage(
                    model = SteamStoreSearch.headerUrl(friend.gameAppId),
                    contentDescription = friend.gameName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = ss.dp(76f), height = ss.dp(36f)).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant),
                )
            }
            if (unread > 0) {
                Spacer(Modifier.width(ss.dp(10f)))
                UnreadBadge(unread)
            }
        }
        if (open) {
            val labels = xmbFriendActions(friend).map { action ->
                when (action) {
                    XmbFriendAction.MESSAGE -> stringResource(R.string.xmb_fr_message)
                    XmbFriendAction.GAME -> stringResource(R.string.xmb_fr_game_page)
                    XmbFriendAction.PROFILE -> stringResource(R.string.xmb_fr_profile)
                    XmbFriendAction.REMOVE ->
                        if (confirmRemove) stringResource(R.string.xmb_fr_remove_confirm) else stringResource(R.string.xmb_fr_remove)
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = ss.dp(8f)), horizontalArrangement = Arrangement.spacedBy(ss.dp(8f))) {
                labels.forEachIndexed { i, label ->
                    XmbFriendsButton(label, selected && sub == i, ss, { onAction(i) })
                }
            }
        }
    }
}

/** A friend request, in or out: the card with its [buttons] (Accept / Decline, or Cancel); [sub] is the one the cursor is on, -1 when it is elsewhere. */
@Composable
private fun XmbFriendsRequestCard(
    friend: SteamFriendsStore.SteamFriend,
    status: String,
    buttons: List<String>,
    sub: Int,
    ss: ScreenScale,
    onButton: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .xmbFriendsRow()
            .defaultMinSize(minHeight = 56.dp)
            .clip(shape)
            .background(deckCardFill())
            .border(1.dp, deckLine(), shape)
            .padding(horizontal = ss.dp(12f), vertical = ss.dp(8f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FriendAvatar(friend = friend, size = ss.dp(44f))
        Spacer(Modifier.width(ss.dp(12f)))
        Column(Modifier.weight(1f)) {
            Text(
                friend.displayName, color = cs.onSurface, fontSize = ss.sp(15f), fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(status, color = cs.onSurfaceVariant, fontSize = ss.sp(12.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        buttons.forEachIndexed { i, label ->
            Spacer(Modifier.width(ss.dp(8f)))
            XmbFriendsButton(label, sub == i, ss, { onButton(i) })
        }
    }
}
