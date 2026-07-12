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
