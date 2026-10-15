package com.winlator.star.linux

import android.content.Context
import com.winlator.star.core.GPUInformation

/**
 * Whether this device can draw a Linux session at all.
 *
 * The runtime draws with Turnip, which is an Adreno driver. On Mali, Xclipse and PowerVR the
 * compositor gets no usable Vulkan device and the session comes up as sound over a black screen —
 * a failure with nothing in it to read. The app cannot fix that, so it says so before the download
 * rather than after it.
 */
object LinuxDeviceSupport {
    /** True when the GPU is one the runtime's driver supports. */
    @JvmStatic
    fun drawable(context: Context): Boolean = GPUInformation.isAdrenoGPU(context)

    /** The GPU as the device reports it, for the card that explains the refusal. */
    @JvmStatic
    fun gpuName(context: Context): String =
        runCatching { GPUInformation.extractModelName(GPUInformation.getRenderer(null, context)) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "this GPU"
}
