# Bannerlator HDR test, round 2 (Wayland) — Galaxy Fold

Testing build of the **standard** app (`com.winlator.banner`), from the HDR branch (`feat/wayland-hdr`,
round 2). Install it over the round-1 build or your current Bannerlator: containers, games and settings
stay as they are. It is not a Bannerlator release. Use it with the **Wayland layer versionCode 10**
published next to it (it tells Windows your screen's real brightness; everything else here also works
on v9).

## Round 2e — what changed, and what to check first

**What changed**

1. **The app now asks for HDR brightness explicitly.** Android 15+ lets a surface ask the display for a
   given amount of HDR headroom; until now we never asked (Samsung boosts anyway — some other phones may
   only boost when asked). The game's display layer and, with frame generation, the screen surface now
   ask for *content peak ÷ 203 nits* (e.g. 1207 ÷ 203 ≈ 5.9×), capped at what the display says it can
   reach, and the request is cleared when HDR stops.
2. **The display's own ceiling** (Android 16+: "highest HDR/SDR ratio") is in the first log line and in
   the verdict. If a phone reports 1.00 there, no app can get an HDR boost on it — the log says so.
3. **How much of the screen the HDR layer covers** is logged (some phones only switch to HDR for a
   large enough HDR area).
4. **Cause wording:** "brightness at maximum" is only named for *manual* brightness at ≥ 250/255 (the
   Fold kept 3.00× at 255 *auto*). With no visible cause the line now says the phone may not boost HDR
   for apps, with what was asked and the display's ceiling.

**ROG Phone 9 Pro tester (headroom stayed 1.00 in 2d)**

- Run the AIO HDR card **fullscreen** (not windowed), once in **HDR10** mode and once in **SDR** mode.
- In HDR10 mode, look at the **400 / 600 / 1000-nit patches**: are they visibly brighter than the
  **203-nit** (paper white) patch? In SDR mode they will all look the same — that is the comparison.
- Send the log (`Download/Wayland-logs/wayland-*.log`). The lines that matter:
  - first line: `HDR capability of … | HDR/SDR headroom available (ratio 1.00), highest ratio X` — or
    `highest ratio not reported`;
  - `requested HDR headroom 5.9x on banner_wayland_game (content peak 1207 nits (max CLL) / SDR 203 …)`;
  - `banner_wayland_game: dataspace BT2020_PQ … covers N% of the screen (…)`;
  - `display HDR/SDR ratio …` — does it leave 1.00 now?
  - the verdict `HDR on screen: …`.

**Fold (regression, 1 minute)** — AIO HDR card fullscreen in HDR10: the ratio should still reach ~3.00,
with a `requested HDR headroom …x on banner_wayland_game` line; then Frame Generation 2× for 20 s: a
`requested HDR headroom …x on the screen surface (HDR10 swapchain for frame generation; …)` line and
HUD `HDR`.

## Round 2d — what changed, and what to check first

**What changed**

1. **Frame generation can now keep real HDR.** Round 2c's test E logged `the screen surface lists no
   HDR10 swapchain format (… 37/0 37/1000104001 …)` and tone-mapped. That was the compositor reading
   only the first 32 of the screen's format/colour-space pairs — your Fold lists every format once per
   colour space (11 each), so the 10-bit and FP16 rows, where HDR10 lives, were never looked at. Now
   every pair is read. If the screen offers HDR10, frame generation goes through an HDR10 swapchain
   (10-bit preferred, then FP16, then 8-bit) and carries the game's HDR metadata where the device
   supports `VK_EXT_hdr_metadata`.
2. **Frame generation works on the HDR picture in FP16** (16-bit float, the format lsfg-vk uses for
   HDR) where the engine can, instead of 8 bits — less banding in smooth gradients. If the engine
   can't build its chain in FP16, it restarts in 8 bits by itself (logged).
3. **Why headroom drops is now in the log.** Next to every no-headroom line, the 10 s HDR line and the
   final verdict: the phone's **thermal status** (and thermal headroom), the **brightness setting**
   (value and auto/manual), and — on Android 15+ — **screen recording started/stopped**, plus
   **screenshot taken** (Android 14+). No permission prompts (install-time permissions only).

**What to check** (AIO HDR test card, the light DX11 PQ scene, then God of War if you like)

- **Test E first:** Frame Generation 2× with the AIO HDR card. Expect the HUD line to stay **`HDR`**
  (not `HDR tone-mapped`), and in the log:
  - `screen surface lists N format/colour-space pairs (RGBA8 x11, …, A2B10G10R10 x11, RGBA16F x11);
    HDR-capable: A2B10G10R10/HDR10, …` — **send this line whatever it says**;
  - `screen swapchain built as HDR10 (format 64 A2B10G10R10, HDR10_ST2084) …` (or another format);
  - `compositor device enables VK_EXT_hdr_metadata …` and `HDR10 swapchain: the game's metadata set …`;
  - `frame generation runs on the HDR picture in FP16 (RGBA16F) …` and
    `generating: LSFG Native x2 at WxH (…, FP16: the HDR picture)`.
  If instead you still see `lists no HDR10 swapchain format among its N … pairs`, the list line above
  it says what the screen really offers.
- **Then look at the AIO card's banding strips** with frame generation on and off: FP16 through the
  engine should band no worse than without frame generation. Note what you see (a photo is fine).
- **God of War fading to no headroom:** play until it happens and send the log. The 5 s line now says
  e.g. `no HDR headroom for 5 s … likely the device is hot (thermal SEVERE) … [thermal SEVERE
  (headroom 1.02), brightness 180/255 manual]`, which tells heat, brightness and recording apart.

## What is new since round 1

1. **A real setting instead of environment variables.** *HDR output (HDR10)* in the container editor,
   in the game shortcut's settings and in XMB game settings (right under *Wayland game driver*). The
   shortcut's choice (*Use container default* / *On* / *Off*) wins over the container's. It applies
   **from the next launch** (the game is told about HDR when it starts). On a screen without HDR10 it
   is greyed out with the reason.
2. **`DXVK_HDR=1` is set for you** when HDR is on (a `DXVK_HDR` you set yourself is kept).
3. **A live switch in game:** drawer → *Graphics* → **HDR output**. It is only there when HDR is really
   open for the session. **On** = real HDR. **Off** = the same picture tone-mapped to SDR, straight
   away, no relaunch, the game's own HDR setting untouched. It lasts for this session only (the next
   launch starts On again).
4. **HUD line (round 2b):** the Fusion HUD shows the HDR state on **its own line, directly under
   `<latency> · Wayland`** (it no longer runs off the pill's edge):
   - **`HDR`** — HDR frames really on screen, with HDR headroom;
   - **`HDR (no headroom)`** — HDR frames on screen, but Android has given them no extra brightness for
     5 s or more (HDR/SDR ratio 1.00 — Android does this while the screen is being **recorded**, and
     possibly with the brightness slider at maximum);
   - **`HDR off`** — the drawer switch is off (tone-mapped to SDR);
   - **`HDR tone-mapped`** — the switch is on, but the frames are tone-mapped (frame generation on a
     screen that offers no HDR10 swapchain);
   - **`HDR ready`** — HDR is open for the session, no HDR frames on screen right now (game in SDR).

   Sessions without HDR show no such line (the HUD is exactly as before). The Full size has an `HDR`
   row under `DISP`, Tiles put it under *Display*, Minimal under its footer, Mega in its bottom band.
   The session log says the same, and its summary gives the share of the HDR time that really had
   headroom.
5. **HDR no longer switches off effects and the rest.** Screen effects, a window over the game, a
   windowed game and *Zero-copy presentation* off now **stay HDR**: the compositor composes the whole
   picture in 10-bit HDR and puts it on the game's display layer. With **frame generation** it depends
   on the screen: HDR if the screen surface offers an HDR10 swapchain, otherwise a correctly
   tone-mapped SDR picture (never washed out). Which one your Fold does is one of the things to find out.
6. **Brightness hand-off:** on a screen that lists HDR10, the session exports its brightness as
   Android reports it (`BANNER_WAYLAND_HDR_MAX_NITS`, `…_MAX_AVG_NITS`, `…_MIN_NITS`). Layer v10 tells
   Windows about it, so DXGI reports your screen's real peak (about 1345 nits on your inner screen)
   instead of a made-up 1499. Android gives no *live* brightness in nits; the log records the live
   HDR/SDR headroom instead.

## Setup (once)

1. Install the APK over the current app. Install the **v10 layer** `.wcp` from the same release and
   pick it for your Wayland container (*Wine version* in the container editor).
2. On the game's shortcut, **remove the round-1 variables** `BANNER_WAYLAND_HDR=1` and `DXVK_HDR=1`
   from *Environment variables*. (They still work, but `BANNER_WAYLAND_HDR` in the environment
   **overrides** the new setting either way, so leave it out for this test.)
3. Game shortcut settings → **HDR output (HDR10)** → **On**. Check that the text under it says it
   applies from the next launch.
4. *Settings → Log Manager*: keep **"DXVK & VKD3D"** logs on (default).
5. HUD on (drawer → *HUD*), default **Fusion** style (Pill size), so you can see the HDR line.
6. Screen brightness **around 70–80 %, auto-brightness off**, for the main tests (step H checks what
   happens at maximum). **Do not record the screen** during steps A–G: Android turns HDR headroom off
   while recording (the first round-2 run lost it the second the recording started, and the video is SDR
   anyway). Step H records on purpose.

Use a **DirectX 11 game with an HDR option**; **God of War** with **DXVK v3.1-gplasync** is the known
good one. Leave the drawer's "HDR" *screen effect* **off** unless a step says otherwise (that one is a
fake bloom filter, not HDR).

## Run the tests (one session, ~15 minutes, in this order)

Launch the game from the shortcut. In its own display options switch **HDR on** (restart the game if it
asks). Stand somewhere **bright** (sky, sun, fire, lights) and keep that view for every step.

| # | Do this | You should see | Log says (search `color`) |
|---|---|---|---|
| **A** | Nothing — just play 1–2 min | Bright highlights, normal colours. HUD line `HDR` under `… · Wayland` | `HDR gate OPEN: HDR output is on (the game's HDR output setting) …`, `dataspace BT2020_PQ … set on the display layer`, `display HDR/SDR ratio 2.xx` |
| **B** | Drawer → *Graphics* → **HDR output Off**. Wait 20 s. Then **On** again. Repeat once. | Off: highlights drop to SDR, colours stay correct (**not** grey/washed out), HUD `HDR off`, row says *"Off: HDR frames shown tone-mapped to SDR."* On: HDR comes back, HUD `HDR` | `HDR output switched OFF in the drawer …`, `tone-mapped picture for …: HDR output is switched off in the drawer …`, ratio back to `1.00`; then `HDR output switched ON …`, `… is back on its own display layer`, ratio up again |
| **C** | Drawer → *Graphics* → turn on **Sharpen (CAS)** or **FXAA** for 30 s, then off | Still HDR (bright highlights), the effect visible | `HDR picture for …: screen effects are on - the whole scene is composed into one 10-bit PQ BT.2020 picture …`, `effects  chain now works in 10-bit RGB …`, `HDR picture on its own display layer: …, 10-bit buffers, tagged BT2020_PQ` |
| **D** | Drawer → *Graphics* → **Zero-copy presentation Off** for 30 s, then **On** | Still HDR | `HDR picture for …: zero-copy presentation is off …`; on again: `… is back on its own display layer` |
| **E** | Drawer → *Graphics* → **Frame Generation 2×** (any engine that is offered) for 60 s, then off | **Either** still HDR (HUD `HDR`) **or** a correct SDR picture (HUD `HDR tone-mapped`). Both are fine — we need to know which | `screen surface lists N format/colour-space pairs (…); HDR-capable: …`, `HDR with frame generation for …`, then **either** `screen swapchain built as HDR10 (format 64 A2B10G10R10, HDR10_ST2084) …` + `frame generation runs on the HDR picture in FP16 …` **or** `the screen surface lists no HDR10 swapchain format among its N … pairs …: tone-mapped to SDR instead` |
| **F** | *(optional)* A window over the game: drawer → *Task Manager* → **New Task…** → `notepad` → OK; look, then close Notepad | Still HDR around the window; Notepad itself looks normal (white, not grey) | `HDR picture for …: a window is above the game …` — or no new line if your screen can show the window on a second layer (also HDR) |
| **G** | *(optional)* The game's own **windowed** mode, if it has one | Still HDR inside the window | `HDR picture for …: the HDR game is not one fullscreen window` |
| **H** | Brightness slider to **maximum** for 30 s on the bright scene, then back to ~70 %. Then a **20 s screen recording**, stopped | Recording: after ~5 s the HUD reads `HDR (no headroom)` and the drawer row says so; after stopping it returns to `HDR`. Maximum brightness: the same if Android also drops headroom there — if nothing changes, that is an answer too | `no HDR headroom for 5 s while HDR frames are on screen (display HDR/SDR ratio 1.00): the screen brightness is probably at maximum …`, then `HDR headroom is back: display HDR/SDR ratio …` |
| **I** | *(optional)* Same game with DXVK **2.4.1** instead of v3.1, relaunch | Note whether the game offers its HDR option (round 1 it did not; the cause is not known yet) | send the DXVK logs of this run too |

Some games minimise when another window takes focus (step F). If the game vanishes, close Notepad and
bring the game back from the drawer's window list.

Colour effects (brightness, contrast, gamma, saturation, the "HDR" bloom) work on the HDR signal in
step C and can look stronger than in SDR; that is expected. With frame generation (step E) the HDR
picture passes the frame-generation engine at 8 bits per channel, so smooth skies may band — tell us
if you see it.

End with the drawer's **Exit**, so the session log writes its summary line.

## Files to send back (one folder, e.g. `Download/HDR-r2-fold/`)

| # | File | Why |
|---|---|---|
| A | `/sdcard/Download/Wayland-logs/wayland-<date>_<time>.log` — the newest one(s) | every `color` line |
| B | the game's log folder `/sdcard/Download/bannerlator/<shortcut name>/` — the whole folder (`<exe>_dxgi.log`, `<exe>_d3d11.log`, `wine_debug.log` if present) | DXVK's view; the brightness DXGI reports |
| C | a note of what you saw at each step (A–I), especially **E** (HDR or SDR with frame generation) and **H** (did the HUD say "no headroom"?) | the picture itself cannot be logged |

## What the log will say

Near the start (every HDR session):

| Line (abridged) | Means |
|---|---|
| `display  HDR capability of "Built-in Screen" … formats HDR10, HLG, HDR10+ … -- the display accepts HDR10` | the app read your screen |
| `color  HDR gate OPEN: HDR output is on (the game's HDR output setting) and "Built-in Screen" … Offering games HDR10 …` | HDR offered because of the new setting (`BANNER_WAYLAND_HDR=1 (shortcut env var)` here would mean a leftover variable) |
| `color  compositor instance enables VK_EXT_swapchain_colorspace …` | frame generation *can* try an HDR10 swapchain |
| `color  session environment: DXVK_HDR=1 BANNER_WAYLAND_HDR_MAX_NITS=1351 BANNER_WAYLAND_HDR_MAX_AVG_NITS=… BANNER_WAYLAND_HDR_MIN_NITS=… - from "Built-in Screen" …` | DXVK_HDR set for you + the brightness hand-off |
| `color  <game>.exe bound wp_color_manager_v1 version 1 …`, `image description #N … BT.2020, ST 2084 (PQ) … -> ready`, `… now applies to its frames` | the game switched to HDR10 |
| `color  "<game>" … presents WxH buffers in XB30 (10-bit A2B10G10R10) …` | 10-bit frames |

Every 10 s while anything HDR happens:

`color  HDR last 10 s: N frames shown as BT2020_PQ (zero-copy a, 8-bit layer copy b, composed picture c, HDR10 swapchain d; last buffer …) | e tone-mapped to SDR | f washed-out copies | display HDR/SDR ratio x-y (now z)`

- `zero-copy` = the game's own 10-bit frames on the display (step A, and after each step ends).
- `composed picture` = steps C, D, F, G (HDR kept by composing).
- `HDR10 swapchain` = step E where the screen offers HDR10.
- `tone-mapped to SDR` = step B off, or step E where it does not.
- `washed-out copies` should stay **0** the whole session. If it is not, the line before it says why.
- `(now 1.00) - NO headroom while HDR frames are on screen: screen brightness at maximum, or the screen is
  being recorded …` = step H, a screen recording, or brightness left at maximum.

At the end: `color  HDR on screen: yes - N frames of "God of War" (GoW.exe) shown as BT2020_PQ (… 10-bit zero-copy, … 8-bit layer copy, … composed, … HDR10 swapchain; … more tone-mapped, … with HDR output off); the display's HDR/SDR ratio rose to X while they were on screen (1.00 = SDR only); headroom above 1.00 for P% of the HDR time (a of b s), now R` — the last `HDR on screen:` line is the verdict. A low P% with brightness below maximum is worth telling us about.

**Failure cases, and the line that names each one:**

| What you'd see | Meaning |
|---|---|
| no *HDR output* row in the drawer | HDR is not open this session: look for `HDR gate CLOSED: …` or `HDR output off for this session …` |
| `HDR output off for this session (the HDR output setting is off) …` | the setting is off for this game (check the shortcut's choice, then the container's) |
| `HDR output off for this session (BANNER_WAYLAND_HDR=0 in the shortcut env var overrides the setting) …`, or `HDR gate OPEN: BANNER_WAYLAND_HDR=1 (shortcut env var) …` | a leftover environment variable is deciding instead of the setting — remove it |
| `HDR gate CLOSED: HDR output is on (…) but this display cannot show HDR10 …` | the screen did not report HDR10 at launch |
| picture grey / washed out, `washed-out copies` > 0 | `HDR frames of … go through the compositor's 8-bit SDR copy now, because …` names why — send the log |
| `the composed picture could not go on the game's display layer …` | the composed picture fell back to the screen swapchain (still correct, but tell us) |
| `HDR output is switched off, but …'s HDR frames cannot be imported …` | the switch cannot tone-map this game's frames; they stay HDR (expected on some drivers) |
| ratio stays `1.00`, verdict `tagged but NOT confirmed …` | Android got HDR frames but gave them no headroom the whole time (brightness at maximum, power saving, or HDR off for this screen in Android settings) |
