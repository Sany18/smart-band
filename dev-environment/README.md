# Setting up a dev environment for Band 9 Pro apps

[`example-app/`](example-app/) is a complete, minimal counter app (tap to
increment) that has been **built and installed on real Band 9 Pro hardware**
via the flow described below — not just theoretical. Copy it as your actual
starting point instead of starting from blank files.

There are **two, mostly unrelated, development tracks** for this device:

| Track | What you build | Language | Toolchain |
|---|---|---|---|
| **Quick apps** (`.rpk`) | Installable mini-apps (calculator, health trackers, etc.) | `.ux` single-file components (JS + HTML-ish template + CSS-ish style) | Official, cross-platform (`aiot-toolkit`, Node.js) |
| **Watchfaces** (`.face`) | The clock face itself | Lua (LVGL widgets) | Windows-only (EasyFace) — see [`../community-resources/`](../community-resources/) |

This guide covers the **quick app** track, which is fully doable on macOS/
Linux/Windows with no proprietary tools.

## Prerequisites

- **Node.js 8+** (officially; anything reasonably current works — this was
  verified end-to-end on Node 24).
- **`aiot-toolkit`** — the official Xiaomi Vela CLI, published on npm:

  ```bash
  npm install aiot-toolkit -g
  aiot -v   # prints a version number if installed correctly
  ```

  It bundles its own adb wrapper and an emulator manager — see
  [`../emulator/`](../emulator/) for setting up a virtual Band 9 Pro to test
  against before touching real hardware.

## The `.ux` component format

Quick apps are built from **`.ux` files** — single-file components, structurally
identical in spirit to Vue SFCs: a `<template>`, a `<style>`, and a `<script>`
block in one file.

```html
<template>
  <div class="page">
    <text onclick="increment">{{ count }}</text>
  </div>
</template>

<style>
  .page { flex-direction: column; justify-content: center; align-items: center; }
</style>

<script>
  export default {
    private: { count: 0 },
    increment() { this.count++ }
  }
</script>
```

- Data binding: `{{ expr }}`, `for="{{item in list}}"`, `onclick="handler"`.
- Component state is declared under `private` (internal), `protected`
  (overridable by an internal page-to-page navigation), or `public`
  (overridable by external callers) — see the official
  [framework docs](https://github.com/miwufe/quickapp-docs/tree/master/docs/framework).
- System APIs are imported like modules: `import router from '@system.router'`,
  `import fetch from '@system.fetch'`, etc. — each one corresponds to a
  `features` entry you must declare in `manifest.json` (a basic permission
  model).
- Under the hood there are actually **two DSL compiler backends** in the
  `aiot-toolkit`/`@aiot-toolkit` npm scope: `dsl-vue` and `dsl-xvm` (a newer/
  lighter runtime — this is what real Band 9 Pro RPKs are built with, per
  [`../protocol-notes/`](../protocol-notes/)). As an app author you don't
  normally pick this directly; it's determined by the toolkit configuration.

## `manifest.json`

Every project has one; it's the app's identity, permissions, and page router.
Full schema: [miwufe/manifestJSONSchema](https://github.com/miwufe/manifestJSONSchema).
The field that matters most for this device:

```json
{
  "package": "com.example.myapp",
  "name": "My App",
  "versionName": "1.0.0",
  "versionCode": 1,
  "icon": "/common/logo.png",
  "deviceTypeList": ["watch"],
  "config": { "designWidth": 336 },
  "router": {
    "entry": "pages/index",
    "pages": { "pages/index": { "component": "index" } }
  }
}
```

- `deviceTypeList` is an enum: `phone`, `watch`, `car`, `tv-quickapp`, `tv-h5`.
  Use `watch`.
- `config.designWidth: 336` matches the Band 9 Pro's screen width — this is
  how the framework scales your layout units.

## Creating a project

⚠️ The official docs describe a scaffolding command, `aiot init <name> -d <type>`,
that **does not exist** in the currently-published `aiot-toolkit@2.0.5` CLI
(confirmed: `aiot init` just falls through to the generic help text, no error,
no project created). Rather than chase a possibly-removed/renamed command,
the verified working path is:

1. Clone [miwufe/hap-toolkit](https://github.com/miwufe/hap-toolkit) and use
   `examples/sample` as your starting template (a full, real quick-app project
   with dozens of working `.ux` components you can copy from).
2. Strip it down to what you need, and add `aiot-toolkit` as a dependency in
   your own `package.json` (instead of hap-toolkit's local monorepo-relative
   CLI invocation) with scripts like:

   ```json
   {
     "scripts": {
       "build": "aiot build",
       "start": "aiot start",
       "release": "aiot release"
     },
     "devDependencies": { "aiot-toolkit": "^2.0.5" }
   }
   ```
3. Edit `src/manifest.json` — set `deviceTypeList: ["watch"]` and
   `config.designWidth: 336`.

## Build → run → install

```bash
npm install
npm run build      # compiles src/ -> build/, and packages dist/*.rpk
```

To run against a connected device (real band via Gadgetbridge/official app,
or the emulator from [`../emulator/`](../emulator/)):

```bash
npx aiot getConnectedDevices   # lists adb-visible targets, e.g.:
                                #   100.x.x.x:5555   (real phone/band bridge)
                                #   emulator-5554    (Vela virtual device)
npx aiot start                 # builds + deploys + launches on the selected device
```

### Installing a built `.rpk` onto the real band

Two independent paths, both verified working:

**A. Via Gadgetbridge** (what this repo's pairing flow uses — see the top-level
[README](../README.md#gadgetbridge-pairing)):

```bash
# the app can't read arbitrary shared-storage paths (Scoped Storage) —
# push into its own external files dir instead:
adb push dist/myapp.rpk /sdcard/Android/data/nodomain.freeyourgadget.gadgetbridge/files/myapp.rpk

adb shell am start -a android.intent.action.VIEW \
  -d "file:///storage/emulated/0/Android/data/nodomain.freeyourgadget.gadgetbridge/files/myapp.rpk" \
  -n nodomain.freeyourgadget.gadgetbridge/.activities.install.FileInstallerActivity \
  -t "application/octet-stream"
```

This auto-detects the file as an RPK (`XiaomiFWHelper`/`XiaomiInstallHandler`)
and, if exactly one compatible device is connected, jumps straight to the
install confirmation screen.

**B. Via the official "Quick App Debugger"** (`org.hapjs.debugger` +
`org.hapjs.mockup`, installable with `npx aiot installDbgAndMkp`) — the
sanctioned Xiaomi workflow, per the
[official install docs](https://github.com/miwufe/quickapp-docs/blob/master/docs/tutorial/getting-started/installing.en.md):
push the `.rpk` to `/sdcard/`, then trigger "INSTALL WITH FILE" from inside
the debugger app on-device.

## Signing / release builds

Debug builds are meant to be signed too — with a **locally self-signed** cert,
not one from Xiaomi:

```bash
mkdir -p sign/debug
openssl req -newkey rsa:2048 -nodes -keyout sign/debug/private.pem \
  -x509 -days 3650 -out sign/debug/certificate.pem
```

`npm run build` picks this up automatically if present. For an actual release
build (`sign/release/` instead of `sign/debug/`), `npm run release` produces
`dist/<name>.release.rpk`, publishable via
[developer.hybrid.xiaomi.com](http://developer.hybrid.xiaomi.com/). See
[`../protocol-notes/`](../protocol-notes/) for what happens (and what's
missing) when this step is skipped.

## Further reading

- [miwufe/quickapp-docs](https://github.com/miwufe/quickapp-docs) — the full
  official framework reference: lifecycle, page styling/layout, page routing
  and parameter passing, event handling, parent/child component
  communication, async patterns, and the complete widget catalog.
