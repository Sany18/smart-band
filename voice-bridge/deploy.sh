#!/usr/bin/env bash
# Deploy voice-bridge to a Linux host as a systemd --user service.
#
#   ./deploy.sh [ssh-host]          (default: x1)
#
# - copies bridge.py / requirements.txt / unit to ~/allProjs/smart-band/voice-bridge
# - creates .venv with faster-whisper (first run downloads the model, ~800 MB)
# - creates .env on first deploy (token = BRIDGE_TOKEN from the local .env, so the
#   phone config keeps working), never overwrites an existing remote .env
# - enables + restarts voice-bridge.service (needs `loginctl enable-linger` for boot start)
set -euo pipefail

HOST="${1:-x1}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEST="allProjs/smart-band/voice-bridge"

TOKEN="$(grep -E '^BRIDGE_TOKEN=' "$HERE/.env" | cut -d= -f2-)"
[ -n "$TOKEN" ] || { echo "no BRIDGE_TOKEN in $HERE/.env"; exit 1; }

echo "== copy files to $HOST:~/$DEST"
ssh "$HOST" "mkdir -p ~/$DEST ~/.config/systemd/user"
scp -q "$HERE/bridge.py" "$HERE/requirements.txt" "$HOST:$DEST/"
scp -q "$HERE/voice-bridge.service" "$HOST:.config/systemd/user/voice-bridge.service"

echo "== remote setup"
ssh "$HOST" TOKEN="$TOKEN" DEST="$DEST" 'bash -s' <<'REMOTE'
set -euo pipefail
cd ~/"$DEST"
if [ ! -x .venv/bin/python ]; then
    python3 -m venv .venv
fi
.venv/bin/pip install -q --upgrade pip
.venv/bin/pip install -q -r requirements.txt

TS_IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"
if [ ! -f .env ]; then
    umask 077
    cat > .env <<EOF
BRIDGE_TOKEN=$TOKEN
BRIDGE_HOST=${TS_IP:-0.0.0.0}
BRIDGE_PORT=8787
STT_BACKEND=faster-whisper
FW_MODEL=large-v3-turbo
FW_COMPUTE=int8
WHISPER_LANG=uk
CLAUDE_MODEL=haiku
CLAUDE_CWD=$HOME
EOF
    echo "created .env (host ${TS_IP:-0.0.0.0})"
fi

systemctl --user daemon-reload
systemctl --user enable voice-bridge.service >/dev/null
systemctl --user restart voice-bridge.service
echo "linger: $(loginctl show-user "$USER" -p Linger --value)"
REMOTE

echo "== status (model load can take a while on first start)"
ssh "$HOST" 'sleep 3; systemctl --user --no-pager status voice-bridge.service | head -12'
