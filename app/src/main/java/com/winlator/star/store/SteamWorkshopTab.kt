package com.winlator.star.store

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * The Workshop tab of a Steam game's page: the items you are subscribed to, and a browser for the
 * game's whole Workshop where you can subscribe. Items download in [SteamWorkshop]'s own queue, so
 * leaving the tab does not stop them.
 *
 * It sits inside the page's own vertical scroll, so lists here are plain columns with a "Load more"
 * button rather than lazy lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SteamWorkshopTab(appId: Int, signedIn: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads by SteamWorkshop.downloads.collectAsState()
    val failures by SteamWorkshop.failures.collectAsState()
    val installedVersion by SteamWorkshop.installedVersion.collectAsState()

    var contentDir by remember(appId) { mutableStateOf<File?>(null) }
    var subscribed by remember(appId) { mutableStateOf<List<WorkshopItem>>(emptyList()) }
    var subscribedFailed by remember(appId) { mutableStateOf(false) }
    var loadingSubs by remember(appId) { mutableStateOf(true) }
    var installedStamps by remember(appId) { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var installedItems by remember(appId) { mutableStateOf<List<WorkshopItem>>(emptyList()) }
    var browseMode by remember(appId) { mutableStateOf(false) }
    val working = remember(appId) { mutableStateMapOf<Long, Boolean>() }
    var notice by remember(appId) { mutableStateOf<String?>(null) }

    var sort by remember(appId) { mutableStateOf(WorkshopSort.TRENDING) }
    var queryText by remember(appId) { mutableStateOf("") }
    var submittedQuery by remember(appId) { mutableStateOf("") }
    var results by remember(appId) { mutableStateOf<List<WorkshopItem>>(emptyList()) }
    var resultsTotal by remember(appId) { mutableStateOf(0) }
    var resultsPage by remember(appId) { mutableStateOf(0) }
    var browseLoading by remember(appId) { mutableStateOf(false) }
    var browseFailed by remember(appId) { mutableStateOf(false) }

    suspend fun reloadSubscriptions() {
        loadingSubs = true
        val list = if (signedIn) SteamWorkshop.subscribed(appId) else null
        subscribedFailed = list == null
        if (list != null) subscribed = list
        loadingSubs = false
    }

    suspend fun loadBrowsePage(reset: Boolean) {
        browseLoading = true
        val next = if (reset) 1 else resultsPage + 1
        val page = SteamWorkshop.browse(appId, sort, next, submittedQuery, emptyList())
        if (page == null) {
            browseFailed = true
        } else {
            browseFailed = false
            results = if (reset) page.items else results + page.items.filter { n -> results.none { it.id == n.id } }
            resultsTotal = page.total
            resultsPage = next
        }
        browseLoading = false
    }

    LaunchedEffect(appId) {
        contentDir = withContext(Dispatchers.IO) {
            SteamWorkshop.installDirOf(ctx, appId)?.let { SteamWorkshop.contentDir(it, appId) }
        }
    }
    LaunchedEffect(appId, signedIn) { reloadSubscriptions() }
    LaunchedEffect(contentDir, installedVersion) {
        val dir = contentDir ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            installedStamps = SteamWorkshop.installed(dir)
            installedItems = SteamWorkshop.installedItems(dir, appId)
        }
    }
    LaunchedEffect(browseMode, sort, submittedQuery) {
        if (browseMode && signedIn) loadBrowsePage(reset = true)
    }

    fun install(item: WorkshopItem) {
        val dir = contentDir
        if (dir == null) {
            notice = "Install the game first — Workshop items download next to it."
            return
        }
        SteamWorkshop.enqueue(ctx, item, dir)
    }

    fun subscribeAndInstall(item: WorkshopItem) {
        scope.launch {
            working[item.id] = true
            val result = SteamWorkshop.setSubscribed(appId, item, subscribe = true)
            if (!result.ok) {
                notice = "Couldn't subscribe to ${item.title}: ${result.message}"
            } else {
                // The subscription list is what carries the download info, so use its copy of the item.
                reloadSubscriptions()
                val listed = subscribed.firstOrNull { it.id == item.id }
                when {
                    listed == null -> notice = "Subscribed to ${item.title}. Steam hasn't listed it yet — pull it from Subscribed in a moment."
                    !listed.downloadable -> notice = "Subscribed to ${item.title}, but it has no downloadable files."
                    else -> install(listed)
                }
            }
            working.remove(item.id)
        }
    }

    fun unsubscribe(item: WorkshopItem) {
        scope.launch {
            working[item.id] = true
            val result = SteamWorkshop.setSubscribed(appId, item, subscribe = false)
            if (!result.ok) {
                notice = "Couldn't unsubscribe from ${item.title}: ${result.message}"
            } else {
                contentDir?.let { dir ->
                    withContext(Dispatchers.IO) { SteamWorkshop.remove(dir, item.id) }
                    installedStamps = withContext(Dispatchers.IO) { SteamWorkshop.installed(dir) }
                    installedItems = withContext(Dispatchers.IO) { SteamWorkshop.installedItems(dir, appId) }
                }
                reloadSubscriptions()
            }
            working.remove(item.id)
        }
    }

    fun removeFiles(item: WorkshopItem) {
        val dir = contentDir ?: return
        scope.launch {
            working[item.id] = true
            withContext(Dispatchers.IO) { SteamWorkshop.remove(dir, item.id) }
            installedStamps = withContext(Dispatchers.IO) { SteamWorkshop.installed(dir) }
            installedItems = withContext(Dispatchers.IO) { SteamWorkshop.installedItems(dir, appId) }
            working.remove(item.id)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)) {
        if (!signedIn) {
            Text(
                text = "Sign in to Steam to see and manage this game's Workshop items.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !browseMode,
                onClick = { browseMode = false },
                label = { Text("Subscribed (${subscribed.size})") },
            )
            FilterChip(
                selected = browseMode,
                onClick = { browseMode = true },
                label = { Text("Browse") },
            )
        }

        if (contentDir == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This game isn't installed, so items can't be downloaded yet. You can still browse and subscribe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        notice?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { notice = null }) { Text("OK") }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (!browseMode) {
            val subscribedIds = subscribed.map { it.id }.toSet()
            val onlyInstalled = installedItems.filter { it.id !in subscribedIds }
            val shown = subscribed + onlyInstalled
            when {
                loadingSubs && shown.isEmpty() -> Loading()
                subscribedFailed && shown.isEmpty() ->
                    Hint("Couldn't reach Steam to list your subscriptions.", onRetry = { scope.launch { reloadSubscriptions() } })
                shown.isEmpty() ->
                    Hint("You aren't subscribed to anything for this game yet. Use Browse to find items.", null)
                else -> {
                    if (subscribedFailed) {
                        Text(
                            text = "Couldn't reach Steam — showing what is installed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    shown.forEach { item ->
                        val isSubscribed = item.id in subscribedIds
                        WorkshopRow(
                            item = item,
                            installedStamp = installedStamps[item.id],
                            download = downloads[item.id],
                            failure = failures[item.id],
                            busy = working[item.id] == true,
                            subscribed = isSubscribed,
                            onInstall = { install(item) },
                            onRemove = { removeFiles(item) },
                            onSubscribe = { subscribeAndInstall(item) },
                            onUnsubscribe = { unsubscribe(item) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    singleLine = true,
                    placeholder = { Text("Search this game's Workshop") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submittedQuery = queryText.trim() }),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { submittedQuery = queryText.trim() },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Search") }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WorkshopSort.values().forEach { option ->
                    FilterChip(
                        selected = option == sort && submittedQuery.isEmpty(),
                        onClick = { queryText = ""; submittedQuery = ""; sort = option },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val subscribedIds = subscribed.map { it.id }.toSet()
            when {
                browseLoading && results.isEmpty() -> Loading()
                browseFailed && results.isEmpty() ->
                    Hint("Couldn't load the Workshop from Steam.", onRetry = { scope.launch { loadBrowsePage(reset = true) } })
                results.isEmpty() -> Hint("Nothing found.", null)
                else -> {
                    results.forEach { item ->
                        WorkshopRow(
                            item = item,
                            installedStamp = installedStamps[item.id],
                            download = downloads[item.id],
                            failure = failures[item.id],
                            busy = working[item.id] == true,
                            subscribed = item.id in subscribedIds,
                            onInstall = { install(subscribed.firstOrNull { it.id == item.id } ?: item) },
                            onRemove = { removeFiles(item) },
                            onSubscribe = { subscribeAndInstall(item) },
                            onUnsubscribe = { unsubscribe(item) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (results.size < resultsTotal) {
                        OutlinedButton(
                            onClick = { scope.launch { loadBrowsePage(reset = false) } },
                            enabled = !browseLoading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(if (browseLoading) "Loading…" else "Load more (${results.size} of $resultsTotal)") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** A message with an optional Retry. */
@Composable
private fun Hint(text: String, onRetry: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        }
    }
}

/**
 * One item: picture, title, a meta line, a status line and the actions that apply.
 * [installedStamp] is the `time_updated` the installed copy was downloaded at (null = not installed).
 */
@Composable
private fun WorkshopRow(
    item: WorkshopItem,
    installedStamp: Long?,
    download: WorkshopDownload?,
    failure: String?,
    busy: Boolean,
    subscribed: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onSubscribe: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    val installed = installedStamp != null
    val outdated = SteamWorkshop.needsUpdate(item, installedStamp)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    if (item.previewUrl.isNotEmpty()) {
                        AsyncImage(
                            model = item.previewUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metaLine(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.shortDescription.isNotEmpty()) {
                        Text(
                            text = item.shortDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            val status = when {
                download != null && download.active -> null
                download != null -> "Waiting to download…"
                failure != null -> "Failed: $failure"
                outdated -> "Update available"
                installed -> "Installed"
                subscribed -> "Subscribed — not downloaded"
                else -> null
            }
            if (download != null && download.active) {
                Spacer(Modifier.height(6.dp))
                val fraction = if (download.total > 0L) (download.done.toFloat() / download.total).coerceIn(0f, 1f) else 0f
                if (download.total > 0L) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = "Downloading ${fmtBytes(download.done)}" + if (download.total > 0L) " of ${fmtBytes(download.total)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (status != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        failure != null -> MaterialTheme.colorScheme.error
                        outdated -> MaterialTheme.colorScheme.tertiary
                        installed -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            val idle = download == null && !busy
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                if (installed) {
                    TextButton(onClick = onRemove, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
                }
                if (subscribed) {
                    TextButton(onClick = onUnsubscribe, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (installed) "Unsubscribe & remove" else "Unsubscribe")
                    }
                    if (!installed || outdated) {
                        Button(onClick = onInstall, enabled = idle && item.downloadable, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (outdated) "Update" else "Download")
                        }
                    }
                } else {
                    Button(onClick = onSubscribe, enabled = idle, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Subscribe & download")
                    }
                }
            }
        }
    }
}

private fun metaLine(item: WorkshopItem): String {
    val parts = ArrayList<String>()
    if (item.sizeBytes > 0L) parts.add(fmtBytes(item.sizeBytes))
    if (item.timeUpdated > 0L) {
        parts.add("updated " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.timeUpdated * 1000)))
    }
    if (item.subscriptions > 0) parts.add("%,d subscribers".format(item.subscriptions))
    val votes = item.votesUp + item.votesDown
    if (votes > 0) parts.add("${item.votesUp * 100 / votes}% positive")
    return parts.joinToString(" · ")
}

private fun fmtBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    else                    -> "%.0f KB".format(bytes / 1024.0)
}
