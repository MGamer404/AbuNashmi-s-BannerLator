package com.winlator.star.store.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.store.GogRedistInstaller
import com.winlator.star.ui.screens.OutlinedAlertDialog

/**
 * The GOG prerequisites dialogs, drawn from [GogRedistInstaller.PrereqUi]: the container picker,
 * "Install prerequisites?", and the progress window with one line per GOG dep → Win Component.
 */
@Composable
fun GogPrereqDialogs(ui: GogRedistInstaller.PrereqUi) {
    ui.pick?.let { pick ->
        ContainerPickerDialog(
            gameName = "",
            containers = pick.containers,
            title = "Install prerequisites into…",
            onDismiss = { ui.pick = null },
            onSelected = { ui.pick = null; pick.onSelected(it) },
        )
    }

    ui.confirm?.let { confirm ->
        OutlinedAlertDialog(
            onDismissRequest = { ui.confirm = null },
            title = { Text("Install prerequisites?") },
            text = {
                Text(
                    "This game needs: ${confirm.deps.joinToString(", ")}.\n\n" +
                        "They'll be installed into \"${confirm.containerName}\" from Win Components, without " +
                        "opening the container. One with no matching component can use GOG's own installer, " +
                        "which opens the container.",
                )
            },
            confirmButton = {
                TextButton(onClick = { ui.confirm = null; confirm.onInstall() }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { ui.confirm = null }) { Text("Skip") }
            },
        )
    }

    val progress = ui.progress
    if (progress != null && !progress.hidden) {
        val finished = progress.finished
        OutlinedAlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = {
                Text(
                    when {
                        finished == null -> "Installing prerequisites"
                        progress.gog == null -> "Prerequisites installed"
                        else -> "Prerequisites"
                    },
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    progress.rows.forEach { row -> PrereqRowLine(row) }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    val step = finished ?: listOfNotNull(
                        progress.step.ifEmpty { null },
                        progress.percent?.let { "$it %" },
                    ).joinToString(" — ")
                    if (step.isNotEmpty()) {
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                if (finished == null) {
                    TextButton(onClick = { progress.hidden = true }) { Text("Hide") }
                } else {
                    TextButton(onClick = { ui.progress = null }) { Text("Close") }
                }
            },
            dismissButton = {
                val gog = progress.gog
                if (gog != null) {
                    TextButton(onClick = { ui.progress = null; gog() }) { Text("Use GOG's installer") }
                }
            },
        )
    }
}

@Composable
private fun PrereqRowLine(row: GogRedistInstaller.PrereqRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = row.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = row.status,
            style = MaterialTheme.typography.bodySmall,
            color = when (row.state) {
                GogRedistInstaller.RowState.DONE -> MaterialTheme.colorScheme.primary
                GogRedistInstaller.RowState.ACTIVE -> MaterialTheme.colorScheme.tertiary
                GogRedistInstaller.RowState.FAILED -> MaterialTheme.colorScheme.error
                GogRedistInstaller.RowState.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
