package com.winlator.star.ui.screens

import com.winlator.star.store.SteamFriendsStore
import com.winlator.star.store.SteamFriendsStore.Presence
import com.winlator.star.store.friendsOnlineCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMB Home's Friends tab: the sections and rows of the roster, the cursor's moves in them, a friend's actions and the online count. */
class XmbFriendsLogicTest {

    private fun friend(id: Long, name: String, presence: Presence, game: Int = 0, nick: String? = null) =
        SteamFriendsStore.SteamFriend(id, name, nick, presence, "status", game, if (game != 0) "Game" else null, null)

    private val none = emptyList<SteamFriendsStore.SteamFriend>()

    private fun keys(rows: List<XmbFriendsRow>) = rows.map { it.key }

    @Test fun rows_sectionsInOrder_eachWithItsFriendsByName() {
        val rows = xmbFriendsRows(
            friends = listOf(
                friend(1, "zed", Presence.OFFLINE),
                friend(2, "Bob", Presence.ONLINE),
                friend(3, "amy", Presence.ONLINE),
                friend(4, "Cat", Presence.IN_GAME, game = 10),
                friend(5, "dan", Presence.AWAY),
            ),
            incoming = none, outgoing = none, collapsed = emptySet(),
        )
        assertEquals(
            listOf(
                "toolbar",
                "hdr:IN_GAME", "f:4",
                "hdr:ONLINE", "f:3", "f:2",
                "hdr:AWAY", "f:5",
                "hdr:OFFLINE", "f:1",
            ),
            keys(rows),
        )
    }

    @Test fun rows_headerCarriesTheCountAndFoldState() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(1, "a", Presence.OFFLINE), friend(2, "b", Presence.OFFLINE)),
            incoming = none, outgoing = none, collapsed = setOf(Presence.OFFLINE),
        )
        // A folded section keeps its heading and count but lists no friends.
        assertEquals(listOf("toolbar", "hdr:OFFLINE"), keys(rows))
        val header = rows[1] as XmbFriendsRow.Header
        assertEquals(2, header.count)
        assertTrue(header.collapsed)
    }

    @Test fun rows_emptySectionsAreLeftOut_unknownComesLast() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(1, "a", Presence.UNKNOWN), friend(2, "b", Presence.ONLINE)),
            incoming = none, outgoing = none, collapsed = emptySet(),
        )
        assertEquals(listOf("toolbar", "hdr:ONLINE", "f:2", "hdr:UNKNOWN", "f:1"), keys(rows))
    }

    @Test fun rows_requestsSitOnTopUnderTheirHeading_incomingBeforeOutgoing() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(9, "x", Presence.ONLINE)),
            incoming = listOf(friend(2, "Zoe", Presence.OFFLINE), friend(1, "al", Presence.OFFLINE)),
            outgoing = listOf(friend(3, "Meg", Presence.OFFLINE)),
            collapsed = emptySet(),
        )
        assertEquals(listOf("toolbar", "requests", "in:1", "in:2", "out:3", "hdr:ONLINE", "f:9"), keys(rows))
        assertEquals(3, (rows[1] as XmbFriendsRow.RequestsHeader).count)
    }

    @Test fun rows_aNicknameSortsAsTheNameShown() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(1, "Alpha", Presence.ONLINE, nick = "zzz"), friend(2, "Beta", Presence.ONLINE)),
            incoming = none, outgoing = none, collapsed = emptySet(),
        )
        assertEquals(listOf("toolbar", "hdr:ONLINE", "f:2", "f:1"), keys(rows))
    }

    @Test fun empty_onlyTheToolbarIsEmpty() {
        assertTrue(xmbFriendsEmpty(xmbFriendsRows(none, none, none, emptySet())))
        assertFalse(xmbFriendsEmpty(xmbFriendsRows(none, listOf(friend(1, "a", Presence.OFFLINE)), none, emptySet())))
        // Even a folded roster is not empty: its headings are there.
        assertFalse(xmbFriendsEmpty(xmbFriendsRows(listOf(friend(1, "a", Presence.OFFLINE)), none, none, setOf(Presence.OFFLINE))))
    }

    @Test fun resolve_findsTheKey_elseTheNearestSelectableAfterThenBefore() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(1, "a", Presence.ONLINE), friend(2, "b", Presence.ONLINE), friend(3, "c", Presence.OFFLINE)),
            incoming = none, outgoing = none, collapsed = emptySet(),
        )
        // toolbar, hdr:ONLINE, f:1, f:2, hdr:OFFLINE, f:3
        assertEquals(3, xmbFriendsResolve(rows, "f:2", 0))
        assertEquals(4, xmbFriendsResolve(rows, "f:gone", 4))
        assertEquals(5, xmbFriendsResolve(rows, "f:gone", 99))
        assertEquals(0, xmbFriendsResolve(rows, "f:gone", -3))
        assertEquals(0, xmbFriendsResolve(listOf(XmbFriendsRow.Toolbar), "x", 5))
    }

    @Test fun move_skipsTheRequestsHeading_upFromTheTopGoesToTheBar() {
        val rows = xmbFriendsRows(
            friends = listOf(friend(9, "x", Presence.ONLINE)),
            incoming = listOf(friend(1, "a", Presence.OFFLINE)),
            outgoing = none, collapsed = emptySet(),
        )
        // toolbar(0), requests(1, not selectable), in:1(2), hdr:ONLINE(3), f:9(4)
        assertEquals(2, xmbFriendsMove(rows, 0, 1))
        assertEquals(0, xmbFriendsMove(rows, 2, -1))
        assertEquals(XMB_FRIENDS_TO_BAR, xmbFriendsMove(rows, 0, -1))
        assertEquals(4, xmbFriendsMove(rows, 4, 1))
        assertEquals(4, xmbFriendsMove(rows, 0, 9))
    }

    @Test fun move_aStrayIndexIsSafe() {
        val rows = xmbFriendsRows(none, none, none, emptySet())
        assertEquals(0, xmbFriendsMove(rows, 7, 1))
        assertEquals(XMB_FRIENDS_TO_BAR, xmbFriendsMove(rows, 7, -1))
        assertEquals(0, xmbFriendsMove(emptyList(), 0, 1))
    }

    @Test fun actions_gameOnlyWhileInAGame_removeIsLast() {
        assertEquals(
            listOf(XmbFriendAction.MESSAGE, XmbFriendAction.GAME, XmbFriendAction.PROFILE, XmbFriendAction.REMOVE),
            xmbFriendActions(friend(1, "a", Presence.IN_GAME, game = 440)),
        )
        assertEquals(
            listOf(XmbFriendAction.MESSAGE, XmbFriendAction.PROFILE, XmbFriendAction.REMOVE),
            xmbFriendActions(friend(1, "a", Presence.ONLINE)),
        )
        // In a game with no app id (a non-Steam game): nothing to open.
        assertEquals(
            listOf(XmbFriendAction.MESSAGE, XmbFriendAction.PROFILE, XmbFriendAction.REMOVE),
            xmbFriendActions(friend(1, "a", Presence.IN_GAME, game = 0)),
        )
    }

    @Test fun buttons_perRow() {
        val f = friend(5, "a", Presence.IN_GAME, game = 1)
        assertEquals(XMB_FRIENDS_TOOLBAR_BUTTONS, xmbFriendsButtonCount(XmbFriendsRow.Toolbar, null))
        assertEquals(2, xmbFriendsButtonCount(XmbFriendsRow.Incoming(f), null))
        assertEquals(1, xmbFriendsButtonCount(XmbFriendsRow.Outgoing(f), null))
        assertEquals(1, xmbFriendsButtonCount(XmbFriendsRow.Friend(f), null))
        assertEquals(1, xmbFriendsButtonCount(XmbFriendsRow.Friend(f), 6L))
        assertEquals(4, xmbFriendsButtonCount(XmbFriendsRow.Friend(f), 5L))
        assertEquals(1, xmbFriendsButtonCount(XmbFriendsRow.Header(Presence.ONLINE, 3, false), null))
    }

    @Test fun online_countsReachableFriends_notOfflineOrUnknown() {
        val friends = listOf(
            friend(1, "a", Presence.IN_GAME, game = 1),
            friend(2, "b", Presence.ONLINE),
            friend(3, "c", Presence.AWAY),
            friend(4, "d", Presence.OFFLINE),
            friend(5, "e", Presence.UNKNOWN),
        )
        assertEquals(3, friendsOnlineCount(friends))
        assertEquals(0, friendsOnlineCount(emptyList()))
    }
}
