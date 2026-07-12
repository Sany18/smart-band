const { VvdManager, defaultSDKHome, defaultVvdHome } = require("@aiot-toolkit/emulator");

const VVD_NAME = "Vela_Band9Pro";

async function main() {
  const vvdManager = new VvdManager({ sdkHome: defaultSDKHome, vvdHome: defaultVvdHome });

  const { coldBoot, emulatorInstance } = await vvdManager.startVvd({
    vvdName: VVD_NAME,
    verbose: true,
    stdoutCallback: (msg) => console.log("[out]", msg),
    stderrCallback: (msg) => console.log("[err]", msg),
  });

  console.log("coldBoot:", coldBoot);
  console.log("Boot command issued, waiting for the emulator window/boot to complete...");
}

main().catch((e) => {
  console.error("startVvd failed:", e);
  process.exit(1);
});
