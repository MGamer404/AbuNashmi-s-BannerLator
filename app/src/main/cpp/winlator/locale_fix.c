/*
 * liblocale_fix.so — give Wine the container's locale (LC_ALL) on Android.
 *
 * Wine picks the Windows system/user locale, and with it the ANSI code page, from
 * setlocale(LC_CTYPE / LC_MESSAGES, ""). Android's libc ignores the environment there and always
 * answers "C.UTF-8"; Wine falls back to getenv("LC_ALL") only for exactly "C", so "C.UTF-8" became
 * the unknown locale "C" -> en-US -> code page 1252. GBK / Shift-JIS games then showed mojibake in
 * title bars, dialogs and the folders they create (Chinese report, 2026-10-24).
 *
 * How: interpose setlocale() via LD_PRELOAD. A query with "" for LC_ALL, LC_CTYPE or LC_MESSAGES
 * still calls the real setlocale (so libc state is unchanged) but returns the name from LC_ALL,
 * else LC_CTYPE / LC_MESSAGES, else LANG — the names Wine's unix_to_win_locale() understands
 * (zh_CN.UTF-8 -> zh-CN -> code page 936). Plain "C" / "C.*" / "POSIX" values are left alone.
 *
 * Env:
 *   LOCALE_FIX_DISABLE=1  pass everything through untouched.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <locale.h>
#include <stdlib.h>
#include <string.h>

typedef char *(*setlocale_fn)(int, const char *);

static setlocale_fn next_setlocale;
static char names[3][64];

static const char *wanted(int category)
{
    const char *v = getenv("LC_ALL");
    if (!v || !*v) v = getenv(category == LC_MESSAGES ? "LC_MESSAGES" : "LC_CTYPE");
    if (!v || !*v) v = getenv("LANG");
    if (!v || !*v || !strcmp(v, "C") || !strncmp(v, "C.", 2) || !strcmp(v, "POSIX")) return NULL;
    return v;
}

char *setlocale(int category, const char *locale)
{
    if (!next_setlocale) next_setlocale = (setlocale_fn)dlsym(RTLD_NEXT, "setlocale");
    char *r = next_setlocale ? next_setlocale(category, locale) : NULL;
    if (!r || !locale || *locale) return r;

    int slot = category == LC_ALL ? 0 : category == LC_CTYPE ? 1 : category == LC_MESSAGES ? 2 : -1;
    if (slot < 0) return r;
    const char *e = getenv("LOCALE_FIX_DISABLE");
    if (e && *e == '1') return r;
    const char *v = wanted(category);
    if (!v || strlen(v) >= sizeof(names[0])) return r;
    strcpy(names[slot], v);
    return names[slot];
}
