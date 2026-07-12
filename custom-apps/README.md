# Custom apps

Quick apps authored in this repo (as opposed to third-party community apps —
see [`../community-resources/sample-apps/`](../community-resources/sample-apps/)
for those). Each subfolder is a complete, independently buildable project —
see [`../dev-environment/`](../dev-environment/) for how to build and install
one of these onto real hardware or the emulator.

| App | What it does |
|---|---|
| [`counter-example/`](counter-example/) | Minimal tap-to-increment counter — the reference project for the dev-environment guide. Built and verified running on real Band 9 Pro hardware. |
| [`gb-relay/`](gb-relay/) | Demonstrates the `system.interconnect` phone<->watch messaging channel (see [`../protocol-notes/README.md`](../protocol-notes/README.md)) against a patched Gadgetbridge. Shows live connection status, lets you ping the phone, and renders a phone-relayed image via `@system.file`. Confirmed working end-to-end on real hardware, both directions, including images. |
| [`location-app/`](location-app/) | Shows GPS coordinates via `@system.geolocation`, plus a real map tile fetched from OpenStreetMap and relayed through the same `system.interconnect` channel. Falls back to the phone's own location if the watch has no fix (e.g. indoors). Confirmed working end-to-end on real hardware. |
