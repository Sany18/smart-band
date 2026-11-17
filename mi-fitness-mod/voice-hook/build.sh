#!/usr/bin/env bash
# Build a Mi Fitness 3.56.0 APK with the watch voice hook.
#
# Only three things change versus the original APK:
#   classes.dex    – the app-update prompt is disabled (AppUpgradeUtil)
#   classes9.dex   – receiveAudioCommand() calls WatchVoiceHook.onCommand() first
#   classes13.dex  – new: WatchVoiceHook (compiled from src/)
# Resources, native libs and every other dex stay byte-identical.
#
# Output: ../work/out/mi-fitness-3.56.0-voice.apk (signed with keys/watchvoice.jks)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
APK="$ROOT/apk/mi-fitness-3.56.0.apk"
NORES="$ROOT/work/nores"          # apktool d -r output (smali only)
OUT="$ROOT/work/out"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.1.0"
AJ="$SDK/platforms/android-36.1/android.jar"
KS="$HERE/keys/watchvoice.jks"
KS_PASS="${KS_PASS:-watchvoice}"

[ -f "$APK" ] || { echo "missing $APK"; exit 1; }

echo "== 1/5 compile hook -> dex"
rm -rf "$HERE/build"; mkdir -p "$HERE/build/stubs" "$HERE/build/classes" "$HERE/build/dex"
"$JAVA_HOME/bin/javac" --release 11 -nowarn -d "$HERE/build/stubs" $(find "$HERE/stubs" -name '*.java')
"$JAVA_HOME/bin/javac" --release 11 -Xlint:-options -cp "$HERE/build/stubs:$AJ" \
    -d "$HERE/build/classes" "$HERE/src/WatchVoiceHook.java"
"$BT/d8" --release --min-api 26 --lib "$AJ" --classpath "$HERE/build/stubs" \
    --output "$HERE/build/dex" "$HERE"/build/classes/*.class

echo "== 2/5 patch smali"
[ -d "$NORES" ] || apktool d -r -f -o "$NORES" "$APK"
python3 "$HERE/patch_smali.py" "$NORES"

echo "== 3/5 reassemble dex (apktool only re-smalis changed folders)"
apktool b "$NORES" -o "$OUT/.apktool-unused.apk" >/dev/null
DEX9="$NORES/build/apk/classes9.dex"
DEX1="$NORES/build/apk/classes.dex"
[ -f "$DEX9" ] && [ -f "$DEX1" ] || { echo "apktool did not produce $DEX9 / $DEX1"; exit 1; }

echo "== 4/5 splice into original APK"
mkdir -p "$OUT"; STAGE="$(mktemp -d)"
cp "$APK" "$STAGE/mod.apk"
cp "$DEX9" "$STAGE/classes9.dex"
cp "$DEX1" "$STAGE/classes.dex"
cp "$HERE/build/dex/classes.dex" "$STAGE/classes13.dex"
( cd "$STAGE"
  zip -q -d mod.apk 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/*.EC' 'META-INF/MANIFEST.MF' || true
  zip -q mod.apk classes.dex classes9.dex classes13.dex )
"$BT/zipalign" -P 16 -f 4 "$STAGE/mod.apk" "$STAGE/aligned.apk"

echo "== 5/5 sign"
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias watchvoice -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=watchvoice" >/dev/null
fi
FINAL="$OUT/mi-fitness-3.56.0-voice.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias watchvoice \
    --out "$FINAL" "$STAGE/aligned.apk"
"$BT/apksigner" verify "$FINAL"
rm -rf "$STAGE" "$OUT/.apktool-unused.apk"
ls -la "$FINAL"
