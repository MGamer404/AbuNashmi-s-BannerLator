package com.winlator.star.core;

/**
 * The "Codecs" rows of the Win Components tab: which media backend Wine's Media Foundation demuxes
 * with, and whether video decoders may hand the game GPU (DXGI) frames.
 *
 * Both live in the wincomponents "key=index" string (container default, per-game override) next to
 * the DLL overrides, but they are NOT DLL overrides: the launch path skips them in the extraction
 * loop and applies them here instead.
 *
 * Builtin decoder — Wine's mfsrcsnk/mfasfsrcsnk/mfmp4srcsnk byte-stream handlers (every layer we
 * ship, Wine-10 base) read HKCU\Software\Wine\MediaFoundation "DisableGstByteStreamHandler":
 * DWORD 0 = winegstreamer's handler, 1 = winedmo (dlls/mfsrcsnk/media_source.c
 * use_gst_byte_stream_handler). Our layers default to winegstreamer when the value is absent
 * (upstream Wine defaults to winedmo) and also honour WINE_USE_DMO=1, like Pipetto's Proton-9
 * tree. Both are written, so the choice holds on any layer:
 *   GStreamer → DWORD 0 (today's proven path: MF → winegstreamer → GStreamer).
 *   FFmpeg    → DWORD 1 + WINE_USE_DMO=1. Needs a layer whose winedmo was built with FFmpeg
 *               (Proton 11.0-2 vc12+); older ones fail winedmo's probe and stay on GStreamer.
 * DirectShow (quartz) always goes through winegstreamer; this only moves Media Foundation.
 *
 * Software decoding — WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1 makes MFCreateDXGIDeviceManager
 * fail, so MF decoders output system-memory frames (fixes black video with sound in some games).
 */
public final class MediaDecoder {
    public static final String KEY_BACKEND = "builtinDecoder";
    public static final String KEY_SOFTWARE_DECODING = "softwareDecoding";

    public static final int BACKEND_GSTREAMER = 0;
    public static final int BACKEND_FFMPEG = 1;

    /** Option labels, indexed by the stored value. */
    public static final String[] BACKEND_OPTIONS = {"GStreamer", "FFmpeg (winedmo)"};
    public static final String[] SOFTWARE_DECODING_OPTIONS = {"Off", "On"};

    public static final String REG_KEY = "Software\\Wine\\MediaFoundation";
    public static final String REG_VALUE = "DisableGstByteStreamHandler";

    private MediaDecoder() {}

    public static boolean isCodecKey(String key) {
        return KEY_BACKEND.equals(key) || KEY_SOFTWARE_DECODING.equals(key);
    }

    public static String[] optionsFor(String key) {
        return KEY_BACKEND.equals(key) ? BACKEND_OPTIONS : SOFTWARE_DECODING_OPTIONS;
    }

    public static int backend(String wincomponents) {
        return value(wincomponents, KEY_BACKEND, BACKEND_GSTREAMER);
    }

    public static boolean softwareDecoding(String wincomponents) {
        return value(wincomponents, KEY_SOFTWARE_DECODING, 0) == 1;
    }

    private static int value(String wincomponents, String key, int fallback) {
        if (wincomponents == null || wincomponents.isEmpty()) return fallback;
        for (String[] entry : new KeyValueSet(wincomponents)) {
            if (!key.equals(entry[0])) continue;
            try {
                return Integer.parseInt(entry[1]);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
