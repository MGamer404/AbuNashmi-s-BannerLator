# Spec — Per-Game Save Backup (iteration 2 on `feat/save-backup-restore`)

Status: DESIGN. Follows shipped v1 `bc7d4dc` (container-scoped) + `a8ddf7d` (skip frontend shortcuts).
Resolution strategy chosen by user (2026-07-05): **heuristic discovery → user confirm → persist mapping**.

## Goal
Let the user back up **one game's** saves out of a container, instead of the whole
`users/xuser` profile. Keep the existing whole-container path as an option. Output stays a
GameHub-compatible `.zip` so it restores in GameHub, other Winlator builds, and our own restore.

## Why v1 isn't enough
`GameSaveBackup.doBackup()` (`core/GameSaveBackup.kt:156`) walks the entire
`.wine/drive_c/users/xuser` profile into one zip named after the *container*. No game list, no
scoping. Titles that save outside the user profile (install dir, registry) are already out of scope
and stay out for this iteration.

## Chosen approach — heuristic + confirm + remember
1. **Discover** candidate save folders by scanning the container's known save roots and fuzzy-matching
   directory names against the game's identifiers.
2. **Confirm** — show candidates as a checklist with per-folder sizes; user ticks what to include and
   can add a folder manually.
3. **Remember** — persist the confirmed folder set per game so next time it's one tap (no re-scan/confirm
   unless the user edits it). Self-correcting: the mapping is the source of truth once taught.

~80% first-try hit rate expected; the persisted map fixes the tail.

## UX flow (extends the existing `SaveFlow` state machine in `ui/screens/ContainersScreen.kt`)
Current: overflow → `Backup / Restore save` → `Fork(back up | restore)` → format/source → SAF/confirm.

New branch under **Back up**:

```
Fork
 └─ "Back up this save"
     ├─ (NEW) BackupScope picker:
     │    • "This whole container"   → existing BackupFormat path (unchanged)
     │    • "A specific game ▸"      → GamePicker
     └─ GamePicker (list from ContainerManager.loadShortcuts())
          → SaveDiscovery (spinner; runs off-thread)
          → SaveConfirm (checklist of candidate folders + sizes + "add folder…")
          → (NEW) BackupFormat (choose target layout: GameHub vs Winlator-native)
             → backup(scopedRoots, format) → reuse UninstallResultBar for the result
```

The whole-container path funnels into the **same** `BackupFormat` step, so both scopes get the
format choice. This replaces v1's single-option `BackupFormat` dialog with a two-option one.

- Game list = `ContainerManager.loadShortcuts()` (`container/ContainerManager.java:216`), which returns
  `ArrayList<Shortcut>` with `name`, `path` (exe), `wmClass` (`container/Shortcut.java:22-28`). One row
  per game, cover art already available via `Shortcut.getCoverArt()`.
- If a persisted mapping exists for the picked game, skip discovery and jump straight to a pre-ticked
  `SaveConfirm` (with an "edit / re-scan" affordance).

## Save discovery heuristic (new `core/SaveLocator.kt`)
Scan **roots** relative to `drive_c/users/xuser`:

```
Documents/My Games          Saved Games            AppData/Roaming
Documents                   AppData/Local          AppData/LocalLow
```

For each root, examine child dirs at **depth 1 and depth 2** (depth 2 catches
`AppData/Roaming/<Publisher>/<Game>`). Score each dir name against three game identifiers —
`Shortcut.name`, exe basename (`path`), and `wmClass` — after normalization:

- lowercase; strip `®™©`; strip all non-alphanumerics; collapse whitespace.
- Score: exact normalized match = 100; one contains the other = 70; token-subset overlap ≥50% = 50;
  Levenshtein ratio ≥ 0.85 = 40. Keep candidates scoring ≥ 50.
- Rank by score; de-dupe nested hits (drop a child if its ancestor already matched).

Each surviving candidate carries its relative path (from `xuser`) and a computed byte size (cheap
recursive sum) for the confirm UI. Empty result → confirm screen shows only "add folder manually".

**Manual add:** SAF/dir chooser rooted at the container's `xuser` dir; the chosen path is stored
relative to `xuser`. Reject paths that escape `xuser` (same canonical-prefix guard already used on
restore, `GameSaveBackup.kt:71-85`).

## Persisted mapping (new sidecar — no mutation of GameHub-compat shortcut files)
`<container.rootDir>/app_data/save_maps.json`:

```json
{ "<gameKey>": { "name": "Elden Ring", "roots": ["Documents/My Games/Elden Ring",
                                                  "AppData/Roaming/EldenRing"] } }
```

- `gameKey` = `wmClass` if non-empty, else the `.lnk`/`.desktop` filename (stable & unique per shortcut).
- Written on confirm; read to pre-tick / skip discovery. Uninstalling the game does not auto-purge
  (harmless; can add later).

## Zip format / layout chooser (the "which tool is this for" step)
The file walk is identical across formats — the ONLY difference is how each entry name is rooted.
Two concrete targets:

| Format | Entry root written into the zip | For | Restores in |
|--------|--------------------------------|-----|-------------|
| **GameHub / Proton** (default) | `/drive_c/users/steamuser/<rel>` | GameHub, Proton-based tools | GameHub, our builds |
| **Winlator-native** | `drive_c/users/xuser/<rel>` (no leading slash) | sibling Winlator / WinNative / Bannerlator builds | any Winlator-lineage build, our builds |

Rationale for the two axes that actually matter:
- **User segment** — GameHub/Proton expect the profile under `steamuser`; native Winlator builds use
  `xuser` (`xenvironment/ImageFs.java:15`). Getting this wrong is what makes a game "not see" its save.
- **`drive_c` root prefix + leading slash** — GameHub roots every entry at `/drive_c/…`; the native
  variant drops the leading slash for a cleaner in-place import.

Both formats re-import into OUR builds either way: `remapForRestore` rewrites **any** non-`Public`
user segment to `xuser` (`GameSaveBackup.kt:113-118`), so `steamuser→xuser` and `xuser→xuser` both
land correctly. The format only changes *external-tool* compatibility.

Modeled as `enum class BackupLayout { GAMEHUB, WINLATOR }`; the two strings above are the only
per-format state (root prefix + user segment). Adding a third target later = one enum case.

## Backup engine change (`core/GameSaveBackup.kt`)
Add an overload:

```kotlin
fun backup(context, container, roots: List<String>?, gameName: String?,
           layout: BackupLayout, onResult)
```

- `roots == null` → whole-profile behavior (whole-container option keeps working).
- `roots != null` → walk **only** those subtrees under `xuser`. Reuse `isNoiseDir` (`:200`) to keep
  Temp/CrashDumps out even inside a scoped root.
- Entry name is built from `layout`: `"${rootPrefix}users/${userSeg}/$rel"` where
  `(rootPrefix, userSeg)` = `("/drive_c/", "steamuser")` for GAMEHUB or `("drive_c/", "xuser")` for
  WINLATOR. This replaces the hard-coded `PROTON_USER` constant at `:37`/`:184`.
- Zip filename: `<sanitize(gameName)>_<epochMillis>.zip` — matches GameHub's `<Game>_<epoch>.zip`
  convention that our restore's `gameNameFromUri()` already parses (`:48-52`), so a per-game backup
  round-trips with a clean game name in the restore confirm. (Filename convention is format-agnostic.)
- Empty scope (no files) → same "No save files to back up" result (`:192`).

## Restore interplay — no change needed
Restore (`doRestore`, `:67`) unzips whatever entries are present into `drive_c`, remaps
`steamuser→xuser`, Zip-Slip-guarded, skips frontend shortcuts (`isFrontendShortcut`, `:129`). A
scoped per-game zip is just a subset of entries → restores correctly with zero engine change. Whole
container and per-game zips are interchangeable on the restore side.

## Edge cases
- **Shared publisher root** (two games under `AppData/Roaming/<Publisher>`): candidates de-duped;
  if two games map to overlapping folders, back up copies the shared subtree into each game's zip
  (acceptable; note in UI if detected).
- **No detected saves:** confirm screen offers only manual add; if user backs out, no zip written.
- **Saves in install dir / `Program Files`:** out of scope this iteration (stretch: opt-in
  "also search the game's install folder" that scans the exe's dir for `*.sav`-like files).
- **Steam cloud `userdata/<appid>/remote`:** out of scope; would need AppID resolution via
  `store/SteamDatabase.java` (`app_id` lives there, not on the Shortcut). Future.
- **Registry-based saves:** out of scope (documented limitation, same as v1).

## Files
- NEW `core/SaveLocator.kt` — roots, normalization, scoring, size sums, sidecar read/write.
- EDIT `core/GameSaveBackup.kt` — scoped `backup()` overload + game-named zip.
- EDIT `ui/screens/ContainersScreen.kt` — `BackupScope` + `GamePicker` + `SaveDiscovery` +
  `SaveConfirm` states in the `SaveFlow` sealed class (`:228`); reuse progress + `UninstallResultBar`.
- strings.xml — new labels.

## Phasing / effort
- **P1 (core):** SaveLocator discovery + scoring + sizes; scoped backup overload; unit-testable pure logic.
- **P2 (UI):** BackupScope/GamePicker/SaveConfirm dialogs + manual-add SAF + sidecar persist.
- **P3 (polish):** pre-ticked skip-discovery on remembered games; overlap warning; empty-state copy.
Estimate: ~1 focused build cycle to green; medium diff (one new file + two edits).

## Test plan
- CI: build green (workflow_dispatch `build-artifacts.yml`), all three flavors.
- Device (root bridge): container with 2+ installed games →
  1. per-game backup of Game A, **GameHub format** → verify zip under
     `Downloads/Winlator/Backups/GameSaves/` named `<A>_<epoch>.zip`, contains ONLY A's folders,
     rooted `/drive_c/users/steamuser/…`, not B's. Repeat with **Winlator format** → same files
     rooted `drive_c/users/xuser/…` (no leading slash). `unzip -l` both to confirm the roots.
  2. wipe A's save in-container → restore that zip → A's save returns, B untouched.
  3. re-open backup for A → confirm pre-ticked from sidecar (no re-scan).
  4. game with saves in an odd folder → manual-add path → persists → one-tap next time.
  5. whole-container option still works unchanged (regression).

## Open (defer unless asked)
- AppID-keyed Steam cloud folders; install-dir save scan; auto-purge sidecar on uninstall;
  optional bundled Ludusavi manifest as a *second* resolver layered under the heuristic later.
