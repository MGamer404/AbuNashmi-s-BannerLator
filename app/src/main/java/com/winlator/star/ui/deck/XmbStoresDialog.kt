package com.winlator.star.ui.deck

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.R
import com.winlator.star.container.Shortcut
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled

/**
 * The XMB home's Stores tab: a small picker of the five stores, each with whether it is signed in and how many of its games the
 * library has, and Add game. A store opens its own screen (its sign-in while signed out). B closes it.
 */
@Composable
internal fun XmbStoresDialog(
    signedIn: Map<DeckStore, Boolean>,
    games: List<Shortcut>,
    onStore: (DeckStore) -> Unit,
    onAddGame: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val resources = LocalContext.current.resources
    val owned = remember(games) { DeckStore.entries.associateWith { store -> games.count(store.owns) } }
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DeckDialogBars()
        DeckDialogScaled {
            val shape = deckShape(26.dp)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(16.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .xmbPanel(shape, cs.surfaceContainerHigh)
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
            ) {
                Text(stringResource(R.string.xmb_tab_stores), color = cs.onSurface, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                DeckStore.entries.forEachIndexed { i, store ->
                    val count = owned[store] ?: 0
                    val gamesText = if (count == 0) resources.getString(R.string.xmb_store_no_games) else resources.getQuantityString(R.plurals.xmb_store_games, count, count)
                    val details = if (signedIn[store] == true) resources.getString(R.string.xmb_store_signed_in, gamesText) else resources.getString(R.string.xmb_store_signed_out)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DeckButton(
                            store.label, Icons.Filled.Storefront, { onStore(store) },
                            modifier = (if (i == 0) Modifier.focusRequester(firstRequester) else Modifier).width(190.dp),
                        )
                        Text(details, color = cs.onSurfaceVariant, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                    DeckButton(stringResource(R.string.xmb_stores_add_game), Icons.Filled.Add, onAddGame)
                    Spacer(Modifier.weight(1f))
                    DeckButton(stringResource(R.string.xmb_stores_close), null, onDismiss, primary = true, glyph = "B")
                }
            }
        }
    }
}
