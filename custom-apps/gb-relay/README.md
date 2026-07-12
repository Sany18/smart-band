# gb-relay

Reference implementation of the `system.interconnect` phone&lt;-&gt;watch
messaging channel described in
[`../../protocol-notes/README.md`](../../protocol-notes/README.md), running
against a patched Gadgetbridge
([`xiaomi-app-messaging` branch](https://github.com/Sany18/Gadgetbridge/tree/xiaomi-app-messaging)).

What it does:

- Shows the live connection status (`getApkStatus()`: `CONNECTED` /
  `DISCONNECTED` / `TIMEOUT` / `UNINSTALLED`).
- `SEND PING` sends a watch-&gt;phone text message.
- Renders whatever the phone sends back. Plain text is shown directly;
  a message prefixed `IMG:<base64>` is decoded, written to local storage via
  `@system.file`'s `writeArrayBuffer`, and displayed with `<image>` — this is
  the part that proves a phone-relayed image (e.g. fetched over the internet
  by Gadgetbridge, which the band itself cannot do) can actually render on
  the watch.

Confirmed working end to end on real Band 9 Pro hardware, both directions,
including the image path.

## Non-obvious bug this app ran into

`file.writeArrayBuffer({ uri, buffer, ... })` throws
`"exception: arraybuffer object expected"` if `buffer` is a raw
`ArrayBuffer` (e.g. `new Uint8Array(bytes).buffer`) — despite the method
name, it actually wants the `Uint8Array` itself
(`new Uint8Array(bytes)`, not `.buffer`). See `base64ToArrayBuffer()` /
`handleImageMessage()` in
[`src/pages/index/index.ux`](src/pages/index/index.ux).

## Driving it without a real second app

There's no phone-side UI for sending messages yet — trigger it via adb
against Gadgetbridge's debug receiver (see the "How to actually use it
today" section in [`../../protocol-notes/README.md`](../../protocol-notes/README.md)
for the exact commands, including the `imagePath` extra for the image path).
