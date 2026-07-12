# counter-example

Minimal single-page quick app: a big number and a "TAP ME" button that
increments it. No native features beyond `system.prompt` (declared but
unused — a leftover from initial scaffolding).

This is the reference project for
[`../../dev-environment/README.md`](../../dev-environment/README.md) — the
smallest possible app that proves the full toolchain (build, sign, install
via Gadgetbridge or the emulator) works end to end. Start here if you're
setting up the dev environment for the first time; move to
[`../gb-relay/`](../gb-relay/) once you need phone communication.

Package: `dev.notes.band9protest` ("GB Test"). Confirmed built and running
on real Band 9 Pro hardware.
