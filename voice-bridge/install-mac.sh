#!/usr/bin/env bash
# Install / reinstall voice-bridge as a per-user launchd agent on this Mac.
# Starts at login, restarts if it dies (e.g. Tailscale IP not up yet at boot).
#   ./install-mac.sh            install + start
#   ./install-mac.sh uninstall  stop + remove
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LABEL="com.watchvoice.bridge"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/voice-bridge.log"
DOMAIN="gui/$(id -u)"

launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
if [ "${1:-}" = "uninstall" ]; then
    rm -f "$PLIST"
    echo "removed $LABEL"
    exit 0
fi

[ -f "$HERE/.env" ] || { echo "missing $HERE/.env (see README)"; exit 1; }
chmod +x "$HERE/run.sh"
mkdir -p "$(dirname "$PLIST")" "$(dirname "$LOG")"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>/bin/bash</string><string>$HERE/run.sh</string></array>
  <key>WorkingDirectory</key><string>$HERE</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
EOF
launchctl bootstrap "$DOMAIN" "$PLIST"
sleep 2
launchctl print "$DOMAIN/$LABEL" | grep -E "state =|pid =" || true
tail -3 "$LOG"
