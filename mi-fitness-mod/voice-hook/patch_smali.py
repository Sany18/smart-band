#!/usr/bin/env python3
"""Insert the WatchVoiceHook call into BluetoothSpeechRecognizerManager.receiveAudioCommand,
and disable the app-update prompt (a modal with only "Update" that blocks the whole app;
updating would drop this mod).

Idempotent: running twice leaves a single hook. Usage: patch_smali.py <apktool-dir>
"""
import pathlib
import sys

REL = "smali_classes9/com/xiaomi/fitness/aivs/bluetooth/BluetoothSpeechRecognizerManager.smali"
METHOD = ".method private final receiveAudioCommand(Ljava/lang/String;Lcom/xiaomi/fitness/aivs/bluetooth/AivsCommand;)V"
ANCHOR = "    invoke-static {v1, v0}, Lcom/xiaomi/fitness/common/log/Logger;->i(Ljava/lang/String;Ljava/lang/String;)V\n"
MARK = "# watchvoice-hook"
HOOK = f"""
    {MARK}
    iget-object v0, p0, Lcom/xiaomi/fitness/aivs/bluetooth/BluetoothSpeechRecognizerManager;->mAivsService:Lcom/xiaomi/fitness/aivs/bluetooth/BluetoothAivsService;

    invoke-static {{v0, p1, p2}}, LWatchVoiceHook;->onCommand(Ljava/lang/Object;Ljava/lang/String;Lcom/xiaomi/fitness/aivs/bluetooth/AivsCommand;)Z

    move-result v0

    if-eqz v0, :watchvoice_continue

    return-void

    :watchvoice_continue
"""


UPGRADE_REL = "smali/com/mi/health/appupgrade/AppUpgradeUtil.smali"
UPGRADE_METHODS = (
    ".method private final showUpdateDialogIfNeed(Landroidx/fragment/app/FragmentManager;"
    "Lcom/mi/health/appupgrade/model/AppUpgradeResult;Z)V",
    ".method private final goToAppUpgradeActivity(Z)V",
)
UPGRADE_MARK = "# watchvoice-no-upgrade"


def patch_upgrade(root: pathlib.Path) -> None:
    path = root / UPGRADE_REL
    src = path.read_text()
    if UPGRADE_MARK in src:
        print("already patched:", path)
        return
    for method in UPGRADE_METHODS:
        start = src.index(method)
        locals_end = src.index("\n", src.index(".locals", start)) + 1
        src = src[:locals_end] + f"\n    {UPGRADE_MARK}\n    return-void\n" + src[locals_end:]
    path.write_text(src)
    print("patched:", path)


def main() -> None:
    patch_upgrade(pathlib.Path(sys.argv[1]))
    path = pathlib.Path(sys.argv[1]) / REL
    src = path.read_text()
    if MARK in src:
        print("already patched:", path)
        return
    start = src.index(METHOD)
    end = src.index(".end method", start)
    body = src[start:end]
    # Insert after the stock "receive command" log line: v0/v1 are dead afterwards
    # (the next instruction reloads v0 from getType()).
    pos = body.index(ANCHOR) + len(ANCHOR)
    body = body[:pos] + HOOK + body[pos:]
    path.write_text(src[:start] + body + src[end:])
    print("patched:", path)


if __name__ == "__main__":
    main()
