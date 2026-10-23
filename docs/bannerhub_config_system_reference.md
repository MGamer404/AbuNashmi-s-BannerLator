# BannerHub Community Config System — full reference (for Bannerlator integration)

First-party system, entirely user-owned: **worker + KV store + GitHub repo**. This is the authoritative
reference for wiring Bannerlator's community-configs feature to the SAME backend. Sources read
2026-07-10: the worker `bannerhub-configs-worker.js`, `CLOUDFLARE_API_CONTRACT.md`, and the BannerHub
client (`BhSettingsExporter.java` = create/upload/apply, `BhGameConfigsActivity.java` = browse/social UI).
Where the contract .md and the worker code disagree, **the worker code wins** (noted inline).

- **Worker base:** `https://bannerhub-configs-worker.the412banner.workers.dev` (the SAME worker Bannerlator
  already calls for `/steam/search`).
- **Repo:** `The412Banner/bannerhub-game-configs` (worker commits to it via a server-side `GITHUB_TOKEN`).
- **KV binding:** `CONFIG_KV`.

## Bottom line for Bannerlator
No new backend, no new repo needed. Everything — list/download/upload/vote/comment/report/describe/delete
+ `app_source` namespacing — already exists. Bannerlator just calls these endpoints and stamps a distinct
`app_source`. The only real client work is **schema translation** (GameHub `pc_*` ⇄ Bannerlator), which we
already do on read (`ConfigTranslator`).

---

## Endpoints (method · path · body/query → response)

| # | Endpoint | Body / query | Returns |
|---|---|---|---|
| 1 | `GET /games[?refresh=1]` | — | `[{name,count}]` (from repo `games.json`, CI-rebuilt every 30 min; legacy bare-string array tolerated by client) |
| 2 | `GET /list?game=X[&refresh=1]` | — | config entries **with `votes`+`downloads`+`app_source` attached, sorted by votes desc then timestamp**; KV-cached 3 min (`cache:list:<game>`) |
| 3 | `GET /download?game=X&file=Y[&sha=Z]` | — | raw config JSON; **probabilistically** bumps `downloads:<sha>` (10% chance → +10, i.e. sampled estimate, not exact) |
| 4 | `POST /upload` | `{game, filename, content(base64), upload_token?}` | `{success, path, sha}` — commits to repo, tags `source:<sha>`, updates `recent.json`+`devices.json`, bumps `counts:<game>` |
| 5 | `POST /vote` | `{sha, game?, filename?}` | `{success, votes}` — **1 vote / IP / sha / 24h** (`voted:<ip>:<sha>` TTL 86400 — worker code; the contract .md's "7-day" is WRONG) |
| 6 | `POST /report` | `{sha}` | `{success, reports}` — 1 / IP / sha / 7d (`reported:<ip>:<sha>` TTL 604800) |
| 7 | `GET /comments?game=X&file=Y` | — | `[{text, device, date, ts}]` (may be empty) |
| 8 | `POST /comment` | `{game, filename, text, device}` | `{success}` — text ≤500 chars (`<>` stripped), device ≤60, max 200/config (oldest dropped) |
| 9 | `GET /desc?sha=Z` | — | `{text}` (uploader description, or "") |
| 10 | `POST /describe` | `{sha, token, text}` | `{success}` — token must match `token:<sha>`; text ≤500, `<>` stripped |
| 11 | `POST /delete` | `{sha, game, filename, upload_token}` | `{success}` — token-gated (KV `token:<sha>`, or fallback to `meta.upload_token` embedded in the file); deletes file + all KV keys |
| 12 | `POST /admin/delete` | `{game, filename, password}` | admin; `ADMIN_SECRET`; 5-fail/IP → 15-min lockout |
| 13 | `POST /admin/edit` | `{game, filename, content, password}` | admin |
| 14 | `POST /admin/purge` | `{password, app_source}` | **deletes ALL configs whose `source:<sha>.app_source` == given value** → `{deleted, skipped, errors}` |
| 15 | `GET /steam/search?name=X` | — | `{appid, name, cover}` (Steam storesearch proxy — Bannerlator already uses this) |

CORS `*` on everything; `OPTIONS` → 204.

### KV key scheme
`token:<sha>` · `votes:<sha>` · `downloads:<sha>` · `reports:<sha>` · `desc:<sha>` ·
`source:<sha>`=`{app_source,game,filename}` · `comments:<game>/<file>` (JSON array) ·
`voted:<ip>:<sha>` · `reported:<ip>:<sha>` · `counts:<game>` · `cache:list:<game>` (180s) · `cache:games`.

---

## Config file format (the `{meta, settings, components}` envelope)

Stored at `configs/<safeGame>/<filename>`; the worker base64-decodes `content` on upload to read `meta`.

### `meta`
| Key | Derivation (BannerHub) |
|---|---|
| `app_source` | **hardcoded** `"bannerhub"` (Lite writes its own, likely `"bannerhub_lite"` — CONFIRM before assuming). |
| `device` | `Build.MANUFACTURER + " " + Build.MODEL` (space-joined, raw). |
| `soc` | `detectSoc()` — **GPU-renderer string**, not a chipset. Order: `device_info/gpu_renderer` SP → `/sys/class/kgsl/kgsl-3d0/gpu_model` → `Build.SOC_MODEL` (SDK≥31) → `Build.HARDWARE`. |
| `bh_version` | app version constant (informational; never reject on mismatch). |
| `upload_token` | `Long.toHexString(new Random().nextLong() & Long.MAX_VALUE)` → **variable-length (1–16) lowercase hex, non-crypto**. Same token → file meta AND upload body. |
| `settings_count` / `components_count` | counts. |

### `settings`
BannerHub copies the **entire** `pc_g_setting<gameId>` SharedPreferences blob **verbatim** (no rename/filter).
Component-bearing keys (each value is a JSON string with a `name`/`displayName`):

| SP key | Component |
|---|---|
| `pc_ls_DXVK` | DXVK |
| `pc_ls_VK3k` | VKD3D (note casing) |
| `pc_set_constant_94` | Box64 |
| `pc_set_constant_95` | FEXCore |
| `pc_ls_GPU_DRIVER_` | GPU/Turnip driver (trailing `_` is part of the key) |
| `pc_ls_CONTAINER_LIST` | Wine/Proton container |
| `pc_ls_steam_client` | Steam client |

Also seen in `settings`: `pc_ls_boot_option` (cmdline), `pc_ls_environment_variable` (env), `pc_s_resolution_w*/h*`.
(These are exactly what Bannerlator's `ConfigTranslator` already maps → `[Extra Data]`.)

### `components[]`
`{name, url, type}` per entry — **only** when `banners_sources/url_for:<name>` is non-empty (a tracked
download; stock components are omitted). `url` is arbitrary (GitHub releases etc.), NOT a hardcoded CDN.
On IMPORT, BannerHub maps `type`→int (`DXVK 12 · VKD3D 13 · Box64 94 · FEXCore 95 · GPU 10`) for its own
`ComponentInjectorHelper` — a GameHub-only contract that does NOT apply to Bannerlator.

### Filename
`<safeGame>-<safeMfr>-<safeModel>-<safeSoc>-<unixSeconds>.json`, each field sanitized `[^a-zA-Z0-9_\-]→_`.
**5 fields** (SOC before the timestamp). The worker's `/list` parser derives device/soc/date from this.

---

## Client-side social dedup (SharedPreferences, local-only, no accounts)
- `bh_config_votes` — voted SHAs → button shows "Voted ✓", disabled. Reset on reinstall/clear-data.
- `bh_config_reports` — reported SHAs.
- `bh_config_uploads` — my uploads, keyed by `sha` → `{sha,game,filename,date,token}`; surfaces the token
  to enable describe/delete + the "My Uploads" screen. Token also recoverable from the file's `meta.upload_token`.
- Comments send `device = Build.MANUFACTURER + "_" + Build.MODEL` (underscore — differs from meta's space-joined `device`).

Extra data sources the client uses: `…github.io/bannerhub-game-configs/devices.json` (`{game:[{s,d}]}`, for
"matches your device" badges) and Steam storesearch for covers.

---

## Bannerlator integration plan (grounded in the above)

**Track 1 — detail page (BUILT, backend-free today).** Can immediately show REAL social data by switching
reads from raw GitHub → the worker:
- `GET /list?game=` → gives votes/downloads per config (replaces the current `CommunityConfigFetcher`
  contents-API listing; also gets 3-min caching + ranking for free).
- `GET /download?game=&file=&sha=` → the raw config (also counts a download).
- `GET /comments` + `GET /desc?sha=` → comments + uploader description in the detail view.

**Track 2 — votes/comments write.** `POST /vote` (dedup locally in a `banner_config_votes` SP, mirror
BannerHub's pattern) and `POST /comment` (`device = MANUFACTURER_MODEL`). Trivial once detail-page reads
go through the worker (we then have `sha`/`game`/`filename`).

**Track 3 — uploads (reverse of `ConfigTranslator`).** Emit a `{meta, settings, components}` file FROM a
Bannerlator shortcut, base64, `POST /upload`.

> **⚠️ SUPERSEDED where noted (decision 2026-07-10, reaffirmed 2026-07-11):** the "so BannerHub can also
> read our uploads" / "one shared repo, app_source separation" framing below is the OLD plan. FINAL
> architecture = a SEPARATE repo `bannerlator-game-configs` + worker **ns-routing** (`?ns=bannerlator`).
> BannerHub must NEVER see Bannerlator configs (asymmetric visibility); Bannerlator reads BOTH repos and
> merges; BannerHub reads only its own (never passes `ns`, code untouched). Uploads go ONLY to our repo.
> `app_source="bannerlator"` still stamped (purge/labels), but repo isolation — not app_source alone — is
> what hides our configs from BannerHub. See `project_bannerlator_bannerhub_config_crossuse` "🚀 STEP 3 PLAN".

**Rules for clean interop:**
1. **`meta.app_source = "bannerlator"`** (distinct value) so `/admin/purge` can manage our uploads
   independently and BannerHub's purge never touches them. NEVER write `"bannerhub"`.
2. Emit the GameHub `pc_*` keys (so BannerHub can also read our uploads) by reversing `ConfigTranslator`
   — DXVK→`pc_ls_DXVK`, VKD3D→`pc_ls_VK3k`, Turnip→`pc_ls_GPU_DRIVER_`, FEX→`pc_set_constant_95`, etc.,
   each a JSON string with a `name`. Do NOT dump Bannerlator's `[Extra Data]` keys verbatim (BannerHub
   can't read them).
3. Same filename shape (5 fields, unix seconds, `[^a-zA-Z0-9_\-]→_`).
4. `upload_token` = variable-length lowercase hex (match the format); store it locally keyed by returned
   `sha` for later describe/delete, and it's embedded in `meta` for recovery.
5. Do NOT emit `components[].url` pointing anywhere Bannerlator can't honor; prefer translating versions to
   OUR catalog on read (already done) rather than trusting foreign component URLs on apply.

**Isolation note:** all of the above is the user's own infra; there is no XiaoJi/third-party involvement,
no ToS/PII barrier. `app_source` tagging keeps Bannerlator and BannerHub data cleanly separable in one repo.

---

## Discrepancies corrected vs older notes
- Vote dedup is **24h per IP** (worker `voted:` TTL 86400), not 7 days.
- Downloads count is a **sampled estimate** (10%→+10), not exact.
- Filename is **5 fields** (SOC inserted), not the 4 the old REPORT.md shows.
- Upvotes/comments are **first-party on this worker**, not XiaoJi (earlier wrong assumption, now fixed).
