package com.winlator.star.communityconfigs

import android.content.Context
import android.os.Build
import com.winlator.star.core.GPUInformation

/**
 * Best-effort description of the running device, used to rank community-config device rows.
 * Nothing here is required — when a value is unavailable we simply fall back to showing all rows.
 */
object DeviceIdentity {

    /** SoC model (e.g. "SM8650"). {@code Build.SOC_MODEL} is API 31+; null on older devices. */
    fun soc(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val model = Build.SOC_MODEL
            if (!model.isNullOrBlank() && model != Build.UNKNOWN) return model
        }
        return null
    }

    /**
     * Friendly device name from {@code Build.MANUFACTURER} + {@code Build.MODEL}, purely for display
     * (e.g. "AYANEO Pocket FIT", "Samsung Galaxy S24"). Returns null when either field is blank or
     * unknown. This is NEVER used for device matching — that stays on the SoC/GPU labels.
     */
    fun deviceModel(): String? {
        val rawMaker = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        if (rawMaker.isBlank() || rawMaker.equals(Build.UNKNOWN, ignoreCase = true)) return null
        if (model.isBlank() || model.equals(Build.UNKNOWN, ignoreCase = true)) return null
        // Many vendors style their name in all-caps (AYANEO, ZTE, LG); preserve that. Otherwise
        // present a normal word title-cased ("samsung" -> "Samsung").
        val maker = if (rawMaker.any { it.isLetter() } && rawMaker == rawMaker.uppercase()) {
            rawMaker
        } else {
            rawMaker.split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { c -> c.uppercase() }
            }
        }
        // Some models already lead with the brand ("Samsung Galaxy S24"); don't duplicate it.
        if (model.startsWith(maker, ignoreCase = true) || model.startsWith(rawMaker, ignoreCase = true)) {
            return model
        }
        return "$maker $model"
    }

    /** GPU/driver name from the existing native probe (e.g. "Adreno 750"); null on failure. */
    fun gpu(context: Context): String? =
        try {
            GPUInformation.getRenderer(null, context)?.takeIf {
                it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
            }
        } catch (e: Throwable) {
            null
        }
}
