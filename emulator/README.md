# Vela OS emulator for the Band 9 Pro

The official Xiaomi Vela SDK, [`aiot-toolkit`](https://www.npmjs.com/package/aiot-toolkit),
ships a real emulator (a fork of the Android emulator/QEMU stack) with a
`vela-miwear-watch-5.0` system image and device skins including
**`xiaomi_band_pro`** (Band 8 Pro / 9 Pro screen geometry, 336×480).

This gets you a safe sandbox to test quick-apps and observe the device's real
app catalog, without touching your physical band.

## Setup

```bash
npm install aiot-toolkit
npx aiot initEmulatorEnv     # downloads ~650MB of system images/skins/tools
```

## Known bug: `aiot createVVD` crashes on any new name

`aiot-toolkit`'s interactive `createVVD` command validates the chosen VVD name
by calling `VvdManager.getVvdDir(name)` to check whether it already exists —
but `getVvdDir` **throws** when the name doesn't exist, instead of returning
something falsy. Since a *new* VVD name by definition doesn't exist yet, the
validator throws on literally the first prompt, every time.

Confirmed against `aiot-toolkit@2.0.5`. Rather than patch the installed
package, [`create-vvd.js`](create-vvd.js) calls the underlying
`@aiot-toolkit/emulator` library directly, bypassing the buggy CLI wrapper
entirely.

## Usage

```bash
node create-vvd.js   # creates a VVD named Vela_Band9Pro with the xiaomi_band_pro skin
node start-vvd.js    # boots it — opens a GUI window, and appears in `adb devices`
                      # as emulator-5554 once ready
```

Once booted, `adb devices` should show both your real device (if connected)
and the emulator side by side:

```
100.x.x.x:5555     device   (your real phone, e.g. via Gadgetbridge)
emulator-5554      device   (the Vela virtual device)
```

From there, `aiot start`/`aiot build` (run from inside an actual quick-app
project directory — see [miwufe/hap-toolkit](https://github.com/miwufe/hap-toolkit)'s
`examples/sample`) can deploy and run a quick-app against the emulator.
