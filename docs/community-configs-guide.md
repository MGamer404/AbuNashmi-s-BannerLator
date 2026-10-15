# Community Configs & Accounts — the plain-English guide

*How Bannerlator's config-sharing and (optional) account system works, explained without the jargon.*

---

## The short version

Getting a Windows game to run well on Android usually means fiddling with a pile of
settings — which graphics translator to use, which driver, which resolution, what launch
options, and so on. When someone finds a combination that works great on their phone or
handheld, **Community Configs** lets them share it, and lets *you* grab it and apply it to
your own game in **one tap** — instead of guessing.

- **Browse** setups other people have shared, filtered to hardware like yours.
- **Apply** one with a single tap — it changes only that game, and only the settings the
  config includes, leaving everything else you've set alone.
- **Share** your own winning setup so others can use it.
- **(Optional) make an account** so your shared setups follow you to a new device and carry
  your name and picture. You never *need* one — everything works fine anonymously.

That's the whole idea. The rest of this guide walks through each part.

---

## 1. What a "config" actually is

A config is a small file that records **the settings one game was tuned with** — think of it
as a recipe card. It captures things like:

- the **graphics translator** and its options (DXVK / VKD3D / VEGAS and how they're set up),
- the **GPU driver** version,
- the **x86 translator** (FEXCore / Box64) and its preset,
- the **renderer**, **resolution / fullscreen** mode, **frame-generation** choice,
- **launch arguments** and **environment variables**,
- and dozens of the smaller per-game toggles.

It also remembers **which device it came from** (the phone/handheld model and its graphics
chip) so you can tell whether it's likely to work for you.

Two things it deliberately does **not** contain: your personal files, and any of your store
logins. It also leaves out the device-specific GPU driver tuning that shouldn't travel
between different phones. A config is just the recipe, nothing private.

---

## 2. Finding configs for your device

Tap the **globe button** in the Games header to open the catalog browser. From there you can:

- **Search** by game, and filter by **Steam** titles or by name.
- **Sort** by most-upvoted, name, or how many devices have shared for a game.
- Turn on **"Matches my device,"** which narrows the list to configs shared from hardware
  like yours — same graphics chip or SoC — so you're looking at setups with the best chance
  of working.

Each result is a card showing its **★ upvotes** and **↓ downloads** (best-rated first), the
**device and graphics chip** it came from, and the **date**. Popular, proven setups rise to
the top.

At the top of the browser you'll see a **"Your device"** line showing your device model and
its graphics chip/SoC — that's what the "matches my device" check compares against. If one
of those two can't be detected it simply reads **"Unresolved"** in that spot, so you always
know exactly what the app found.

---

## 3. Applying a config

Tap a config and choose **Apply**. Two things make this safe and easy:

- **It's a surgical merge.** Only the settings the config includes are written into your
  game's shortcut. Everything else you've already set is preserved — nothing is wiped or
  reset. If you apply it to a game that doesn't look like a match, the app warns you first.
- **It installs what's missing, if you want.** If the config needs a DXVK / VKD3D / FEXCore
  build or a Turnip GPU driver you don't have, Bannerlator offers to install it: an exact
  match is one confirm, otherwise you pick from the closest versions. Anything you already
  have is recognised and not re-downloaded, and the config auto-applies once the piece is in.

Nothing on your device changes until **you** tap Apply. Browsing is completely read-only —
your containers, your installed system image, and your existing settings are never touched
just by looking.

### The detail page

Before you apply, open a config's **detail page** to see:

- **where it came from** (device, graphics chip, app, date),
- a **plain-language list of what it sets**, in Bannerlator's own terms,
- a **before-you-apply comparison** against your current shortcut, so you can see exactly
  what would change,
- and its **description, upvotes, downloads, and comments** — where you can upvote and leave
  a comment of your own.

---

## 4. Sharing your own config

Found a setup that runs a game beautifully? Share it so others don't have to rediscover it.

From a game's shortcut you can **export and upload** its current settings as a config. The
export captures the full recipe — the graphics-translator setup (including every DXVK
option), the driver, the renderer, resolution, launch options, and the rest of the per-game
tuning — plus your device model and graphics chip so people can tell if it fits their
hardware. Give it a short description ("60fps, high settings, no stutter") and it's live for
everyone.

Uploading is **optional and anonymous by default.** You don't need an account to share, and
your upload doesn't carry anything private — just the game's settings and which device they
came from.

Bannerlator keeps its shared configs in **its own space**, separate from other apps'
libraries, so what you upload here stays within the Bannerlator community.

---

## 5. Managing what you've shared — "My uploads"

Everything you upload is listed under **My uploads** — reachable from the globe browser (the
👤 person entry) and from the navigation drawer. There you can:

- **expand** each upload to see its details,
- **edit its description** right inline, and
- **delete** it if you want to take it down.

If you're browsing without an account, this list lives on your device. That's where the
optional account system comes in.

---

## 6. The optional account system

**You never have to make an account.** Uploading, managing your uploads, browsing, applying,
upvoting and commenting all work perfectly fine anonymously. An account just adds three
conveniences on top:

1. **Your uploads follow you.** Sign in on a new phone or after reinstalling, and your shared
   configs — with the ability to edit and delete them — come right back.
2. **Your name on your work.** Configs you share can show *"by <your username>"* instead of
   "Anonymous user," so people can recognise setups from someone whose configs they trust.
3. **A profile picture.** Set an avatar and it shows on your shared configs and in the app.

### How signing up works — and why there's no email

Creating an account takes just a **username and a password**. There's **no email, no phone
number, nothing personal.** Because there's no email to send a reset link to, you instead get
a one-time **recovery key** when you sign up.

> ⚠️ **Save your recovery key.** It's shown once and is the *only* way to get back into your
> account if you forget your password (username + recovery key = back in). Bannerlator also
> writes a copy to a small file on your device — `Download/bannerlator/game-configs/my-account.json`
> — so it survives a reinstall, but keep your own copy somewhere safe too. There's no email
> reset — the recovery key is it. (You can also re-show it any time from the account screen
> while you're signed in.)

Your username is claimed once and is uniquely yours. Passwords are stored using strong,
industry-standard protection (they're never kept as plain text), and this whole system is
kept separate from — and has nothing to do with — your Steam/Epic/GOG/Amazon store logins.

### Where your avatar and account live

Your profile picture and account live on **Bannerlator's own first-party service** — the same
one that stores the shared configs. It isn't handed to any third party. The picture is only
used to show your avatar next to configs you've shared and in the app.

---

## 7. Voting, comments, and keeping the catalog useful

- **Upvote** the configs that worked for you — that's what pushes the good ones to the top.
- **Comment** to add tips or report results ("needed the newer driver, then perfect").
- Every config carries a **live description** from whoever shared it.

These are what turn a pile of files into a ranked, community-curated list you can trust.

---

## 8. Your privacy, in one place

- **Browsing changes nothing** on your device — it's read-only until you tap Apply.
- **A shared config contains only game settings + which device they came from** — never your
  files, never your store logins, never the device-specific driver tuning that shouldn't
  travel.
- **Accounts are optional, and email-free** — a username and password, with a recovery key
  instead of an email reset.
- **Everything runs on Bannerlator's own first-party service** — configs, accounts and avatars
  aren't sold or handed to a third party.

---

## Quick FAQ

**Do I need an account to use community configs?**
No. Browse, apply, share, manage your uploads, upvote and comment — all work anonymously. An
account only adds cross-device recovery, your name on your uploads, and a profile picture.

**Will applying a config mess up my other games or settings?**
No. It only touches the one shortcut you apply it to, and only the settings the config
includes. Everything else is preserved, and it warns you if the game doesn't look like a
match.

**I forgot my password and there's no email — am I locked out?**
Not if you kept your **recovery key** (shown once at sign-up, and saved to a file on your
device). Username + recovery key gets you back in and lets you set a new password.

**Is my Steam/Epic/GOG login connected to my community account?**
No — completely separate systems. The community account is just a username and password for
sharing configs.

**What if my device model or chip shows as "Unresolved"?**
It just means Android didn't report that one value on your hardware. It's cosmetic — matching
still works off whichever of the two (chip/SoC or GPU) was detected.
