<p align="center">
<img src="https://github.com/The412Banner/Bannerlator/blob/main/logo.jpg?raw=true" width="1500" height="500" alt="Bannerlator" />
</p>

<p align="center">
<img src="https://img.shields.io/badge/release-3.1.6--pre1-ff3d8b?style=for-the-badge" alt="3.1.6-pre1" />
&nbsp;<img src="https://img.shields.io/badge/update-app--side%20%C2%B7%20no%20ImageFS%20reinstall-2dd4bf?style=for-the-badge" alt="app-side update" />
&nbsp;<img src="https://img.shields.io/badge/Headless%20Steam-new%20launch%20option-22c55e?style=for-the-badge" alt="Headless Steam" />
&nbsp;<img src="https://img.shields.io/badge/pre--release-beta-f59e0b?style=for-the-badge" alt="pre-release" />
</p>

# Bannerlator 3.1.6-pre1

Run Windows apps and games on Android — no PC and no root required.

**3.1.6-pre1 is the Headless Steam pre-release.** Steam games get a second online launch option next to SteamLite: **Headless Steam** runs the game on the app's own signed-in Steam session — no Steam client window, sign-in in under a second, cloud saves, stats and achievements on the real account — and it is the first build where a Steam game's **Find Servers** browser works on this path, at full speed. The browser lives in the new **v13 Proton layers** (the in-app catalog default from today). Also in this build: the **Box64EC** picker (an experimental second x86-64 translator next to FEXCore), a Headless Steam entry in the Log Manager, and a handful of launch fixes.

- **Headless Steam:** pick it in the launch popup for any Steam shortcut (Headless Steam / SteamLite / Goldberg / Raw). Needs the **v13 layers**.
- **Server browser:** Counter-Strike: Source lists thousands of servers, filters and joins — 316 to 376 fps while the list refreshes, matching SteamLite.
- **VAC:** Headless Steam is **not** VAC-protected (Valve's Android client has no VAC runtime). VAC-secured servers may still admit you; for a VAC session use **SteamLite**.
- **Box64EC (experimental, unofficial):** a selectable x86-64 translator on arm64ec containers, built by The412Banner from the box64 project's work-in-progress ARM64EC target.
- **Fixes:** a stray container folder no longer crashes the app at launch; a quick relaunch no longer reports "Steam client must be running".
- **Four APKs:** a new **AnTuTu** build (`com.antutu.ABenchMark`) joins standard, PuBG and Ludashi — same app, a different package name for devices that key performance modes on it.

<!-- update-summary: Bannerlator 3.1.6-pre1: Headless Steam, a second online launch option for Steam games on the app's own signed-in session (needs the new v13 Proton layers from the catalog) with a working server browser; Box64EC experimental x86-64 translator picker; Headless Steam log in the Log Manager; launch fixes. -->

> ✅ **Nothing to set up.** Update the app, then accept the **v13** layer update the catalog offers for the Proton 11 layers you use (a revert snapshot is kept). Headless Steam appears in the launch popup of Steam shortcuts once you are signed in to Steam in the app. Everything else you have — containers, SteamLite, Goldberg — is unchanged.

> 📘 **Guides:** [OpenGL games now run 4 to 5 times faster](https://the412banner.github.io/Bannerlator/fast-opengl/) · [LSFG Native Made Simple](https://the412banner.github.io/Bannerlator/lsfg-native-guide.html)

# What's New — everything since 3.1.5

## 🎮 Headless Steam: the app's own Steam session hosts the game

SteamLite runs Valve's Windows Steam client inside the container — the real thing, VAC and all, at the cost of a Steam client under translation. **Headless Steam** takes the other road: the app already holds a signed-in Steam session for your library, so a small native host (Valve's own Android Steam library, driven by the app) signs in on the account, and the game talks to it through the Steam bridge the Proton 11 layers have carried since v9. No Steam window, nothing to install in the prefix, and every Steam call the game makes runs natively instead of through translation.

- **Launch popup:** Steam shortcuts offer **Headless Steam** next to SteamLite, Goldberg and Raw; the choice is remembered per game like the others.
- **What works, device-proven:** sign-in in about a second, the game attached to the session (Steam sees you in-game), cloud saves, stats and achievements, multiplayer servers — Counter-Strike: Source was the test case, including a live match on a community server.
- **What it is not:** a VAC session. Valve's Android library contains only VAC *status* plumbing, no scanner — that only exists in the Windows and x86 Linux clients. Secure servers may still let you in, but you are not being VAC-scanned; for that, keep using SteamLite.
- **Relaunches:** a quick relaunch no longer trips over the previous session's lingering port; when the port really is busy (another app's Steam client — DroidDeck's Linux Steam uses the same one), the launch now stops with a clear card instead of starting the game without Steam.
- **Log Manager:** a **Headless Steam** toggle (same place and ? button as SteamLite) writes a per-game `headless_steam.txt` with a PASS/FAIL checklist — sign-in, bridge, session, server browser — plus the host, bridge and engine logs, redacted like the SteamLite one.

## 🔎 Find Servers works — the server browser

Valve's Android Steam library has no working in-game server browser: the moment a Source game asks for the server list it trips over an object that was never created and hangs. Inside our host it accepts the request and returns nothing. So the **v13 layers** carry their own:

- the game asks the bridge; the bridge asks the **app**, which fetches the master list through Steam's Web API with a token from your session (five thousand servers in about a second);
- the bridge **pings every server itself** (the same query protocol the PC client uses) and feeds the game its callbacks on the game's own thread;
- a small **x86-64 front** inside the layer answers the game's hottest lookups without leaving the emulator — the Source server browser re-reads server details tens of thousands of times a frame while sorting, and each read used to be a crossing into native code. With the front, the list refreshes at **316 to 376 fps**; before it, 0.

> ✅ **Tested** on the Pocket FIT with Counter-Strike: Source on Proton 11.0-2, 11.0-1 and GE 11.0-3 (v13): full lists, the three filter boxes, latency / location / anti-cheat filters, and joining a game.

## 📦 New compatibility layers — versionCode 13

All eight **Proton 11** layers (Proton 11.0-2, 11.0-1, GE-Proton 11.0-3 / 5 / 6 / 7 / 7.1, Proton-CachyOS) are rebuilt at **versionCode 13** with the server browser and its x86-64 front; the four x86_64 flavours are in the same release so the set stays together, but **they have no Steam bridge** (Headless Steam and the server browser do not apply to them) **and are unchanged** apart from the version number. The two Proton 10 layers stay at v12 — no Steam bridge there either. They install into a new coexisting `-13` slot; a container on an older build of the same layer is offered **Update layer → v13** with a revert snapshot. Release notes: [bionic layers v13](https://github.com/The412Banner/proton-wine/releases/tag/build-bionic-layers-20261004-v13).

## 🧬 Box64EC — an experimental second x86-64 translator (unofficial)

arm64ec containers gain an **Emulator (64-bit)** picker (container settings, and per game under Advanced → Box64EC) that switches the x86-64 translator between **FEXCore** (unchanged default) and **Box64EC**. The old picker is now labelled **Emulator (32-bit)**.

> ⚠️ **Box64EC components are experimental and unofficial.** They are built by The412Banner from the [box64](https://github.com/ptitSeb/box64) project's work-in-progress ARM64EC target and [airidosas252's pull request #4480](https://github.com/ptitSeb/box64/pull/4480), with our own hookup of the fast engine; they are **not** releases of the box64 project. Nothing installs by itself: the picker only appears once you install a Box64EC component from the catalog. Expect games that run slower or not at all; FEXCore remains the supported path.

## 📦 A fourth APK: AnTuTu

Every release now ships four builds of the same app under different package names — **standard** (`com.winlator.banner`), **PuBG** (`com.tencent.ig`), **Ludashi** (`com.ludashi.benchmark`) and, new, **AnTuTu** (`com.antutu.ABenchMark`). Take standard unless your device gives a specific package a performance mode; the in-app updater follows whichever one you installed.

## 🛠️ Fixes

- A malformed container folder (for example a leftover `xuser-` directory) crashed the app on every launch; it is skipped with a log line instead.
- Wayland HUD: a Vulkan game is no longer labelled "OpenGL"; the adapter's `icd.json` is written without escaped slashes.
- Headless Steam host: a `dlopen` failure path crashed instead of reporting the error.

## ⚠️ Carried over — still open

- **Headless Steam:** not VAC-protected (by construction — see above); Proton 10 layers have no Steam bridge; voice chat untested; only Counter-Strike: Source has been run through a full online session so far.
- **Server browser:** LAN tab scans the common Source ports by broadcast only; the Friends tab is empty on this path.
- **Box64EC:** experimental — no performance work yet beyond the fast-engine hookup.
- Everything listed as open in 3.1.5 is unchanged: Cutscenes / Codecs (FFmpeg arm64ec only, box64 fix pending upstream), X11 cursor items, save-sync device checks, Fast OpenGL's 32-bit-only setups, the Wayland items, and the Linux Steam client items from 3.1.3.

<details>
<summary><b>📦 Where to get Proton 9</b> (tap to expand)</summary>

The APK does not bundle Proton 9 — Wine installs from the in-app catalog on first run. The Linux Steam client does not use these layers at all: its games run through Valve's own ARM64 Proton.

**Proton 9.0 arm64ec** — not bundled, download it if you want it:

**https://github.com/The412Banner/Nightlies/releases/download/Proton/wine/proton-9.0-arm64ec.wcp**

66.7 MB · sha256 `f8a99fed387f1b097009129dc49368c8f500927640a9d4c7a192d850b1b2068c`
Install it via **Containers → download icon → install the `.wcp`**. It also appears in the in-app catalog. *(Compiled as a wcp by **Xnick417x**.)*

</details>

---

<details>
<summary><b>🙏 Credits</b> (tap to expand)</summary>

**The cutscene fixes** in the v12 layers: the Media Foundation series by **NelloKudo**; the media-converter gate by **GloriousEggroll**; the original shared-texture driver (`sharedgpures.sys`) by **Valve** (Derek Lesho), rewritten here for Wine 11; the bionic prefix packs and build sysroot by **GameNative**; `winedmo` from **Wine** (Rémi Bernon), with Proton's PCM filter (© 2025 Conor McCarthy for CodeWeavers) replaced by our own. Full per-layer credits are in the [v12 layer release](https://github.com/The412Banner/proton-wine/releases/tag/build-bionic-layers-20261003-v12).

**The Codecs setting** is modelled on **Pipetto-crypto**'s winedmo backport for Proton 9 (winlator `a81fae78`): the pseudo-components in the Win Components list and the `WINE_USE_DMO` name. No code was copied; the Compose UI and plumbing are new.

**box64** is **ptitSeb**'s; the GStreamer-wrapper fix is ours and submitted upstream as [#4528](https://github.com/ptitSeb/box64/pull/4528). **FEXCore** is the FEX-Emu team's; the 2609-unix package is the catalog build with its unixlib pair.

**XInput 2 raw mouse** follows what **GameNative** and **WinNative** already do in their X servers, extended with touch and stylus raw motion. **Colour cursors** came from a Discord #bugs report of black-and-white cursors in Kingdom Come and the Gothic Remake — thank you to the reporter.

Everything credited in 3.1.4 still applies: Fast OpenGL (Mesa, Banners-Turnip, Wine's EGL route), the Wayland adapter (Pipetto-crypto's bionic Vulkan wrapper), winhandler.exe (Winlator by **brunodev85**), the Turnip fix (Mesa / Turnip by the Freedreno and Mesa teams), the Linux Steam client (Valve, DroidDeck, WinNative, Armada, Termux proot, Arch Linux ARM, FEX-Emu), ntsync (**Joshua Tam / joshuatam**, GameNative), the bundled Wayland drivers (Vauzi-17, the WinNative development team, StevenMXZ, whitebelyash), DXVK, VKD3D-Proton, Wine, Proton / GE-Proton, box64, SteamKit2 and Wayland.

</details>

---

*Entirely app-side — no ImageFS reinstall. Install over 3.1.4 or any earlier 3.1.x; everything carries over. Then take the v12 layer update from the catalog.*
