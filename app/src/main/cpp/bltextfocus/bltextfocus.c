/*
 * bltextfocus.exe: tells Bannerlator when a text field in the running Windows app has the keyboard focus, so the
 * on-screen XMB keyboard can come up by itself.
 *
 * The app's Java X server sees X11 events only; which Windows control owns the focus is known inside the Wine
 * session alone. This program runs there with no window and no console, polls the foreground thread's focus and
 * caret (GetGUIThreadInfo) a few times a second, and sends one UDP datagram to the app's WinHandler on
 * 127.0.0.1:7947 when the answer changes, plus a repeat every 2 s while a text field is focused so a lost datagram
 * cannot leave the app out of step.
 *
 * Bannerlator stages it into C:\windows and starts it through winhandler.exe (winhandler/TextFocusHelper); it is
 * killed by name when the session ends. Arguments:
 *   -v     write every state change to C:\bltextfocus.log (for debugging on the phone)
 *   -stop  ask a running instance to send "inactive" and exit
 *
 * Datagram (30 bytes, little endian, framed like the BANNERIN-XInput datagrams, request code first):
 *    0  u8   17 (RequestCodes.TEXT_FOCUS)
 *    1  4B   "BTF1"
 *    5  u8   active: 1 = a text field has the focus
 *    6  i32  editStyle: the Edit control's style low word (ES_NUMBER 0x2000, ES_PASSWORD 0x20, ES_MULTILINE 0x4,
 *            ES_READONLY 0x800), 0 for controls that are not Edit / RichEdit or when inactive
 *   10  i32  caret left   (screen coordinates; 0 when the focused control has no caret)
 *   14  i32  caret top
 *   18  i32  caret width
 *   22  i32  caret height
 *   26  i32  pid of the foreground window's process
 *
 * Not detected: games that draw their own text boxes without a Windows caret (most Unity / Unreal / SDL games)
 * and so never tell Windows that a text field has the focus.
 */
#include <winsock2.h>
#include <windows.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>

#define TF_CODE 17
#define TF_PORT 7947
#define TF_PACKET_LEN 30
#define POLL_MS 200u
#define KEEPALIVE_MS 2000u
#define SOCKET_RETRY_MS 5000u
#define CLASS_CHARS 80

/* How the focused control's class is treated. */
enum { KIND_NONE, KIND_EDIT /* Edit / RichEdit: the style word is ES_* */, KIND_TEXT /* a text box, style unknown */ };

typedef struct tf_state {
    int active;
    int32_t style;
    int32_t x, y, w, h;
    int32_t pid;
    HWND focus;
    /* only for the log, never compared */
    char cls[48];
    DWORD flags;
} tf_state;

static HANDLE logfile = INVALID_HANDLE_VALUE;

static void say( const char *fmt, ... )
{
    char line[1100]; /* wsprintf writes at most 1024 bytes after the tick count */
    DWORD len, written;
    va_list ap;

    if (logfile == INVALID_HANDLE_VALUE) return;
    len = (DWORD)wsprintfA( line, "%lu ", (unsigned long)GetTickCount() );
    va_start( ap, fmt );
    len += (DWORD)wvsprintfA( line + len, fmt, ap );
    va_end( ap );
    if (len > sizeof(line) - 3) len = sizeof(line) - 3;
    line[len++] = '\r';
    line[len++] = '\n';
    WriteFile( logfile, line, len, &written, NULL );
}

static WCHAR lower( WCHAR c )
{
    return (c >= 'A' && c <= 'Z') ? (WCHAR)(c + ('a' - 'A')) : c;
}

/* Case-insensitive ASCII compare of a class name with a lower-case literal; prefix = only the start must match. */
static int name_match( const WCHAR *name, const char *want, int prefix )
{
    while (*want)
    {
        if (lower( *name ) != (WCHAR)*want) return 0;
        name++;
        want++;
    }
    return prefix || !*name;
}

static int text_kind( const WCHAR *name )
{
    if (name_match( name, "edit", 0 ) || name_match( name, "richedit", 1 ) ||
        name_match( name, "windowsforms10.edit.", 1 )) return KIND_EDIT;
    if (name_match( name, "scintilla", 0 ) || name_match( name, "textbox", 0 ) ||
        name_match( name, "tedit", 0 ) || name_match( name, "tmemo", 0 )) return KIND_TEXT;
    return KIND_NONE;
}

static int read_gui( DWORD tid, GUITHREADINFO *gti )
{
    memset( gti, 0, sizeof(*gti) );
    gti->cbSize = sizeof(*gti);
    return GetGUIThreadInfo( tid, gti ) != 0;
}

/* Fills *s with what the foreground thread has focused; a zeroed state (inactive) when nothing text-like. */
static void sample( tf_state *s )
{
    GUITHREADINFO gti;
    WCHAR name[CLASS_CHARS];
    HWND fg, target;
    DWORD pid = 0, tid;
    int kind = KIND_NONE, n, i;

    memset( s, 0, sizeof(*s) );
    if (!(fg = GetForegroundWindow())) return;
    tid = GetWindowThreadProcessId( fg, &pid );
    /* the foreground thread by id; 0 (= the foreground thread, too) only if Wine refuses the id */
    if (!tid || !read_gui( tid, &gti ))
    {
        if (!read_gui( 0, &gti )) return;
    }

    target = gti.hwndFocus ? gti.hwndFocus : gti.hwndCaret;
    if (!target) return;

    if (gti.hwndFocus)
    {
        name[0] = 0;
        n = GetClassNameW( gti.hwndFocus, name, CLASS_CHARS );
        if (n > 0) kind = text_kind( name );
        else n = 0;
        for (i = 0; i < n && i < (int)sizeof(s->cls) - 1; i++) s->cls[i] = name[i] < 0x80 ? (char)name[i] : '?';
    }
    /* Not "caret blinking": that flag flips with every blink, so it says nothing about focus. Log only. */
    s->flags = gti.flags;
    s->focus = gti.hwndFocus;

    if (kind == KIND_EDIT)
    {
        s->style = (int32_t)((DWORD)GetWindowLongW( gti.hwndFocus, GWL_STYLE ) & 0xffffu);
        if (s->style & ES_READONLY) { s->style = 0; return; }
    }
    /* a caret means text goes there, whatever the class; no caret needs a text-edit class */
    if (!gti.hwndCaret && kind == KIND_NONE) { s->style = 0; return; }
    if (!IsWindowVisible( target ) || !IsWindowEnabled( target )) { s->style = 0; return; }

    s->active = 1;
    s->pid = (int32_t)pid;
    if (gti.hwndCaret)
    {
        RECT r = gti.rcCaret;
        /* rcCaret is in the caret window's client coordinates */
        MapWindowPoints( gti.hwndCaret, NULL, (POINT *)&r, 2 );
        s->x = r.left;
        s->y = r.top;
        s->w = r.right - r.left;
        s->h = r.bottom - r.top;
    }
}

/* What makes a new datagram necessary; the caret position alone does not (the repeat carries it). */
static int differs( const tf_state *a, const tf_state *b )
{
    if (a->active != b->active) return 1;
    if (!a->active) return 0;
    return a->style != b->style || a->pid != b->pid || a->focus != b->focus;
}

static void put32( unsigned char *p, int32_t v )
{
    uint32_t u = (uint32_t)v;
    p[0] = (unsigned char)(u & 0xff);
    p[1] = (unsigned char)((u >> 8) & 0xff);
    p[2] = (unsigned char)((u >> 16) & 0xff);
    p[3] = (unsigned char)((u >> 24) & 0xff);
}

/* UDP errors are normal (the app may not be listening yet or any more): never reported, never fatal. */
static void send_state( SOCKET sock, const struct sockaddr_in *dst, const tf_state *s )
{
    unsigned char p[TF_PACKET_LEN];

    if (sock == INVALID_SOCKET) return;
    p[0] = TF_CODE;
    p[1] = 'B'; p[2] = 'T'; p[3] = 'F'; p[4] = '1';
    p[5] = s->active ? 1 : 0;
    put32( p + 6, s->style );
    put32( p + 10, s->x );
    put32( p + 14, s->y );
    put32( p + 18, s->w );
    put32( p + 22, s->h );
    put32( p + 26, s->pid );
    (void)sendto( sock, (const char *)p, (int)sizeof(p), 0, (const struct sockaddr *)dst, (int)sizeof(*dst) );
}

/* Does the command line carry the argument as a whole token? */
static int has_arg( const char *cmd, const char *arg )
{
    size_t len = strlen( arg );

    while (*cmd)
    {
        const char *end;
        while (*cmd == ' ') cmd++;
        for (end = cmd; *end && *end != ' '; end++) {}
        if ((size_t)(end - cmd) == len && !memcmp( cmd, arg, len )) return 1;
        cmd = end;
    }
    return 0;
}

int WINAPI WinMain( HINSTANCE inst, HINSTANCE prev, LPSTR cmdline, int show )
{
    const char *cmd = GetCommandLineA();
    struct sockaddr_in dst;
    tf_state cur, sent;
    SOCKET sock = INVALID_SOCKET;
    HANDLE stop_event;
    DWORD last_sent = 0, last_socket_try = 0;
    int have_wsa = 0;
    WSADATA wsa;

    (void)inst; (void)prev; (void)cmdline; (void)show;
    if (!cmd) cmd = "";

    /* the stop request and the running instance meet on this name inside the prefix */
    stop_event = CreateEventA( NULL, TRUE, FALSE, "bltextfocus_stop" );
    if (has_arg( cmd, "-stop" ))
    {
        if (stop_event) SetEvent( stop_event );
        return 0;
    }

    if (has_arg( cmd, "-v" ))
        logfile = CreateFileA( "C:\\bltextfocus.log", GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL );
    say( "start" );

    memset( &dst, 0, sizeof(dst) );
    dst.sin_family = AF_INET;
    dst.sin_port = htons( TF_PORT );
    dst.sin_addr.s_addr = htonl( INADDR_LOOPBACK );
    have_wsa = WSAStartup( MAKEWORD( 2, 2 ), &wsa ) == 0;

    memset( &sent, 0, sizeof(sent) );
    sent.active = -1; /* matches nothing: the first sample is always sent, as the app's "helper is running" */

    for (;;)
    {
        DWORD now = GetTickCount();

        if (have_wsa && sock == INVALID_SOCKET && (!last_socket_try || now - last_socket_try >= SOCKET_RETRY_MS))
        {
            last_socket_try = now ? now : 1;
            sock = socket( AF_INET, SOCK_DGRAM, IPPROTO_UDP );
            if (sock == INVALID_SOCKET) say( "no UDP socket, WSAGetLastError %d", WSAGetLastError() );
        }

        sample( &cur );
        if (differs( &cur, &sent ) || (cur.active && now - last_sent >= KEEPALIVE_MS))
        {
            if (differs( &cur, &sent ))
                say( "active=%d style=%#lx pid=%d focus=%#lx class=%s caret=%d,%d %dx%d flags=%#lx", cur.active,
                     (unsigned long)cur.style, (int)cur.pid, (unsigned long)(ULONG_PTR)cur.focus, cur.cls,
                     (int)cur.x, (int)cur.y, (int)cur.w, (int)cur.h, (unsigned long)cur.flags );
            send_state( sock, &dst, &cur );
            sent = cur;
            last_sent = now;
        }

        /* the wait is the poll interval; the event ends it early only for -stop */
        if (stop_event)
        {
            if (WaitForSingleObject( stop_event, POLL_MS ) == WAIT_OBJECT_0) break;
        }
        else Sleep( POLL_MS );
    }

    memset( &cur, 0, sizeof(cur) );
    send_state( sock, &dst, &cur );
    say( "stop" );
    if (sock != INVALID_SOCKET) closesocket( sock );
    if (have_wsa) WSACleanup();
    if (logfile != INVALID_HANDLE_VALUE) CloseHandle( logfile );
    return 0;
}
