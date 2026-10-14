package com.winlator.star.core;

import com.winlator.star.container.Container;
import com.winlator.star.container.Shortcut;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The "Unreal Engine HDR" setting: one place for what the container editor, the game-shortcut editor,
 * XMB game settings and the launch path must agree on. Off / DirectX 12 fix / DirectX 11 (NVAPI).
 *
 * <p><b>DirectX 12 fix</b> ({@link #DX12}) exports {@code DXVK_ENABLE_NVAPI=1} and nothing else. DXVK
 * switches HDR off by itself for any exe whose name contains "-Win64-Shipping" (every Unreal Engine 4
 * game) while d3d12.dll isn't loaded and DXVK_ENABLE_NVAPI isn't 1 ({@code isHDRDisallowed()} in DXVK's
 * dxgi_options.cpp). It is meant for UE4's DX11 path, but UE4 creates its DXGI factory before it loads
 * d3d12.dll, so UE4 games run with {@code -dx12} lose HDR too (the in-game option stays greyed out).
 * The variable skips that check; on a GPU that isn't NVIDIA it changes nothing else in DXVK.
 *
 * <p><b>DirectX 11 (experimental, NVAPI)</b> ({@link #DX11}) is the fix plus the bundled dxvk-nvapi
 * swapped into the prefix and switched on ({@link DxvkNvapi}): UE4's DX11 renderer turns HDR on through
 * NVAPI, and only on an NVIDIA GPU, so it wants the GPU-name spoof set to an NVIDIA card as well (a hint,
 * never forced). Leaving this mode (Off, or back to the DX12 fix) puts the prefix's own files back at
 * the next launch.
 *
 * <p><b>Where it is stored.</b> {@link #EXTRA} in the container's extraData ({@link #DX12} /
 * {@link #DX11}; absent = {@link #OFF}) and in a game shortcut's extras ({@link #OFF} / {@link #DX12} /
 * {@link #DX11}; absent or "" = the container's). The shortcut wins ({@link #effective}). A
 * DXVK_ENABLE_NVAPI or WINEDLLOVERRIDES entry in the environment variables is the user's and wins.
 */
public final class UnrealHdr {
    private UnrealHdr() {}

    /** The extra's key on the container and on a shortcut. */
    public static final String EXTRA = "unrealHdr";

    public static final String OFF = "off";
    public static final String DX12 = "dx12";
    public static final String DX11 = "dx11";

    public static final String TITLE = "Unreal Engine HDR";

    /** The modes in picker order. */
    public static final List<String> MODES = Collections.unmodifiableList(Arrays.asList(OFF, DX12, DX11));

    /** The picker label of a mode. */
    public static String label(String mode) {
        if (DX12.equals(mode)) return "DirectX 12 fix";
        if (DX11.equals(mode)) return "DirectX 11 (experimental, NVAPI)";
        return "Off";
    }

    public static final String HELP_DX12 =
            "For Unreal Engine games run with -dx12 whose HDR option stays greyed out. Safe.";

    /** The DirectX 11 mode's help; names the gear the NVIDIA spoof lives behind on this backend. */
    public static String helpDx11(boolean wayland) {
        return "Experimental. Swaps in dxvk-nvapi for Unreal Engine DirectX 11 games. Pick an NVIDIA GPU in the "
                + (wayland ? "Wayland driver settings" : "graphics driver configuration")
                + ". Turn it off if a game misbehaves.";
    }

    /** The help shown under the picker: both modes, so the choice can be made from the text. */
    public static String help(boolean wayland) {
        return "DirectX 12 fix: " + HELP_DX12 + "\nDirectX 11: " + helpDx11(wayland)
                + " Needs DXVK 2.6 or newer; with an NVIDIA GPU showing, some games also try DLSS or Reflex, "
                + "which dxvk-nvapi reports as unavailable.\n"
                + (wayland ? "Both need HDR output on as well" : "HDR output itself needs the Wayland backend")
                + ", and HDR switched on in the game. Applies from the next launch.";
    }

    /** One line for XMB, whose subtitles are a single line. */
    public static final String HELP_SHORT =
            "DX12 fix: -dx12 games with a greyed-out HDR option (safe). DX11: dxvk-nvapi, experimental.";

    /** Under the picker in DirectX 11 mode while the GPU-name spoof isn't an NVIDIA card. */
    public static String nvidiaHint(boolean wayland) {
        return "No NVIDIA GPU is being reported, and Unreal Engine only takes its NVAPI path on one: pick an "
                + "NVIDIA GPU under GPU name in the "
                + (wayland ? "Wayland driver settings (the gear next to Wayland game driver)" : "graphics driver configuration (the gear)")
                + ". " + SUGGESTED_NVIDIA + " matches the generation dxvk-nvapi reports. Optional.";
    }

    /** The NVIDIA card the hint suggests: Pascal, like dxvk-nvapi's reported architecture on other drivers. */
    public static final String SUGGESTED_NVIDIA = "NVIDIA GeForce GTX 1080";

    /** {@code v} as a mode, or "" for anything that isn't one. */
    public static String normalize(String v) {
        return OFF.equals(v) || DX12.equals(v) || DX11.equals(v) ? v : "";
    }

    /** The container's mode ({@link #OFF} when unset). */
    public static String containerMode(Container container) {
        if (container == null) return OFF;
        String v = normalize(container.getExtra(EXTRA, ""));
        return v.isEmpty() ? OFF : v;
    }

    /** The shortcut's own choice: a mode, or "" = use the container's. */
    public static String shortcutChoice(Shortcut shortcut) {
        return shortcut == null ? "" : normalize(shortcut.getExtra(EXTRA, ""));
    }

    /** The mode a game launches with: the shortcut's own choice, else the container's. */
    public static String effective(Shortcut shortcut, Container container) {
        String s = shortcutChoice(shortcut);
        return s.isEmpty() ? containerMode(container) : s;
    }

    /** Both non-off modes export DXVK_ENABLE_NVAPI=1 (once). */
    public static boolean exportsEnableNvapi(String mode) {
        return DX12.equals(mode) || DX11.equals(mode);
    }

    /** Only the DirectX 11 mode puts dxvk-nvapi into the prefix. */
    public static boolean usesDxvkNvapi(String mode) {
        return DX11.equals(mode);
    }
}
