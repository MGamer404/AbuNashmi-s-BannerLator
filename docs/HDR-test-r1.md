# Bannerlator HDR test, round 1 (Wayland) — Galaxy Fold

Testing build of the **standard** app (`com.winlator.banner`), from the HDR round-1 branch
(`feat/wayland-hdr`). Install it over your current Bannerlator: containers, games and settings stay as
they are. It is not a Bannerlator release.

## What it does

- Games on the **Wayland** backend can ask for real **HDR10** output. It is **off** unless you switch
  it on for a game (below), and it only turns on when the screen reports HDR10. Your inner screen does
  (HDR10, HLG, HDR10+, 1351 nits, HDR/SDR headroom available).
- The game has to be **fullscreen**. It goes on its own display layer: *Zero-copy presentation*
  turns itself on for that game when HDR is on.
- While a game shows HDR, **screen effects and frame generation are skipped for it** (on purpose: both
  are SDR and would wash the picture out).
- This is **not** the "HDR" toggle in the drawer's screen effects (that one is a fake bloom filter).
  Leave it **off**.

## Setup (once)

1. Install the APK over the current app.
2. Pick a **DirectX 11 game with an HDR option in its own display/video settings** that runs on one of
   your **Wayland** containers (layer `Proton-11.0-2.1-arm64ec-9`). **God of War** is a good one.
   Other DX11 HDR games work the same way; DX12 (VKD3D) games may also work but are untested.
3. Open **that game's shortcut** settings → *Environment variables* (the shortcut, not the container,
   so no other game changes) and add:

   ```
   BANNER_WAYLAND_HDR=1
   DXVK_HDR=1
   ```

   `BANNER_WAYLAND_HDR=1` lets the compositor offer HDR10; `DXVK_HDR=1` makes the game see an HDR
   display (without it the game's HDR option stays greyed out).
4. *Settings → Log Manager*: keep **"DXVK & VKD3D"** logs on (default). Wine debug is optional.

## Run

5. Launch the game from that shortcut. In the game's own display/graphics options switch **HDR on**.
   If the game asks for a restart, exit and launch again.
6. Play **2–3 minutes somewhere bright** — sky, sun, fire, headlights, magic effects.
   Do not touch the drawer's Zero-copy switch, screen effects or frame generation during the test.
7. Optional A/B: switch HDR off in the game for ~30 s, then on again.
8. Leave with the drawer's **Exit**, so the session log writes its summary line.

## What to look for on screen

- **Working:** bright things (sun, lights, fire, sky) are clearly brighter than the white of the game's
  menus, with detail in them instead of flat white. The panel may brighten for a moment when HDR
  starts (Android switching the display into HDR). Colours look normal, not grey.
- **Not working:** the picture is **washed out / greyish / flat** (HDR frames shown as SDR), or very
  dark / oversaturated, or the game's HDR option stays greyed out.
- Screenshots cannot capture HDR. A photo of the screen is fine if you want to show something.

## Files to send back (put them in one folder, e.g. `Download/HDR-r1-fold/`)

| # | File | Why |
|---|---|---|
| A | `/sdcard/Download/Wayland-logs/wayland-<date>_<time>.log` — the newest one(s) from the test | the main proof (every `color` line) |
| B | the game's log folder `/sdcard/Download/bannerlator/<shortcut name>/` — the whole folder (`<exe>_dxgi.log`, `<exe>_d3d11.log`, e.g. `GoW_d3d11.log`, plus `wine_debug.log` / `vkd3d-proton.log` if present) | DXVK's own view of the swapchain |

(If you changed the location in the Log Manager, B is under that folder instead.)

## What the logs will say

Search the Wayland log for `color`. In order, a **successful** run shows:

| Line (abridged) | Means |
|---|---|
| `display  HDR capability of "Built-in Screen" … formats HDR10, HLG, HDR10+ … -- the display accepts HDR10` | the app read your screen |
| `color  HDR gate OPEN: BANNER_WAYLAND_HDR=1 (shortcut env) and "Built-in Screen" … reports HDR types HDR10, … Offering games HDR10 …` | HDR was offered to games |
| `color  zero-copy presentation turned on for this session …` (only if it was not on already) | the game gets its own display layer |
| `color  10-bit dma-buf formats AB30/XB30 advertised …` | 10-bit buffers allowed |
| `color  <game>.exe bound wp_color_manager_v1 version 1 …` | the game's Vulkan driver saw HDR10 |
| `color  "<game>" …: colour-management surface created …` | the game's swapchain asked for a non-sRGB colour space |
| `color  image description #N from <game>.exe: BT.2020, ST 2084 (PQ); mastering …; max CLL …, max FALL … -> ready` | the game asked for HDR10 (with its own brightness metadata) |
| `color  "<game>" …: image description #N now applies to its frames` | it took effect |
| `color  "<game>" … presents WxH buffers in XB30 (10-bit A2B10G10R10) …, gralloc RGBA1010102 (10-bit)` | 10-bit frames arrived |
| `layer  zero-copy: AHB swapchain from <game>.exe (… RGBA1010102 (10-bit) …)` | the game's own 10-bit buffers go to the display |
| `color  banner_wayland_game: dataspace BT2020_PQ (0x9c60000) set on the display layer …` | Android was told the frames are HDR10 |
| `color  HDR last 10 s: N frames on the display layer tagged BT2020_PQ …` | steady state, every 10 s |
| `color  display HDR/SDR ratio 2.xx (was 1.00; HDR frames on screen: yes …)` | **Android really showed HDR** (1.00 = SDR only) |
| `color  HDR on screen: yes - … and the display's HDR/SDR ratio rose to …` | the verdict (the **last** `HDR on screen:` line counts) |

DXVK's `<exe>_d3d11.log` / `<exe>_dxgi.log`: `Color space: VK_COLOR_SPACE_HDR10_ST2084_EXT` and a
`…A2B10G10R10…` format at swapchain creation.

**Failure cases, and the line that names each one:**

| What you'd see | Meaning |
|---|---|
| `HDR output off for this session (BANNER_WAYLAND_HDR not set) - this display lists HDR10 …` | the variable is not in the shortcut's environment (or the game was launched from another shortcut) |
| `HDR gate CLOSED: … cannot show HDR10 …` | the screen reported no HDR10 at launch |
| `DXVK_HDR=1 is not in the game's environment …` | add `DXVK_HDR=1` |
| gate OPEN, `bound wp_color_manager_v1`, but no `image description` line; verdict `no, because no program asked for HDR …` | the game never switched to HDR: its in-game HDR option is off (or it has none) |
| `image description …` but the buffers stay `XB24 (8-bit)` / no `dataspace BT2020_PQ` line | the game asked for HDR but its swapchain did not become 10-bit (send the DXVK logs) |
| `HDR frames of … go through the compositor's 8-bit SDR copy now, because …` + verdict `no, because … went through the compositor's 8-bit SDR copy (…)` | the frames could not reach the display layer; the reason is in the line |
| `dataspace BT2020_PQ … set` but ratio stays `1.00`; verdict `tagged but NOT confirmed …` | Android received HDR frames but did not show them as HDR (power saving, or HDR off for this screen in Android settings) |
| `screen effects are NOT applied to …` / `frame generation is NOT applied to …` | expected while HDR is on (not a failure) |
