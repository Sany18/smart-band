# Community resources (non-GitHub)

These tools/sites are part of the same community ecosystem (loosely centered
around the **m0tral** modding group and their Telegram channels) but are
**not hosted on GitHub**, so — unlike the repos linked from the top-level
README — links alone aren't reliable. Their infrastructure has already been
observed to be geo-blocked from at least one country (Ukraine) without a VPN,
so files that matter are kept here alongside the link.

## miwatch.de/appView — community RPK/watchface directory

A submission-based directory of third-party RPK apps for this device family:
<https://miwatch.de/appView>

- Frontend is a small JS app hitting its own API at `miwatch.de/appViewData`
  (optionally `?genId=<id>`, where `367` = Band 9 Pro — see the `<select
  id="modelFilter">` on the page for the full id list).
- **Every download URL returned by that API points to `www.conversmod.ru`**
  (confirmed: same IP, `62.113.109.51`, for all ~116 listed apps as of this
  writing). That host was **completely unreachable** (connection timeout) from
  both a Mac and an Android phone on a Ukrainian ISP, with and without a
  generic VPN exit — but reachable once routed through a VPN server outside
  the blocked region.
- [`sample-apps/calculator-sample.rpk`](sample-apps/calculator-sample.rpk) is
  one such app (a simple calculator, by a third-party developer, package id
  `com.neoruaa.calculator`), kept here as a working example of the RPK format
  since the origin host may not always be reachable. See
  [`../protocol-notes/`](../protocol-notes/) for what's inside it.

## EasyFace (the actual tool, not just the GitHub README)

The [GitHub repo](https://github.com/m0tral/EasyFace) is documentation-only.
The real Windows compiler (`EasyFace_setup.msi` + a required
"Gen2 Compiler" update zip for post-2021 devices like this one) is distributed
via a Google Drive folder linked from that README:
<https://drive.google.com/drive/folders/1mtKY4VPxt7iCHEGvDxTw743v9ZUAG20N>

Not mirrored here (Windows binary, unclear redistribution rights). Needs the
VC++ 2015-2022 x86 redistributable installed to run
(`mfc140u.dll not found` if missing — a common gotcha, fixed by installing/
repairing the **x86**, not x64, redistributable).

## m0tral mod APKs (modified Mi Fitness / Mi Health)

Distributed via Telegram, not GitHub or Google Drive:
- Bot: `@mi_watch_bot`
- Channels: `@mi_watch_int` (English), `@mi_watch_news`

Not mirrored here — these are full repackaged/modified builds of a proprietary
Xiaomi app (~140MB), which isn't appropriate to redistribute from a notes repo
regardless of source. If you want one, get it from the bot directly.

Context for why you might *not* want it: as of the versions tested
(`3.33.6i` builds `v139u`/`v142`), the mod's headline "Force English language"
feature for the band itself was found to be **dead code** — present in
strings/handlers but never wired up to any screen in either build. The one
functional difference from the official app is that `v139u` fixed a bug where
the bind step hardcoded the wrong device model (`n66cn` instead of the actual
scanned `n67cn`), which made pairing a Band 9 Pro fail with "model not
consistent" on `v142`. If you don't need that specific fix, the official app
plus [Gadgetbridge](../README.md#gadgetbridge-pairing) covers the same ground
without depending on Telegram-distributed builds or a Russian-hosted backend.
