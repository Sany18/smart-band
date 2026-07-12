# Xiaomi Smart Band 9 Pro — dev notes (China unit, used outside China)

Notes from getting a **China-market Xiaomi Smart Band 9 Pro** (`miwear.watch.n67cn`)
working well outside China (specifically: Ukraine), without official region/global
firmware support, and from poking at the wider Xiaomi "Vela" wearable app ecosystem.

Nothing here bypasses any cryptography or DRM. It's account binding, open-source
companion apps, and the *official* (if obscure) Xiaomi app development SDK.

## TL;DR findings

- **A China-market Band 9 Pro can be used fully outside China** (tested from
  Ukraine) — core fitness/health tracking, notifications, workouts with GPS,
  alarms, weather, and third-party mini-apps all work with no region trickery,
  once you're past the region-server confusion described below. What you
  *can't* get on China hardware is a fully English on-device UI or the global
  mini-app store — see the next point.
- A China Band 9 Pro (`n67cn`) **cannot be flashed to global firmware** (`n67gl`).
  The device model is checked both in the cloud bind step and (presumably) on-device;
  there is no known bypass. If you need a fully English/global experience, buy the
  global SKU.
- You **can** get a fully open, English (companion app UI), cloud-free experience via
  [Gadgetbridge](#gadgetbridge-pairing) — no Xiaomi account needed after initial pairing,
  no dependency on any region server.
- The band's mini-app format (**RPK**, a Xiaomi "quick app" / hybrid JS package) is
  **not cryptographically signed** in practice — see [`protocol-notes/`](protocol-notes/).
- There is a real, public, cross-platform **official dev SDK** for this whole device
  family (`aiot-toolkit` on npm) including a **working desktop emulator** for the
  band's OS (Vela/NuttX) — see [`emulator/`](emulator/). This isn't advertised
  anywhere obvious; you mostly find it by tracing `require()` paths out of a
  decompiled sample app.

## Gadgetbridge pairing

[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) has protocol
support for the Band 9 Pro (`MiBand9ProCoordinator`, device name pattern
`^Xiaomi Smart Band 9 Pro [0-9A-F]{4}$`). It authenticates with a 16-byte
**auth key** instead of a full cloud account.

Getting that key without root, reproducibly:

1. **Pair the band in the official Mi Fitness / Mi Health app** first (sign in,
   bind the device normally). This is what actually establishes the auth key on
   the device side.
2. **Force-stop the official app.** Open **[Notify for Mi Band](https://play.google.com/store/apps/details?id=com.mediatek.factorymode.telcelsdi)**
   (or any similar third-party companion) and let it detect the already-bonded
   device — it reads out the pairing/auth key for you.
3. **Force-stop Notify.** Open Gadgetbridge → Add device → long-press the
   discovered Band 9 Pro entry → **Auth Key** → paste the key you just read →
   enable **"New Auth Protocol"** (the Band 9 Pro uses the newer V2 handshake) →
   connect.

Only one app can hold the BLE link at a time, hence force-stopping between steps.

Build notes for Gadgetbridge itself (JDK 21 + Android SDK 36.1 required; current
`master` has a one-line nullability bug in `HealthConnectUtils.kt` around a
`Long?`/`Long` mismatch on `deviceFromDb.id` — trivial to patch locally, worth
upstreaming) are not repeated here; see the project's own build docs.

## Repository map

| Path | What it is |
|---|---|
| [`emulator/`](emulator/) | Scripts to create + boot a Vela OS virtual device matching the Band 9 Pro, using the official `aiot-toolkit` npm SDK |
| [`protocol-notes/`](protocol-notes/) | Findings about the RPK app package format, including the fake-looking `CERT` file |
| [`community-resources/`](community-resources/) | Non-GitHub, community-run resources (see caveats below) |

## Related open-source projects (linked, not vendored)

These are all public repos — clone them yourself rather than relying on a copy here:

- **[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)** — open-source
  companion app; has Xiaomi protobuf-protocol support including this band.
- **[aiot-toolkit](https://www.npmjs.com/package/aiot-toolkit)** — official Xiaomi
  Vela OS CLI (npm, cross-platform): build/start/release quick apps, manage
  emulator virtual devices (`createVVD`), talk to real devices via its own adb
  wrapper (`@miwt/adb` → [miwufe/node-adb](https://github.com/miwufe/node-adb)).
- **[miwufe/hap-toolkit](https://github.com/miwufe/hap-toolkit)** — open-source
  predecessor CLI + a full example quick-app project you can build from.
- **[miwufe/quickapp-docs](https://github.com/miwufe/quickapp-docs)** — the
  official quick-app framework/API documentation (features, widgets, manifest
  schema, tutorials).
- **[miwufe/manifestJSONSchema](https://github.com/miwufe/manifestJSONSchema)** —
  JSON schema for `manifest.json` validation.
- **[miwufe/adb-tools](https://github.com/miwufe/adb-tools)** — small adb helper.
- **[m0tral/MiWatchLuaWatchfaces](https://github.com/m0tral/MiWatchLuaWatchfaces)** —
  editable Lua/LVGL watchface source samples, including a `MiBand9Pro/` folder
  (`DeviceType=367`, 336×480). Text-free samples (e.g. `AnalogTimeTest`) are a
  good zero-Chinese starting point. Packing into an installable `.face` currently
  still requires EasyFace (Windows-only, see below).
- **[m0tral/EasyFace](https://github.com/m0tral/EasyFace)** — GitHub repo is
  README-only; the actual Windows watchface compiler is distributed elsewhere
  (see `community-resources/`).
- **[m0tral/MiWatchEmulator](https://github.com/m0tral/MiWatchEmulator)** —
  a *different*, Windows-only emulator for watchface testing. As of writing it
  does **not** yet support the Band 9 Pro ("will add Mb8Pro/Mb9Pro soon"),
  which is why this repo's [`emulator/`](emulator/) uses `aiot-toolkit`'s
  emulator instead (which does support it, cross-platform).
- **[Mino260806/mi-watchface-uploader](https://github.com/Mino260806/mi-watchface-uploader)** —
  an alternative custom-watchface install mechanism (file-drop into the official
  app's data dir + apply via its gallery UI).

`xiaomi-auth-sdk` (also under `miwufe`) was investigated and turned out to be an
unrelated Mi Account QR-login SDK — not relevant to device pairing or app dev.

## Explicitly NOT included here

- **Any decompiled Xiaomi app source.** Reverse-engineering the official app was
  how several of these findings were made, but redistributing decompiled
  proprietary code is both huge and not okay to publish.
- **The modded Mi Fitness APKs** (`com.xiaomi.wearable`, m0tral builds). These
  are redistributed via Telegram, not GitHub — see `community-resources/README.md`
  for where to get them if you want them. Not bundled here.
- **Any personal identifiers**: no auth keys, device MACs, account IDs, IPs, or
  serial numbers from the devices used to research this appear anywhere in this
  repo. If you're adapting these notes, treat your own band's auth key like a
  password — it's a real credential, not just an ID.
