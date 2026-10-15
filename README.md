# AbuNashmi's BannerLator

**A personal fork of [BannerLator](https://github.com/The412Banner/Bannerlator) 3.1.6** — the Android app that runs Windows games and apps — with a handful of extra features for Steam.

> **Please read before installing**
>
> - 🤖 **This fork is 100% "vibe coded".** Every change was written by an AI (Claude) from plain-language requests. The person behind it has **absolutely no coding knowledge**, can't review the code, and can't answer technical questions about it.
> - 🧪 **Tested lightly, on one phone.** Everything here was only tried on a OnePlus 15 (Snapdragon 8 Elite Gen 5). Other devices and GPUs are untested. Expect rough edges.
> - 🚧 **It will most likely not be maintained.** There is no roadmap and no promise of bug fixes, updates or answers to questions. Use it as-is, or fork it and make it your own.
> - If you want the actively developed version, use the original: **[The412Banner/Bannerlator](https://github.com/The412Banner/Bannerlator)**.

This project is **not affiliated with** the original BannerLator developer, Valve/Steam, or anyone else it builds on.

### ☕ Support the original developer
If you enjoy this app, please support the person who actually built it. **[The412Banner's Ko-fi](https://ko-fi.com/the412banner)** is the **original BannerLator developer's** page, **not mine**. This fork's author takes no donations and has nothing to do with that page; it's linked here only because the original developer deserves the credit and the support.

---

## What this fork adds

Everything else in the app is exactly BannerLator 3.1.6. The changes are only these:

### 👥 Switch between Steam accounts
Works like the real Steam client. Every account you sign into is remembered. Tap **Change account**, pick one from your list, and you're in — no username or password again. You can also add another account or remove one from the list. If you're updating from the original 3.1.6, your current login carries over and you stay signed in. Switching is blocked while a download is running, so nothing gets mixed up.

### 📁 Extra game library folders that work like real Steam
Add your own folders as game libraries (for example a **Games** folder). The app builds a proper Steam **`steamapps`** folder inside it, the same layout every Steam client uses, so **other apps and emulators can use the same games without downloading them again.**
- When you hit **Download**, you choose where to install: the default location or one of your folders (free space is shown).
- If a `steamapps` folder is already there, the app **builds on it instead of resetting it**. Games that are already installed are found and shown as installed, and existing files are checked and reused rather than downloaded again.
- Moving an installed game between locations is supported.

### 🔎 Search your library
A search bar in the Steam **Library** tab that finds games by name as you type. It ignores capital letters and accents, and doesn't care about word order.

### ⭐ Sort your library by Steam rating
A sort button next to the search bar: **A–Z** (the default) or **Steam user rating**, best first.
- Games are grouped under Steam's own rating names (for example *Overwhelmingly Positive*, *Very Positive*, *Mixed*) with a header and game count for each.
- A game needs **at least 500 reviews** to be ranked. Games with fewer reviews (or none) go to the bottom group.
- Ratings are downloaded in the background after you open the Library, so the first time it can take a few minutes for a big library. Each game card also shows its rating.

### 🕹️ Goldberg (offline Steam emulator) fixes
- Goldberg is **more reliable to launch**. Shortcuts created from the Steam **Library** tab or the **Downloads** screen now remember which Steam game they belong to; before, they didn't, and Goldberg was silently skipped, which could end in a black screen.
- It now tells you with a message when Goldberg couldn't be applied, instead of failing silently.
- Every Goldberg launch writes a small report, `goldberg_diag.txt`, into `Documents/bannerlator/<game name>/` on your phone, which helps when something goes wrong.
- **Goldberg launches are fully offline.** No Steam Cloud save sync, before or after the game. Achievements you earn are saved on the phone and sent to Steam the next time you use **SteamLite** or **Headless Steam** (only if the "sync unlocks to my Steam profile" option is on). Those two modes still sync as before.

### 🔕 No update nags
The built-in "update available" check is turned off, because it would otherwise point at the original project and could replace this version. To update, download a newer APK from the [Releases](../../releases) page.

---

## Install

1. Download the APK from the **[Releases](../../releases)** page.
2. Needs **Android 8.0+** on a **64-bit ARM** phone.
3. It uses the **same app name, package and signing key as the original BannerLator**, so it installs **as an update on top of 3.1.6 and keeps your containers and data**. Still, **back up anything important first.** Don't uninstall the original to "fix" an install problem — that wipes your data.

Only the standard version is built here (not the Ludashi / PuBG / AnTuTu variants).

> 🔐 **Only download APKs from this repository's Releases page.** The app is signed with the same publicly known Android test key as the original, which means anyone could make a look-alike APK that installs over it. Don't install copies from anywhere else.

## Known limits
- Games installed in your own library folders don't appear inside the **Linux Steam client** mode.
- Not every part has been tested on a real device. If something misbehaves, the original BannerLator is the safe fallback.

## Credits and license
This is a fork of **[BannerLator](https://github.com/The412Banner/Bannerlator)** by The412Banner, which stands on a long chain of work: **Winlator** (brunodev85) → cmod → Bionic → Star → Ludashi, plus GameNative, lsfg-vk, gbe_fork / Goldberg, DXVK and many others. **All the credit for the app itself belongs to them.** The full credits are in [`docs/UPSTREAM_README.md`](docs/UPSTREAM_README.md) (the original README, kept as-is, including the complete feature list) and [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md).

The changes in this fork were written with [Claude](https://claude.ai) (Anthropic).

Licensed under **[GPL-3.0](LICENSE)**, like the original. This is an unofficial community project; use it only with games you own. Steam rating data comes from Steam's public store pages.
