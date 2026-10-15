# Track 1 — Community Config **Detail Page** (implementation setup)

Branch: `feat/community-configs-v1`. No backend, no XiaoJi contact. This is the "tap a config →
**Apply to game… | View details**" flow + a read-only details screen. Upvotes/comments are
explicitly OUT of scope here (that's Track 2 — a separate backend milestone; see bottom).

Target milestone label: **"3.8.0" config-browser expansion** (matching BannerHub's own 3.8.0). Per the
versioning hard rule the code lands as `2.6-preN` until told to cut; "3.8.0" is just the feature label
unless the user says otherwise.

---

## Verified data facts (checked live 2026-07-10)

- Config repo `The412Banner/bannerhub-game-configs` is a static export mirror.
- `games.json` = `[{name, count}]` only.
- Each per-device config file = `{meta, settings, components}`:
  - `meta` = `{app_source, device, soc, bh_version, upload_token, settings_count, components_count}`.
    **No vote/like/comment/rating/author fields anywhere** (scanned). `components` is often an empty list.
  - Upload **date** is NOT in meta — it's the trailing epoch in the filename
    (`…-Adreno__TM__740-1778449587.json` → 1778449587). `CommunityConfigFetcher.Fetched.fileName` carries it.
  - `settings` = the `pc_ls_*` / `pc_d_*` keys `ConfigTranslator` already parses.
- ⇒ The detail page renders **only data we already fetch**. Zero new network surface.

---

## Pieces to build (all additive; apply path unchanged)

### 1. Engine — `communityconfigs/CommunityConfigApply.kt`: add a non-mutating `preview()`
`apply()` currently mutates (`putExtra` + `saveData`). The detail page needs the **same diff without
persisting**. Refactor so the change-set is computed once and `apply` = `preview` + commit (avoid drift):

- Extract the body that computes `changed` / `missingComponents` / `missingDrivers` / `advisories` into a
  shared internal builder that takes a "commit: Boolean" (or returns a list of pending `putExtra`
  (key,value) ops + the result, and only `apply` executes them + `saveData`).
- New public `fun preview(shortcut, config, installed, containerWineVersion, isAdreno): ConfigApplyResult`
  — identical result shape, **reads only** (no `putExtra`, no `saveData`). `changed` lines describe what
  *would* change ("dxwrapperConfig.version → 2.7").
- Keep `apply()`'s external behavior byte-identical (regression-guard: existing on-device apply must be
  unaffected).

### 2. VM — `ui/screens/ShortcutsViewModel.kt`: expose fetch+translate (+optional preview) without applying
Mirror `applyCommunityConfig` (line 182) but read-only:

```
data class CommunityConfigDetail(
    val game: CanonicalGame,
    val device: CanonicalDevice,
    val fileName: String,
    val meta: ConfigMeta,          // parsed {app_source, device, soc, bh_version, uploadedEpoch}
    val config: ShortcutConfig,    // from ConfigTranslator.translate
    val preview: CommunityConfigApply.ConfigApplyResult?,  // non-null only when a target shortcut was given
)

fun loadCommunityConfigDetail(game, device, target: Shortcut?, onResult: (CommunityConfigDetail?) -> Unit)
```
- IO on `Dispatchers.IO`: `CommunityConfigFetcher.fetchForDevice(game, device)` → `ConfigTranslator.translate`
  → parse `meta` + epoch-from-fileName → if `target != null` also `CommunityConfigApply.preview(...)`
  (`InstalledComponents.read`, `isAdreno = GPUInformation.isAdrenoGPU`, `containerWineVersion` from target).
- `null` on fetch failure (offline / no file) → UI shows the same clean message as apply.
- Add a tiny `ConfigMeta` parser (epoch → date string; keep it dumb, no locale surprises).

### 3. UI — `ui/screens/ShortcutsScreen.kt`: action sheet + detail dialog
Two tap sites currently call `onApply(game, d)` **directly**:
- Browser: `CommunityDevicePanel` row button, line ~1719 (`onClick = { onApply(game, d) }`), plumbed via
  `CommunityCatalogBrowser(onApply=...)` (1442) ← `onApply = { g, d -> applyPicker = g to d }` (876).
- Per-shortcut sheet: device rows near line ~755 (`vm.selectCommunityGame` region).

Change both so the row tap opens a small **action chooser** first:
- `var configAction by remember { mutableStateOf<Pair<CanonicalGame, CanonicalDevice>?>(null) }`
- Chooser (AlertDialog or bottom action list): **"Apply to game…"** → existing path
  (`applyPicker = g to d` in browser / `chooseApplyTarget` in sheet) · **"View details"** →
  `detailFor = Triple(g, d, currentTargetOrNull)`.
- Preserve current behavior: "Apply to game…" keeps the target-picker + mismatch-warn flow untouched.

New `CommunityConfigDetailDialog(detail, onApply, onDismiss)`:
- **Header:** game name · `device` · `soc` · uploaded date · `bh_version` (source-app badge).
- **"What this config sets"** — from `ShortcutConfig`, in OUR component names (reuse the same mapping the
  apply summary uses): DXVK / VKD3D / Turnip driver / FEX preset / renderer / resolution / launch args /
  env vars. Advisory items (Proton/`wineVersion`) shown as advisory.
- **"Changes to «shortcut»"** (only when `preview != null`): render `preview.changed` (green/would-change),
  `missingComponents` + `missingDrivers` (needs-install), `advisories`. This is the pre-apply diff.
- **Apply button** → reuse `runCommunityApply(target, g, d)` (line 238) → sets `applyResult` → existing
  `SmartComponentInstallRow` / `SmartDriverInstallRow` install flow. So details never duplicates apply.
- **Gating:** add `detailFor` / `configAction` to the `communityDialogsGated` predicate (line ~720/951)
  so this layer participates in the ModalBottomSheet stacking fix (driver/content sheets stay on top).
- **Landscape:** reuse the two-column `BoxWithConstraints` pattern (`maxWidth >= 600.dp`) already used by
  `CommunityCatalogBrowser` — header/meta left, scrollable "what it sets" + diff right.

### 4. Strings
Add: "View details", "Apply to game…" (exists), "What this config sets", "Changes to %1$s",
"Uploaded %1$s", "From BannerHub %1$s", section labels.

---

## Reuse map (don't re-implement)
- Fetch+translate: `CommunityConfigFetcher.fetchForDevice` + `ConfigTranslator.translate` (VM line 190/196).
- Diff: new `CommunityConfigApply.preview` (shares apply's change-computation).
- Apply from details: existing `runCommunityApply` → `applyResult` → smart install rows.
- Landscape: existing `BoxWithConstraints`/two-column in `CommunityCatalogBrowser`.
- Dialog stacking: existing `communityDialogsGated` flag.

## Build / verify (when back online)
1. `ci-watch feat/community-configs-v1` → green. Watch the KDoc `/*` trap (past CI break).
2. `stage-apk feat/community-configs-v1 config-detail`.
3. On-device: tap a config → chooser appears → **View details** shows header + "what it sets" + (from the
   per-shortcut sheet) the diff vs that shortcut → **Apply** runs the real apply + install rows.
   Confirm "Apply to game…" path is unchanged. Landscape detail is two-column.

## Definition of done (Track 1)
Tap→chooser on both sites; detail dialog with provenance + settings-in-our-terms + pre-apply diff; Apply
reuses the existing engine; gated + landscape-correct; CI green; device-verified. No backend touched.

---

## Track 2 (NOT this task) — upvotes & comments
Data is NOT in the mirror; it's XiaoJi community-server data keyed by config id. Options recorded in
memory `[[project_bannerlator_bannerhub_config_crossuse]]`: (A) static vote-count snapshot in our repo via
scheduled Action (lowest risk, counts-first), (B) our own Cloudflare-Worker social layer (full backend,
cold-start), (C) live worker proxy of XiaoJi (ToS + PII risk). Comments carry PII/moderation exposure →
votes-first. Awaiting user's appetite decision before any of these.
