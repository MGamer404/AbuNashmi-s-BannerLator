package com.winlator.star.core;

import android.os.Build;
import android.view.InputDevice;

import java.util.Locale;

/**
 * "Rumble motors": how a game's two XInput rumble requests reach a controller.
 *
 * <p>{@link #BOTH} (PC-accurate) drives the strong (low-frequency) and weak (high-frequency) motors
 * independently, as a PC pad does. {@link #SINGLE} folds both requests onto the strong motor at
 * max(strong, weak) and leaves the other motor at 0, so only one motor ever runs.
 *
 * <p>Why it exists: some handheld controllers slow their stick reports while their motors run.
 * Measured on the AYANEO Pocket FIT's built-in pad (xpad "Microsoft X-Box 360 pad", 045E:028E):
 * 500 reports/s idle, ~53/s with one motor running, ~27/s with both, at any strength and through
 * raw kernel force feedback too, so it is the pad's firmware. One motor halves the slowdown.
 *
 * <p>Stored per container in extraData under {@link #EXTRA}, with a per-game shortcut override of
 * the same key. A container with no value of its own follows {@link #deviceDefault()}, so the
 * device default is never baked into a container that didn't choose.
 */
public final class RumbleMotors {
    public static final String EXTRA = "rumbleMotors";
    public static final int BOTH = 0;
    public static final int SINGLE = 1;

    // The Pocket FIT's built-in pad, as Android enumerates it (dumpsys input, 2026-10-16).
    private static final int BUILT_IN_PAD_VENDOR = 0x045E;
    private static final int BUILT_IN_PAD_PRODUCT = 0x028E;

    private RumbleMotors() {}

    /** BOTH or SINGLE for a stored "0" / "1"; null for anything else (absent, blank, garbage). */
    public static Integer parse(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.equals(String.valueOf(BOTH))) return BOTH;
        if (v.equals(String.valueOf(SINGLE))) return SINGLE;
        return null;
    }

    /** Any int coerced to BOTH / SINGLE (unknown = BOTH, today's behaviour). */
    public static int sanitize(int motors) {
        return motors == SINGLE ? SINGLE : BOTH;
    }

    /** Single on an AYANEO Pocket FIT whose built-in pad is present, Both everywhere else. */
    public static int deviceDefault() {
        return isPocketFit() && builtInPadPresent() ? SINGLE : BOTH;
    }

    /**
     * The AYANEO Pocket FIT by its build identity. Measured on the device:
     * ro.product.manufacturer=AYANEO, ro.product.model="Pocket FIT", ro.product.device=PocketFIT.
     * MODEL or DEVICE is enough (a custom ROM may rename one of them).
     */
    public static boolean isPocketFit() {
        return "ayaneo".equals(squash(Build.MANUFACTURER))
                && ("pocketfit".equals(squash(Build.MODEL)) || "pocketfit".equals(squash(Build.DEVICE)));
    }

    /**
     * True while the Pocket FIT's built-in pad (045E:028E) is enumerated. With the pad switched off
     * the rumble can only go to some other controller, which has no reason to be limited to one motor.
     */
    public static boolean builtInPadPresent() {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d != null && d.getVendorId() == BUILT_IN_PAD_VENDOR
                        && d.getProductId() == BUILT_IN_PAD_PRODUCT) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // No input service (unit tests, very early process start): not a Pocket FIT pad.
        }
        return false;
    }

    public static String label(int motors) {
        return motors == SINGLE ? "Single" : "Both";
    }

    /** "AYANEO Pocket FIT (built-in pad present)" style note for the launch log. */
    public static String deviceNote() {
        return Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")"
                + (isPocketFit() ? (builtInPadPresent() ? ", Pocket FIT built-in pad present"
                                                        : ", Pocket FIT built-in pad not found") : "");
    }

    private static String squash(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
