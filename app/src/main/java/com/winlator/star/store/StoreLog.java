package com.winlator.star.store;

/**
 * Central log-scrubbing helper for the storefront code (Amazon / Epic / GOG).
 *
 * <p>Logging a RAW store URL is dangerous. CDN / manifest download URLs and OAuth
 * token-refresh URLs carry their authorization in the QUERY STRING (signed,
 * time-limited CDN tokens, {@code client_secret}, {@code refresh_token}) or in the
 * userinfo portion of the authority. A single such line written to logcat OR to a
 * shared debug {@code .txt} on external storage hands anyone who reads it a live,
 * downloadable credential. Every store URL that is written to ANY log or file MUST
 * be passed through {@link #redactUrl(String)} first.</p>
 *
 * <p>This helper only produces a safe string for logging — it NEVER touches the value
 * used for the actual network request, login, token refresh, download or cloud save.</p>
 *
 * <p>Dependency-free (manual parsing, no {@code java.net} required) and null-safe.</p>
 */
public final class StoreLog {

    private StoreLog() {}

    /**
     * Returns {@code scheme://host/path} with the query string, fragment and any
     * {@code user:pass@} userinfo stripped — i.e. everything from {@code '?'} (or
     * {@code '#'}) onward is dropped, and credentials embedded in the authority are
     * removed. The auth on a signed store URL lives in exactly those parts.
     *
     * <p>Null-safe (returns {@code null} for {@code null} input). If the value cannot
     * be parsed as a URL, returns the safe placeholder {@code "[url]"} — never the raw
     * input.</p>
     */
    public static String redactUrl(String url) {
        if (url == null) return null;
        try {
            // 1. Drop fragment then query — the signed token / secret lives here.
            String out = url;
            int hash = out.indexOf('#');
            if (hash >= 0) out = out.substring(0, hash);
            int q = out.indexOf('?');
            if (q >= 0) out = out.substring(0, q);

            // 2. Strip userinfo (user:pass@host) from the authority, if present.
            int scheme = out.indexOf("://");
            if (scheme >= 0) {
                int authStart = scheme + 3;
                int pathStart = out.indexOf('/', authStart);
                String authority = (pathStart >= 0)
                        ? out.substring(authStart, pathStart)
                        : out.substring(authStart);
                int at = authority.lastIndexOf('@');
                if (at >= 0) {
                    out = out.substring(0, authStart)
                            + authority.substring(at + 1)
                            + (pathStart >= 0 ? out.substring(pathStart) : "");
                }
            }

            if (out.isEmpty()) return "[url]";
            return out;
        } catch (Exception e) {
            return "[url]";
        }
    }
}
