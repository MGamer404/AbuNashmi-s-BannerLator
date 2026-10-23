package com.winlator.star.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.winlator.star.components.ComponentUninstaller

/**
 * The confirmation before a component comes out of a container ([ComponentUninstaller]), shared by the
 * classic components sheet, the recommendation chips and the Deck components page: what removing it
 * does, which installed components need it, and - when it can't be removed - why, with Reinstall for
 * one installed before removal was recorded. [forGame]: opened from a game's settings, so it says
 * which container the component leaves. Nothing is removed until [onRemove].
 */
@Composable
internal fun ComponentRemoveDialog(
    plan: ComponentUninstaller.Plan,
    containerName: String,
    forGame: Boolean,
    onRemove: () -> Unit,
    onReinstall: (() -> Unit)?,
    onDismiss: () -> Unit,
    containerColor: Color = AlertDialogDefaults.containerColor,
) {
    val name = plan.name
    val where = if (forGame) containerName else "this container"
    val blocked = plan.blocked
    if (blocked != null) {
        val reinstall = onReinstall?.takeIf { blocked == ComponentUninstaller.NO_RECORD }
        OutlinedAlertDialog(
            onDismissRequest = onDismiss,
            containerColor = containerColor,
            title = { Text("Can't remove $name") },
            text = { Text(blocked) },
            confirmButton = {
                if (reinstall != null) TextButton(onClick = reinstall) { Text("Reinstall") }
                else TextButton(onClick = onDismiss) { Text("OK") }
            },
            dismissButton = if (reinstall != null) ({ TextButton(onClick = onDismiss) { Text("Close") } }) else null,
        )
        return
    }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = containerColor,
        title = { Text("Remove $name from $where?") },
        text = {
            Column {
                Text(
                    if (plan.viaUninstaller) {
                        "There is no record of what $name changed (it went in before removal was supported), but it has its own " +
                            "uninstaller: ${plan.products.joinToString(", ") { it.first }}. The container opens and runs it; " +
                            "the app restarts when it closes."
                    } else {
                        "The files it added are deleted, the ones it replaced are put back and its registry changes are undone. " +
                            "Anything changed since it went in is left as it is."
                    }
                )
                if (forGame) {
                    Spacer(Modifier.height(8.dp))
                    Text("Every game in $containerName loses it, not just this one.")
                }
                if (plan.dependents.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Needed by ${plan.dependents.joinToString(", ")}, installed here: they may stop working without it.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRemove) {
                // intentional: destructive-action red
                Text(if (plan.viaUninstaller) "Run uninstaller" else "Remove", color = Color(0xFFE57373))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
