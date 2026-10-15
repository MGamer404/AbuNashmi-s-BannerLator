# The Linux runtime (`linuxfs`)

A second userland beside the Wine imagefs: a glibc aarch64 rootfs in which a Linux ELF can be
exec'd as the app's own uid, with [gamescope](https://github.com/ValveSoftware/gamescope) as the
session compositor and Valve's **native arm64** Steam client. No Wine, no box64, no FEX — the Steam
client is a real aarch64 ELF, and the games it runs use Steam's own Proton.

Ported from WinNative's gamescope runtime (GPL-3.0), which proved the approach on device.

## What builds it

`build-linuxfs.sh <work dir> <out.tar.zst>` — driven by `.github/workflows/build-linuxfs.yml`,
never by hand.

1. Resolves a package closure over the Arch Linux ARM `core`/`extra`/`alarm` databases from the
   seed list at the top of the script (~300 packages), following `%DEPENDS%` and `%PROVIDES%`.
2. Unpacks the ALARM base tarball and every package into `rootfs/`.
3. Cross-builds **Turnip** with `build-turnip.sh`: Arch's Mesa only knows the `msm` kernel driver,
   and Android reaches the Adreno through **KGSL**, so Mesa is built with
   `-Dfreedreno-kmds=msm,kgsl` and `turnip/kgsl-drm-node.patch` on top, which makes it report the
   KGSL device as its own DRM node.
4. Adds **GTK 2 from Debian** — Steam's arm64 UI (`steamui.so`, `vgui2_s.so`) still links it and
   Arch no longer packages it.
5. Copies `overlay/` in and builds `libblsession.so` from `preload/`.
6. Runs what pacman's hooks would have run — the mime, pixbuf, schema, icon and font caches —
   from inside the rootfs under qemu, since nothing else can.

No Valve software is in the result. `bannerlator-steam-install` fetches the client from Valve's
own CDN on the device at first use.

## `libblsession.so`

`LD_PRELOAD`ed into every session process, answering what the kernel or the app sandbox withholds:

| File | What it answers |
|---|---|
| `sysv.c` | System V IPC — Steam's semaphores |
| `robust.c` | `get_robust_list`, trapped by the app seccomp policy; Steam's cross-process mutex needs it |
| `drm.c` | `drmPrimeFDToHandle` and friends on the KGSL node, which cannot answer PRIME ioctls itself |
| `net.c` | The loopback port registry Steam's `lsof` peer check reads |

The `net.c` one is worth knowing about. Steam identifies the peer of its webhelper's websocket by
running `lsof -P -F upnR -i TCP@127.0.0.1:<port>`, which cannot work here — Android denies both
`/proc/net` and `NETLINK_SOCK_DIAG`. Every session process records the loopback sockets it binds,
connects and accepts, and that lsof is answered from the record. The client splits the `n` field on
`->` and requires **two** address halves, takes the source port from the left one, requires `u` to
be its own uid, and discards a record whose `R` is its own pid as a leaked fd.

## Publishing

The workflow uploads an artifact only. Publishing is a separate, explicit step: the tarball goes to
a release and `linuxfs.json` beside the other catalogs in `winlator-contents`:

```json
{
  "version": "r1",
  "url": "https://github.com/The412Banner/winlator-contents/releases/download/linuxfs-r1/linuxfs.tar.zst",
  "sha256": "<sha256 the workflow printed>",
  "size": 0
}
```

`LinuxRuntimeInstaller` reads that row, checks the sha256, unpacks to a staging directory and swaps
it in, so a failed install never leaves a half runtime behind.
