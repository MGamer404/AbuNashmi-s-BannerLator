package com.winlator.star.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.winlator.star.core.StickPacing

/**
 * The Stick updates rate as "−  Up to 60 per second  +" (core.StickPacing.step). It takes taps only:
 * the slider it replaces sat in scrolling forms, and a scroll that began on it changed the rate.
 */
@Composable
fun StickRateStepper(
    pacing: StickPacing,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChange: (StickPacing) -> Unit,
) {
    val down = pacing.step(-1)
    val up = pacing.step(1)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(down) }, enabled = enabled && down != pacing) {
            Icon(Icons.Filled.Remove, contentDescription = "Fewer stick updates")
        }
        Text(
            pacing.rateLabel(),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(onClick = { onChange(up) }, enabled = enabled && up != pacing) {
            Icon(Icons.Filled.Add, contentDescription = "More stick updates")
        }
    }
}
