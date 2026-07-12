# Custom apps

Quick apps authored in this repo (as opposed to third-party community apps —
see [`../community-resources/sample-apps/`](../community-resources/sample-apps/)
for those). Each subfolder is a complete, independently buildable project —
see [`../dev-environment/`](../dev-environment/) for how to build and install
one of these onto real hardware or the emulator.

| App | What it does |
|---|---|
| [`counter-example/`](counter-example/) | Minimal tap-to-increment counter — the reference project for the dev-environment guide. Built and verified running on real Band 9 Pro hardware. |
| [`gb-relay/`](gb-relay/) | Demonstrates the `system.interconnect` phone<->watch messaging channel (see [`../protocol-notes/README.md`](../protocol-notes/README.md)) against a patched Gadgetbridge. Shows live connection status and lets you ping the phone and display whatever message it sends back. Confirmed working end-to-end on real hardware. |
