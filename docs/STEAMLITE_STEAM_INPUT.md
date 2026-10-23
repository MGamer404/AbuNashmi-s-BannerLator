# SteamLite — Steam Input support

**Status (2026-10-04):** implemented on `feat/steamlite-steam-input`, **not device-tested, not in any
hosted SteamLite package.** Test locally (CI-built `steam.exe` copied into a container's Steam dir)
before any repack/rehost — users must never receive an untested SteamLite update.

## Why

Games that read the pad through the **Steam Input API** (`ISteamInput` / `ISteamController` — No Man's
Sky, newer Valve-published titles, many "Steam Deck verified" ports) see **no controller** under
SteamLite: the genuine client's controller manager only applies a layout when the Steam UI tells it
to, and headless nobody does. XInput / DirectInput games are unaffected (they never ask Steam).

## What the agent does (`tools/steamlite-agent/steam_input.h`, gated on `WN_STEAM_INPUT=1`)

After logon and **before `LaunchApp`** (so the game's first `ISteamInput::Init` finds an active layout):

1. `SDL_InitSubSystem(joystick|gamepad)` on the client's `SDL3.dll` (loaded from the Steam dir if the
   client has not yet) — headless, the client never brings the joystick side up.
2. `IClientEngine::GetIClientControllerSerialized(hUser, hPipe)` and **pin** the returned object's
   vftable RVA to a client build whose slot layout was read from the binary (below). Unknown build →
   log and skip (`WN_STEAM_INPUT_UNPINNED=1` forces).
3. `EnableDeviceCallbacks(appid, 0)`, `EnumerateControllers(0, 0)`, wait ≤ 3 s for
   `GetNumConnectedControllers() > 0`.
4. `LoadConfigFromVDFString(0, appid, <layout>, 0, 0)` when the app passed a layout
   (`WN_STEAM_INPUT_VDF`), then `ActivateConfig(0, appid, true)`.
5. In the game-watch loop: re-activate on callbacks **2801** `SteamInputDeviceConnected_t` and **2804**
   `SteamInputGamepadSlotChange_t`; log 2802/2803; log controller-count changes for the first minute.

Nothing here can fail a launch: every step logs and returns.

## What the app does (`RealSteamLauncher`, `SteamInputLayout`, `LaunchMethodSheet`)

- Per-game toggle **"Steam Input"** in the launch popup (SteamLite rows), persisted as shortcut extra
  `steamInput` = `"1"`/`"0"`. Mutually exclusive with **Controller passthrough** (that one strips the
  client's HID hooks so classic DirectInput games read the pad directly — the opposite goal).
- On a SteamLite launch with the toggle on, `RealSteamLauncher.prepare` resolves a layout
  (`SteamInputLayout.resolve`) and writes it to
  `C:\Program Files (x86)\Steam\steamlite_controller_<appid>.vdf`, then sets `WN_STEAM_INPUT=1` and
  `WN_STEAM_INPUT_VDF=<that path>`.
- Layout order: the game's own `controller_<type>.vdf` beside its action manifest
  (PICS `config.steaminputmanifestpath`) → a Valve template from the package
  (`controller_base/templates/`, picked by `config.steamcontrollertemplateindex`: 2/12 gamepad-FPS,
  6 WASD, 4/5 twin-stick, else gamepad+mouse) → `gamepad+mouse`.

## Package changes needed (next SteamLite version)

| Addition | Why | Source |
|---|---|---|
| `SDL3.dll` (x86-64, Valve's build) | the client's controller backend; **absent from steamlite-v1** | Valve CDN `steam_client_win64` → `bins_win64` (zip after a 20-byte header: `tail -c +21`) |
| `controller_base/templates/*.vdf` (4 files) | first-layout seeds | Valve CDN `steam_client_win32` → `public_all` |
| new `steam.exe` | this feature | CI workflow `build-steamlite-agent.yml` |

The existing staging (`stageSteamLite`) already copies every package file and directory into the
prefix Steam dir, so no app change is needed for the new files.

## Slot table — how it was recovered

Public `IClientControllerSerialized` headers disagree with each other and with Valve's current
layout, so the slots were read off `steamclient64.dll` itself:

- The class's vftable comes from MSVC RTTI (`.?AVCSteamControllerSerialized@@` → TypeDescriptor →
  CompleteObjectLocator → vftable); its entries are forwarders (`mov rax,[rcx+0x10]; jmp [rax+N]`) so
  they carry no names.
- The method **names** live in an 8-byte-padded string pool after `"IClientControllerSerialized"`
  (155 names) — this is the **IPC map order, not vtable order**.
- Each name is referenced exactly once, in the client's **serialized-interface dispatcher**: a
  `switch` on a message hash whose case logs the name, unpacks the arguments and ends in the
  **virtual call it dispatches** — `call [rax+SLOT*8]` on the interface. Reading that call gives the
  slot; the argument unpacking gives the signature.
- Cross-check: the same procedure on a second client build (10.41.26.25) gives identical slots, and
  those are the slots another headless host built against that build uses.

| Method | slot | call |
|---|---|---|
| `IClientEngine::GetIClientControllerSerialized` | 66 (+0x210) | `(hUser, hPipe)` |
| `EnableDeviceCallbacks` | 10 (+0x50) | `(appid, 0)` |
| `GetNumConnectedControllers` | 22 (+0xb0) | `()` |
| `LoadConfigFromVDFString` | 38 (+0x130) | `(0, appid, const char* vdf, 0, 0)` |
| `ActivateConfig` | 40 (+0x140) | `(0, appid, true)` |
| `EnumerateControllers` | 102 (+0x330) | `(0, 0)` |

Pinned vftable RVAs for 10.52.09.55 (linked 2026-03-13, the steamlite-v1 build): **0x12f2360** = `IClientControllerSerializedMap` (the proxy class in-process callers actually receive — device-observed), 0x13172b8 = `CSteamControllerSerialized` (the implementation);
md5 `8a1a7892…`, the steamlite-v1 build); 0x12ee7e8 for 10.41.26.25 (2026-01-29). A new client build
must be re-read before its RVA is added.

## Local test recipe (before any repack)

1. `gh workflow run build-steamlite-agent.yml` on the branch → download artifact `steamlite-agent`.
2. Copy into the container: `steam.exe` → `<prefix>/drive_c/Program Files (x86)/Steam/steam.exe`
   **and** `imagefs/opt/steamlite/steam.exe` (staging re-copies from the latter on every launch);
   `SDL3.dll` beside it; `controller_base/templates/*.vdf` under `imagefs/opt/steamlite/`.
3. Launch a Steam Input API game via SteamLite with the toggle on; read the agent log lines
   `steam input: …` (vtable rva must match the pin, `GetNumConnectedControllers -> N`,
   `ActivateConfig … done`), then move the sticks in game.
4. Re-test one classic XInput game with the toggle **off** to confirm nothing changed.
