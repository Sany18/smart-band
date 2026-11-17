#!/bin/bash
# Entry point for the launchd agent (and handy for manual runs): load .env, start bridge.
# launchd starts with a minimal PATH, so add Homebrew (whisper-cli) and ~/.local/bin
# (claude, and uv for the inbox MCP server).
set -euo pipefail
cd "$(dirname "$0")"
export PATH="/opt/homebrew/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"
set -a
. ./.env
set +a
# Never let an API key sneak in: it would switch claude -p from the subscription to API billing.
unset ANTHROPIC_API_KEY
exec /usr/bin/python3 bridge.py
