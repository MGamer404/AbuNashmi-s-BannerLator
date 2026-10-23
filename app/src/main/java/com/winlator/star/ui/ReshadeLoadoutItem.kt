package com.winlator.star.ui

import com.winlator.star.reshade.ReshadeManager

/**
 * One effect in the in-game ReShade loadout: its name, whether it's active (per-effect enabled), the
 * uniforms reflected from its .fx, and the current value-map (seedValues key scheme — "<uniform>" or
 * "<uniform>_<c>" for COLOR). The activity seeds a List<ReshadeLoadoutItem> into XServerDialogState;
 * the drawer edits copies of it and hands a full snapshot back through onReshadeApply → applyReshadeLive.
 *
 * Java-accessed (getName/getEnabled/getParams/getValues) from XServerDisplayActivity.
 */
data class ReshadeLoadoutItem(
    val name: String,
    val enabled: Boolean,
    val params: List<ReshadeManager.ReshadeParam>,
    val values: Map<String, Float>,
)
