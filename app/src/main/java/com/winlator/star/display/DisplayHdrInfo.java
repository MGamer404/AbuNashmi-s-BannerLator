package com.winlator.star.display;

import android.os.Build;
import android.view.Display;

/**
 * What the platform says about one display's HDR capability — read live, never cached.
 *
 * <p>Reporting, plus one input to the Wayland compositor's opt-in HDR10 output (round 1,
 * {@code BANNER_WAYLAND_HDR=1}): that output is only offered when {@link #supportsHdr10} holds for the
 * display the game is on. Without the switch every layer still goes out with
 * {@code dataspace=UNKNOWN} (sRGB), so a display with HDR types listed is telling us what it
 * <em>could</em> take. See {@code app/src/main/cpp/waylandcomp/HDR_RECON.md}.
 *
 * <p><b>Why it is read per display and re-read on every change.</b> HDR capability is a property of
 * the <em>connector</em>, derived from that display's EDID CTA-861.3 static metadata block, not of
 * the device. Plugging a monitor into USB-C gives the app a second {@link Display} with a different
 * answer, and the game can be moved onto it ({@link ExternalDisplayController}). The build-time
 * property {@code ro.surface_flinger.has_HDR_display} is a different thing again and can disagree
 * with this; the runtime per-display answer is the one that decides what SurfaceFlinger does.
 *
 * <p>Every API above the module's minimum (26) is version-guarded and every call is wrapped: an
 * older device, or a vendor framework that throws here, must fall back to "unknown", never crash a
 * running game.
 */
public final class DisplayHdrInfo {

    /** Display types as a human list, or "none" when the display reports no HDR type at all. */
    public final String formats;
    /** True when the display reports at least one HDR type. */
    public final boolean hasHdr;
    /** True when HDR10 is among them — what the Wayland compositor's HDR output needs (BT.2020 + PQ). */
    public final boolean supportsHdr10;
    /** Desired peak luminance in nits, < 0 when the platform did not say. */
    public final float maxLuminance;
    /** Desired max frame-average luminance in nits, < 0 when unknown. */
    public final float maxAverageLuminance;
    /** Desired minimum luminance in nits, < 0 when unknown. */
    public final float minLuminance;
    /** Whether this display exposes a live HDR/SDR brightness ratio (API 34+). */
    public final boolean hdrSdrRatioAvailable;
    /** The current HDR/SDR ratio when available, else < 0. */
    public final float hdrSdrRatio;
    /** The highest HDR/SDR ratio the display says it can reach (Display.getHighestHdrSdrRatio, Android 16+,
     *  read by reflection), else < 0. 1.0 means the display gives no HDR boost at all. */
    public final float highestHdrSdrRatio;
    /** Display id and name, for the log line. */
    public final int displayId;
    public final String displayName;

    private DisplayHdrInfo(String formats, boolean hasHdr, boolean supportsHdr10, float maxLuminance,
                           float maxAverageLuminance, float minLuminance, boolean hdrSdrRatioAvailable,
                           float hdrSdrRatio, float highestHdrSdrRatio, int displayId, String displayName) {
        this.formats = formats;
        this.hasHdr = hasHdr;
        this.supportsHdr10 = supportsHdr10;
        this.maxLuminance = maxLuminance;
        this.maxAverageLuminance = maxAverageLuminance;
        this.minLuminance = minLuminance;
        this.hdrSdrRatioAvailable = hdrSdrRatioAvailable;
        this.hdrSdrRatio = hdrSdrRatio;
        this.highestHdrSdrRatio = highestHdrSdrRatio;
        this.displayId = displayId;
        this.displayName = displayName;
    }

    /** Never returns null and never throws; an unreadable display comes back as "unknown". */
    @SuppressWarnings("deprecation")
    public static DisplayHdrInfo read(Display d) {
        if (d == null) return new DisplayHdrInfo("unknown", false, false, -1f, -1f, -1f, false, -1f, -1f, -1, "—");

        int id = -1;
        String name = "—";
        try { id = d.getDisplayId(); } catch (Throwable ignored) {}
        try { String n = d.getName(); if (n != null && !n.isEmpty()) name = n; } catch (Throwable ignored) {}

        // Two sources for the type list. From Android 14 the per-mode list is authoritative (a display
        // can offer HDR on some output modes only) and Display.getHdrCapabilities() is deprecated;
        // below that, the display-wide list is all there is. Prefer the mode's when it is non-empty.
        int[] types = null;
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Display.Mode m = d.getMode();
                if (m != null) {
                    int[] t = m.getSupportedHdrTypes();
                    if (t != null && t.length > 0) types = t;
                }
            } catch (Throwable ignored) {}
        }

        float maxL = -1f, maxAvgL = -1f, minL = -1f;
        try {
            Display.HdrCapabilities caps = d.getHdrCapabilities();
            if (caps != null) {
                if (types == null) {
                    int[] t = caps.getSupportedHdrTypes();
                    if (t != null) types = t;
                }
                maxL = caps.getDesiredMaxLuminance();
                maxAvgL = caps.getDesiredMaxAverageLuminance();
                minL = caps.getDesiredMinLuminance();
            }
        } catch (Throwable ignored) {}

        boolean ratioAvailable = false;
        float ratio = -1f;
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                ratioAvailable = d.isHdrSdrRatioAvailable();
                if (ratioAvailable) ratio = d.getHdrSdrRatio();
            } catch (Throwable ignored) {}
        }

        boolean hasHdr = types != null && types.length > 0;
        boolean hdr10 = false;
        if (types != null) for (int t : types) if (t == Display.HdrCapabilities.HDR_TYPE_HDR10) hdr10 = true;
        return new DisplayHdrInfo(typeList(types), hasHdr, hdr10, maxL, maxAvgL, minL,
                ratioAvailable, ratio, highestHdrSdrRatio(d), id, name);
    }

    /** The live HDR/SDR ratio of {@code d} (API 34+), or -1 when the display does not report one. It is
     *  1.0 while only SDR is on screen and rises when the display actually grants HDR headroom — the one
     *  platform reading that says an HDR layer is really being shown as HDR. Never throws. */
    public static float liveHdrSdrRatio(Display d) {
        if (d == null || Build.VERSION.SDK_INT < 34) return -1f;
        try {
            return d.isHdrSdrRatioAvailable() ? d.getHdrSdrRatio() : -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    /** Display.getHighestHdrSdrRatio() where the platform has it (Android 16+; not in the compile SDK's
     *  stubs, so by reflection), else -1. Never throws. */
    public static float highestHdrSdrRatio(Display d) {
        if (d == null || Build.VERSION.SDK_INT < 34) return -1f;
        try {
            Object r = Display.class.getMethod("getHighestHdrSdrRatio").invoke(d);
            return r instanceof Float ? (Float) r : -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    private static String typeList(int[] types) {
        if (types == null) return "unknown";
        if (types.length == 0) return "none";
        StringBuilder sb = new StringBuilder();
        for (int t : types) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(typeName(t));
        }
        return sb.toString();
    }

    private static String typeName(int t) {
        switch (t) {
            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: return "Dolby Vision";
            case Display.HdrCapabilities.HDR_TYPE_HDR10:        return "HDR10";
            case Display.HdrCapabilities.HDR_TYPE_HLG:          return "HLG";
            case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS:   return "HDR10+";
            default:                                            return "type " + t;
        }
    }

    private static String nits(float v) {
        if (v < 0f) return "unknown";
        return (Math.abs(v - Math.round(v)) < 0.05f)
                ? String.valueOf(Math.round(v)) + " nits"
                : String.format(java.util.Locale.US, "%.1f nits", v);
    }

    /** Short value for the Task Manager's CONTAINER block: "none" / "HDR10, HLG · 1000 nits". */
    public String shortSummary() {
        if (!hasHdr) {
            // The panel's own peak is still worth showing: it is what any tone-map is aimed at.
            return maxLuminance > 0f ? "none · panel " + nits(maxLuminance) : "none";
        }
        return formats + (maxLuminance > 0f ? " · " + nits(maxLuminance) : "");
    }

    /** One line for the compositor's session log ("display" area). */
    public String logLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("HDR capability of \"").append(displayName).append("\" (display ").append(displayId)
          .append(", Android API ").append(Build.VERSION.SDK_INT).append("): formats ").append(formats)
          .append(" | luminance max ").append(nits(maxLuminance))
          .append(", max average ").append(nits(maxAverageLuminance))
          .append(", min ").append(nits(minLuminance))
          .append(" | HDR/SDR headroom ");
        if (Build.VERSION.SDK_INT < 34) sb.append("not reportable below Android 14");
        else if (!hdrSdrRatioAvailable) sb.append("not available on this display");
        else sb.append(String.format(java.util.Locale.US, "available (ratio %.2f)", hdrSdrRatio));
        if (highestHdrSdrRatio > 0f)
            sb.append(String.format(java.util.Locale.US, ", highest ratio %.2f", highestHdrSdrRatio))
              .append(highestHdrSdrRatio <= 1.01f ? " (no HDR boost at all: no app can raise HDR brightness here)" : "");
        else if (Build.VERSION.SDK_INT >= 34) sb.append(", highest ratio not reported");
        sb.append(hasHdr
                ? (supportsHdr10
                    ? " -- the display accepts HDR10 (Wayland: the HDR output setting offers it to games; see the 'color' lines)"
                    : " -- the display accepts HDR, but not HDR10, which is what games send")
                : " -- SDR only: an HDR layer here would be tone-mapped and dropped to GPU composition");
        return sb.toString();
    }

    /** Two readings are the same fact when everything a tester would quote matches. */
    public boolean sameAs(DisplayHdrInfo o) {
        return o != null && displayId == o.displayId && formats.equals(o.formats)
                && maxLuminance == o.maxLuminance && maxAverageLuminance == o.maxAverageLuminance
                && minLuminance == o.minLuminance && hdrSdrRatioAvailable == o.hdrSdrRatioAvailable;
    }
}
