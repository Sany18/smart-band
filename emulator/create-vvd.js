// Creates a Vela Virtual Device for the Xiaomi Band 9 Pro (xiaomi_band_pro skin),
// calling @aiot-toolkit/emulator directly. Bypasses aiot-toolkit's `createVVD` CLI
// command, whose interactive name-validator crashes on any name that doesn't
// already exist (it calls VvdManager.getVvdDir(), which throws instead of
// signalling "not found").
const path = require("path");
const {
  VvdManager,
  defaultSDKHome,
  defaultVvdHome,
  IVvdArchType,
} = require("@aiot-toolkit/emulator");

const VVD_NAME = "Vela_Band9Pro";
const IMAGE_TYPE = "vela-miwear-watch-5.0";
const SKIN_NAME = "xiaomi_band_pro";

async function main() {
  const vvdManager = new VvdManager({ sdkHome: defaultSDKHome, vvdHome: defaultVvdHome });

  const skinList = await vvdManager.getVelaSkinList();
  const skin = skinList.find((s) => s.name === SKIN_NAME);
  if (!skin) {
    console.error(`Skin "${SKIN_NAME}" not found. Available skins:`, skinList.map((s) => s.name));
    process.exit(1);
  }

  const imageDir = path.resolve(defaultSDKHome, "system-images", IMAGE_TYPE);

  vvdManager.createVvd({
    name: VVD_NAME,
    skin: skin.name,
    "skin.path": skin.path,
    arch: IVvdArchType.arm,
    imageDir,
    imageType: IMAGE_TYPE,
    customLcdRadius: "",
  });

  console.log(`Created VVD "${VVD_NAME}" with skin "${SKIN_NAME}".`);
}

main().catch((e) => {
  console.error("createVvd failed:", e);
  process.exit(1);
});
