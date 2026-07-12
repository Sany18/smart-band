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

**Likely explanation** (see [`../dev-environment/`](../dev-environment/)): a
real project built with the current `aiot-toolkit`/`hap-toolkit` toolchain
*does* get properly signed for debug builds too — `npm run build` picks up a
locally-generated self-signed cert from the project's `sign/debug/` directory
(`private.pem` + `certificate.pem`, e.g. via `openssl req ...`). The sample
RPK analyzed here has `packageInfo.toolkit: "1.0.18"` (an old version) and
`originType: "cmd"`, and was very likely built without that signing step
configured — i.e. this specific file is an *unsigned debug build*, not
evidence that RPKs can never be signed. Whether the device firmware actually
*verifies* a signature when one is present (as opposed to just accepting
whatever's handed to it) is still an open question — untested here.

## Toolchain behind RPK apps

The webpack module paths inside `app.js` reveal the real build tool:
`@aiot-toolkit/dsl-xvm` (a webpack loader/plugin for "xvm" — the quick-app
runtime), part of the same `aiot-toolkit` npm scope as the emulator (see
[`../emulator/`](../emulator/)). So building your own RPK doesn't require any
Windows tooling — it's the same official, cross-platform SDK.
