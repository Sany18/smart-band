# location-app

Reads GPS position via `@system.geolocation` and renders both the raw
coordinates and a real map tile around that position. Confirmed working
end to end on real Band 9 Pro hardware.

If the watch has no GPS fix of its own (e.g. indoors), it falls back to
asking the phone to resolve its own last-known location instead — see
"GPS-less fallback" in
[`../../protocol-notes/README.md`](../../protocol-notes/README.md). This
is the same `system.interconnect` image-relay mechanism as
[`../gb-relay/`](../gb-relay/), applied to a real map tile fetched from
`tile.openstreetmap.org` instead of a static test image.

Package: `dev.notes.band9prolocation`.
