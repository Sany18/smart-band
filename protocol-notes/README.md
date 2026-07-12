# RPK app package format

`.rpk` files are Xiaomi's Vela "quick app" package — the installable mini-app
format for these bands/watches (distinct from the `.face`/`.bin` LVGL
watchface binary format). Structurally it's just a **zip file**:

```
META-INF/
  CERT              <- see below, not what it looks like
manifest.json       <- app metadata, permissions ("features"), routing
manifest-watch.json <- watch-specific variant of the manifest
app.js               <- webpack-bundled app entry (built via @aiot-toolkit/dsl-xvm)
pages/**/*.js         <- per-page bundles
i18n/*.json           <- locale strings
common/**             <- images etc.
```

See [`rpk-manifest-example.json`](rpk-manifest-example.json) for a real
(third-party, publicly-distributed) example — note `designWidth: 336`, which
matches the Band 9 Pro's screen width, and the `deviceTypeList: ["watch"]` /
`features` (permission) declarations.

## `META-INF/CERT` is not a real signature

It looks like a code-signing certificate by name and position (`META-INF/`),
but unzip it and it's **itself just another zip** containing a single
`hash.json` — a flat list of SHA-256 digests of the package's own files. See
[`rpk-cert-is-actually-just-hashes.json`](rpk-cert-is-actually-just-hashes.json).

There's no RSA/PKCS7 structure, no public key, no signing authority — it's an
**integrity manifest, not proof of authorship**.

**Confirmed, not just theorized:** built a minimal test app from scratch (see
[`../dev-environment/`](../dev-environment/)) with the *current*
`aiot-toolkit@2.0.5`, `npm run build`. The build log explicitly shows it
locating and using a bundled key pair
(`node_modules/@aiot-toolkit/aiotpack/.../signature/pem/{private,certificate}.pem`).
Despite that, the resulting `META-INF/CERT` is — byte for byte the same
shape — **still just a zip containing `hash.json`**, no RSA/PKCS7 anywhere.
So this isn't a quirk of one old third-party sample; it's how `aiot build`
(debug mode) packages RPKs in general, even when it has a real key pair
available. It's possible `npm run release` (a separate command, using
`sign/release/` instead of the bundled debug keys) produces an actually-signed
package — not tested here. Either way: this test app **installed and ran
successfully on real Band 9 Pro hardware via Gadgetbridge** with this
unsigned-looking CERT, which is at least circumstantial evidence the device
doesn't hard-require a real signature to install a debug-built RPK.

## Toolchain behind RPK apps

The webpack module paths inside `app.js` reveal the real build tool:
`@aiot-toolkit/dsl-xvm` (a webpack loader/plugin for "xvm" — the quick-app
runtime), part of the same `aiot-toolkit` npm scope as the emulator (see
[`../emulator/`](../emulator/)). So building your own RPK doesn't require any
Windows tooling — it's the same official, cross-platform SDK.

## `system.interconnect`: phone <-> quick-app messaging

There's an undocumented quick-app JS module, `@system.interconnect`, that
lets a watch-side RPK exchange arbitrary byte messages with a specific
companion Android app (matched by package name) over BLE, via whatever app
is managing the connection (stock Mi Fitness, or in our case a patched
Gadgetbridge — see the `xiaomi-app-messaging` branch of
[our Gadgetbridge fork](https://github.com/Sany18/Gadgetbridge)). This is
the same channel the official
`com.xiaomi.wearable.XMS_WEARABLE_SERVICE` AIDL interface
(`NodeApi`/`MessageApi`) exposes to third-party Android apps — we
reimplemented it directly in Gadgetbridge instead of depending on that
service, so a custom quick app can talk to the phone with only Gadgetbridge
installed.

API surface (reverse-engineered, not in any public docs):

```js
import interconnect from '@system.interconnect'
const conn = interconnect.instance() // singleton, create once
conn.getApkStatus() // "UNINSTALLED" | "CONNECTED" | "DISCONNECTED" | "TIMEOUT"
conn.onmessage = (e) => { /* e.data is the phone's message string */ }
conn.onclose = () => {}
conn.send({ data: { data: 'hello' }, success: () => {}, fail: (err) => {} })
```

Wire protocol: reuses the existing RPK-management `Command` (type=20)
channel. `Rpk.rpkAppStatus` (field 8) and `Rpk.rpkAppMessage` (field 9) carry
an `RpkAppBasicInfo{packageName, fingerprint}` plus either a connection
status or an arbitrary byte payload:

- subtype 6, watch -> phone: the watch proactively announces itself
  (reusing the existing `RpkInfoList` shape: `id`=packageName,
  `sha`=a real 20-byte fingerprint) whenever a quick app opens
  `system.interconnect`.
- subtype 7, phone -> watch: `RpkAppStatus`, must echo that same
  fingerprint back with `status` 1 (connected) or 2 (disconnected).
- subtype 8, phone -> watch / subtype 9, watch -> phone: `RpkAppMessage`,
  the actual payload.

**The fingerprint match is not optional.** Skip it (send an empty or
placeholder fingerprint) and the wire traffic still looks byte-correct, but
the watch silently rejects everything: `getApkStatus()` stays stuck on
`TIMEOUT` and `send()` fails with `{"data":"not connected","code":401}`.
The phone side must remember the fingerprint the watch itself announced
and reuse it for every subsequent status/message send to that package.

This is the plumbing behind a longer-term goal: since the band has **no
independent internet access at all** (confirmed separately — no working
`system.fetch` path), a custom quick app can use this channel to have the
phone (which has real internet, via Gadgetbridge's Internethelper
companion app) fetch data on its behalf and relay it back.

### How to actually use it today

This isn't wired into any Gadgetbridge UI yet — it's proven working, not
shipped. To reproduce end-to-end:

1. Build/install Gadgetbridge from the `xiaomi-app-messaging` branch of
   [our fork](https://github.com/Sany18/Gadgetbridge) instead of upstream.
2. Build a quick app whose `manifest.json` declares the
   `system.interconnect` feature and uses the JS API above (a minimal
   example lives in [`../custom-apps/`](../custom-apps/)). Install it via
   Gadgetbridge's normal RPK installer.
3. Open the app on the watch. Gadgetbridge auto-answers the
   `CONNECTED` handshake — no phone-side code needed for that part.
4. To push a message from the phone side (there's no UI for this yet —
   it's a debug hook, `XiaomiAppMessageDebugReceiver`), trigger it from
   adb:

   ```sh
   adb shell am broadcast \
     -a nodomain.freeyourgadget.gadgetbridge.ACTION_SEND_APP_MESSAGE \
     -p nodomain.freeyourgadget.gadgetbridge \
     --es package <your.quickapp.packageName> \
     --es message "hello from phone"
   ```

   Omit `--es message` and it fetches the phone's public IP via
   Internethelper and sends that instead, as a smoke test that a
   real internet round-trip works.
5. On the watch, `conn.onmessage` fires with that string.

To turn this into a real feature (rather than an adb-triggered demo),
something in Gadgetbridge needs to call
`XiaomiRpkService.sendMessageToApp()` from actual app logic instead of
the debug receiver — e.g. a settings screen, a scheduled job, or a
response to a `conn.send()` request coming from the watch itself.
