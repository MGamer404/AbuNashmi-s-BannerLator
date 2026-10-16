# AbuNashmi's BannerLator

**The original [BannerLator](https://github.com/The412Banner/Bannerlator) at 3.1.7-pre2, plus five changes** — the Android app that runs Windows games and apps, with a handful of extra features for Steam.

These five changes were also sent to the original project as pull requests: [#613](https://github.com/The412Banner/Bannerlator/pull/613), [#614](https://github.com/The412Banner/Bannerlator/pull/614), [#615](https://github.com/The412Banner/Bannerlator/pull/615), [#616](https://github.com/The412Banner/Bannerlator/pull/616), [#617](https://github.com/The412Banner/Bannerlator/pull/617).

> **Please read before installing**
>
> - 🤖 **These changes are 100% "vibe coded".** Every change was written by an AI (Claude) from plain-language requests. The person behind it has **absolutely no coding knowledge**, can't review the code, and can't answer technical questions about it.
> - 🧪 **Tested lightly, on one phone.** The changes were only tried on a OnePlus 15 (Snapdragon 8 Elite Gen 5), on top of 3.1.7-pre2. Other devices and GPUs are untested. Expect rough edges.
> - 🚧 **It will most likely not be maintained.** There is no roadmap and no promise of bug fixes, updates or answers to questions. Use it as-is.
> - If you want the actively developed version, use the original: **[The412Banner/Bannerlator](https://github.com/The412Banner/Bannerlator)**.

This project is **not affiliated with** the original BannerLator developer, Valve/Steam, or anyone else it builds on.

### ☕ Support the original developer
If you enjoy this app, please support the person who actually built it. **[The412Banner's Ko-fi](https://ko-fi.com/the412banner)** is the **original BannerLator developer's** page, **not mine**. This fork's author takes no donations and has nothing to do with that page; it's linked here only because the original developer deserves the credit and the support.

---

## What this adds

Everything else in the app is exactly BannerLator 3.1.7-pre2. The changes are only these:

### 👥 Switch between Steam accounts
Works like the real Steam client. Every account you sign into is remembered. Tap **Change account**, pick one from your list, and you're in — no username or password again. You can also add another account or remove one from the list. If you're updating from an earlier version, your current login carries over and you stay signed in. Switching is blocked while a download is running or a game is open. If you launch a game with SteamLite or Headless Steam while signed in as the wrong account, the app offers to switch first. The sign-in screen also lists your saved accounts.

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
- It now tells you with a message when Goldberg couldn't be applied, instead of failing silently. A patch that fails halfway is undone, so a game is never left half patched.
- Launching a game with **SteamLite** or **Headless Steam** puts its original files back first, so the Goldberg picker no longer has an **Off** option.
- Every Goldberg launch writes a small report, `goldberg_diag.txt`, into `Documents/bannerlator/<game name>/` on your phone, which helps when something goes wrong.
- **Goldberg launches don't sync Steam Cloud unless you switch it on** (**Save Manager → "sync with Steam Cloud during Goldberg launches"**, off by default). Achievements you earn are saved on the phone and sent to Steam when that same game is next played with **SteamLite** or **Headless Steam**. Those two modes still sync as before.

---

## Install

1. Download the APK from the **[Releases](../../releases)** page.
2. Needs **Android 8.0+** on a **64-bit ARM** phone.
3. It uses the **same app, package and signing key, and the same version number (100)** as the original's 3.1.7-pre2, so this APK and the original's pre-release can be installed over each other without losing your data. It also installs as a normal update on top of 3.1.6. Going back to 3.1.6 needs an uninstall first, which wipes your data. **Back up anything important first.** Don't uninstall to "fix" an install problem — that wipes your data.

Only the standard version is built here (not the Ludashi / PuBG / AnTuTu variants).

> ℹ️ **About the update check.** The app's built-in update check is the original's, untouched, so it may offer the original's newer builds. Installing one replaces the extras from this repository.

> 🔐 **Only download APKs from this repository's Releases page.** The app is signed with the same publicly known Android test key as the original, which means anyone could make a look-alike APK that installs over it. Don't install copies from anywhere else.

## Known limits
- Games installed in your own library folders don't appear inside the **Linux Steam client** mode.
- Not every part has been tested on a real device. If something misbehaves, the original BannerLator is the safe fallback.

## Credits and license
This is **[BannerLator](https://github.com/The412Banner/Bannerlator)** by The412Banner with five changes on top, and it stands on a long chain of work: **Winlator** (brunodev85) → cmod → Bionic → Star → Ludashi, plus GameNative, lsfg-vk, gbe_fork / Goldberg, DXVK and many others. **All the credit for the app itself belongs to them.** The full credits are in [`docs/UPSTREAM_README.md`](docs/UPSTREAM_README.md) (the original README, kept as-is, including the complete feature list) and [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md).

The changes here were written with [Claude](https://claude.ai) (Anthropic).

Licensed under **[GPL-3.0](LICENSE)**, like the original. This is an unofficial community project; use it only with games you own. Steam rating data comes from Steam's public store pages.
