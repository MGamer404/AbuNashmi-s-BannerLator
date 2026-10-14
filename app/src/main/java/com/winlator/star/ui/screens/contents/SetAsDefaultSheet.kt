package com.winlator.star.ui.screens.contents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.winlator.star.ui.screens.OutlinedAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The gold "Default · arch" tag (and the Set as default menu accent). */
internal val DefaultGold = Color(0xFFE8A92A)

@Composable
internal fun DefaultBadge(arch: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(DefaultGold.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .border(1.dp, DefaultGold.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)) {
        Icon(Icons.Filled.Star, null, tint = DefaultGold, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        Text("Default · ${SetAsDefault.archLabel(arch)}", style = MaterialTheme.typography.labelSmall,
            color = DefaultGold, fontWeight = FontWeight.Bold)
    }
}

/**
 * Set as default sheet: pick the architecture, tick where it applies (new container defaults /
 * every existing container of that arch / every game in them), confirm the summary when existing
 * containers or games are touched, then apply off-main. [onApplied] gets the result line for the
 * Undo snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetAsDefaultSheet(item: SetAsDefault.Item, onDismiss: () -> Unit, onApplied: (SetAsDefault.Outcome) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isLayer = item.kind == SetAsDefault.Kind.LAYER

    var arch by remember(item) { mutableStateOf(if (SetAsDefault.ARM64EC in item.archs) SetAsDefault.ARM64EC else SetAsDefault.X86_64) }
    var toDefaults by remember(item) { mutableStateOf(true) }
    var toContainers by remember(item) { mutableStateOf(false) }
    var toGames by remember(item) { mutableStateOf(false) }
    var counts by remember(item, arch) { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(item, arch) {
        counts = null
        val s = withContext(Dispatchers.IO) { runCatching { SetAsDefault.scope(context.applicationContext, arch) }.getOrNull() }
        counts = (s?.containers?.size ?: 0) to (s?.shortcuts?.size ?: 0)
    }

    val archText = SetAsDefault.archLabel(arch)
    val nC = counts?.first
    val nG = counts?.second
    fun n(v: Int?) = v?.toString() ?: "…"

    fun run() {
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                SetAsDefault.apply(context.applicationContext, item, arch, toDefaults, toContainers, toGames && !isLayer)
            }
            busy = false
            onApplied(out)
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheetState,
        containerColor = cs.surface, contentColor = cs.onSurface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Set as default", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(buildAnnotatedString {
                append("${item.title} — sets the ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = cs.onSurface)) { append(item.kind.setting) }
            }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

            SheetLabel("Architecture")
            val archs = listOf(SetAsDefault.ARM64EC, SetAsDefault.X86_64)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                archs.forEachIndexed { index, a ->
                    SegmentedButton(
                        selected = arch == a,
                        enabled = a in item.archs && !busy,
                        onClick = { arch = a },
                        shape = SegmentedButtonDefaults.itemShape(index, archs.size),
                    ) { Text(SetAsDefault.archLabel(a)) }
                }
            }
            if (item.archs.size < 2) {
                Text("${item.title} only works with ${SetAsDefault.archLabel(item.archs.first())} containers.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            SheetLabel("Apply to")
            ApplyCheck(toDefaults, enabled = !busy, title = "New container defaults",
                sub = "Every new $archText container starts with it.") { toDefaults = it }
            ApplyCheck(toContainers, enabled = !busy, title = "All existing containers",
                sub = "${n(nC)} $archText containers switch to it now." +
                    if (isLayer) " Uses the in-place layer update with a revert snapshot; saves are kept." else "") { toContainers = it }
            ApplyCheck(toGames && !isLayer, enabled = !isLayer && !busy, title = "All game shortcuts",
                sub = if (isLayer) "Games always use their container’s layer, so there is nothing to set per game."
                      else "${n(nG)} games in those containers get it written into their own settings.") { toGames = it }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = DefaultGold)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                val any = toDefaults || toContainers || (toGames && !isLayer)
                Button(
                    onClick = { if (toContainers || (toGames && !isLayer)) confirm = true else run() },
                    enabled = any && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = DefaultGold, contentColor = Color(0xFF1A1204)),
                ) { Text("Apply", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (confirm) {
        val lines = buildList {
            if (toDefaults) add("New $archText containers will use it")
            if (toContainers) add("${n(nC)} $archText containers change their ${item.kind.setting}")
            if (toGames && !isLayer) add("${n(nG)} games get it set as their own ${item.kind.setting}")
        }
        OutlinedAlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = cs.surfaceContainerHigh,
            title = { Text("Apply ${item.title}?", color = cs.onSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lines.forEach { Text("•  $it", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(6.dp))
                    Text("Containers or games that are running are skipped. You can undo this right after.",
                        color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { confirm = false; run() }) { Text("Apply", color = cs.primary) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = cs.primary) } },
        )
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ApplyCheck(checked: Boolean, enabled: Boolean, title: String, sub: String, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .border(1.dp, cs.outline, RoundedCornerShape(12.dp))
            .background(cs.onSurface.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(end = 12.dp, top = 2.dp, bottom = 10.dp)) {
        Checkbox(checked = checked, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
