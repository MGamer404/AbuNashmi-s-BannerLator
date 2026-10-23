package com.winlator.star.ui

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.winlator.star.communityconfigs.AccountManager

/**
 * PHASE 3 (optional accounts) — the one place every avatar renders. Loads [avatarUrl] via Coil (the same
 * image library cover art uses) circular-cropped, and falls back to the person icon when null/blank — so a
 * logged-out user or an anonymous uploader keeps the placeholder unchanged. Reused by the My-account sheet,
 * the config-detail uploader line, the ☰-swap, and the nav-drawer header.
 */
@Composable
fun AccountAvatar(
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    fallbackTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (avatarUrl.isNullOrBlank()) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = fallbackTint,
            modifier = modifier.size(size),
        )
    } else {
        val personPainter = rememberVectorPainter(Icons.Filled.Person)
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // While loading, and on a broken/slow avatar, show the person placeholder — never an empty box.
            placeholder = personPainter,
            error = personPainter,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    }
}

/**
 * PHASE 3 (optional accounts) — a tiny app-wide bus for the OPTIONAL account UI. [account] mirrors the
 * signed-in [AccountManager] state as Compose state so the ☰→avatar swap and the drawer header recompose
 * the instant login / logout / avatar-change happens anywhere; callers invoke [refresh] after any
 * AccountManager mutation. [openMyAccountRequested] is a one-shot signal the nav-drawer sets and the
 * Shortcuts screen consumes to open the My-account sheet (which lives on that screen).
 */
object AccountUiBus {

    var account by mutableStateOf<AccountManager.Account?>(null)
        private set

    var openMyAccountRequested by mutableStateOf(false)

    /** Re-read the signed-in account into Compose state. Call after any login / logout / avatar change. */
    fun refresh(context: Context) {
        account = AccountManager.current(context)
    }

    /** Ask the Shortcuts screen to open the My-account sheet (used by the nav-drawer header row). */
    fun requestMyAccount() {
        openMyAccountRequested = true
    }
}
