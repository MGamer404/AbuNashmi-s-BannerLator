# Bannerlator — Combined Theming + Drawer-Rebuild Plan

_Reconciles the 2026-06-30 theme-centralization plan (recolor only) with the
2026-06-30 drawer-rebuild request (new icons + UI restyle). Supersedes both._

## What changed vs. the original theme plan
| Original theme-centralization rule | Now |
|---|---|
| Default stays **byte-identical** to 2.1.1 | **Dropped for the drawers** — the rebuilt look ships as the new default |
| **No icons, no UI rebuild** (colors only) | Icons + button restyle added, concentrated on the drawers + main screens |
| Typography ramp + light mode **CUT** | Still **CUT** (keep close to original) unless you say otherwise |
| Stores out of scope | Still **out** |

## Two axes (keep them separate — different cost)
- **Axis 1 — Colour follows the theme.** Route hardcoded colours onto `MaterialTheme.colorScheme.*` (Compose) or feed the accent via `getCurrentAccentArgb()` (native/legacy). **Broad + mechanical** — this is what makes *every* menu/submenu/dialog recolour.
- **Axis 2 — Rebuild (icons + button restyle).** Per-surface hand-work. **Concentrated** on high-traffic surfaces, not every obscure dialog.

## Coverage map (every surface, how each is treated)
| Bucket | Surfaces | Axis 1 (colour) | Axis 2 (rebuild) |
|---|---|---|---|
| **A. The two drawers** | App side-nav `AppDrawer.kt`; in-game 6-tab `XServerDrawer.kt` | ✅ | ✅ icons + restyle |
| **B. Drawer-spawned dialogs** | In-game Task-Mgr confirm / New Task / Bring-to-Front / MoreVert dropdown / profile settings; app-side About / Help | ✅ (native ones need manual/Compose conversion) | ⚠️ light touch (icons on actions) |
| **C. App-side screens + sheets** | Settings, Containers, File Manager, Appearance, container/shortcut editors, download sheet (~336 literals) | ✅ | ➖ optional, later |
| **D. Native / legacy** | perf HUD, on-screen touch controls, legacy XML editor activities | ✅ via accent bridge | ➖ no |

## Phases (build order)

**Phase 0 — Foundation (already in flight).**
Branch 1 in-game colour centralization = ✅ device-proven. Follow-up `96ed50e` (PrimaryDim fix + Appearance nav entry) → CI `28431784626` still owes its own device test. Everything below stacks on this.

**Phase 1 — Drawer rebuild (tonight's preview).**
- `AppDrawer.kt`: centralize its local colour consts onto colorScheme **+** new icon set (Games→gamepad, Appearance→palette, distinct store/gpu/layers/folder) **+** restyle (section grouping, accent gradient + glow on selected). Knocks out bucket C for this one file too.
- `XServerDrawer.kt`: layer icon refresh (Graphics rail→display, etc.) + button restyle (scaling / frame-gen / toggles / HUD chips fill from accent) onto the already-centralized colours.
- New vector drawables (palette done; need games + display).
- **Default is now the rebuilt look.**

**Phase 2 — Drawer dialogs (bucket B).**
Theme + light icon pass on the popups the drawers spawn. Flag: some in-game dialogs are **native ContentDialogs**, not Compose — they don't recolour for free and may need Compose conversion (see Task-Manager native-dialog history). App-side About/Help are already Compose AlertDialogs → recolour cheaply.

**Phase 3 — App-screen colour sweep (bucket C = old Branch 2).**
Centralize the ~336 literals screen-by-screen (Settings 77 first, then Contents/InputControls/DownloadSheet/FileManager/Splash/Shortcuts/editors) so every screen + its dialogs follow the theme. Mechanical, one branch per cluster for easy rollback. Colour-follow primary; icon touch-ups optional.

**Phase 4 — Native / legacy (bucket D).**
Feed the accent to perf HUD (`FrameRating.java` …), on-screen touch controls (`InputControlsView.java`), and legacy XML editors via `getCurrentAccentArgb()`. Manual — can't use colorScheme.

**Phase 5 — Optional presets (old Branch 3, additive).**
Midnight Cobalt + Phosphor. Default already changed in Phase 1, so no special handling.

## Verify gate (every phase)
CI green → device test via the root bridge. Gate is now **"looks right + wiring intact"** (End Process / Bring to Front / Exit / launch / controller still work) — **not** byte-identical. Save memory + progress log + commit **before** each on-device test (same-device OOM rule).

## Result
After Phase 3+4, **every** Compose menu/submenu/dialog (app + in-game) recolours with the theme, native/legacy surfaces follow via the accent bridge, and the **rebuild** (new icons + restyled buttons) lands on the two drawers + main screens. That is the full "all menus, submenus, and dialogs themed, both sides" outcome.
