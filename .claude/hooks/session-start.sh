#!/bin/bash
# Cloud sessions only: install the Android toolchain and fetch what git does not carry.
# Local Windows sessions have their own toolchain; see scripts/cloud_setup.sh for what this does.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

"$CLAUDE_PROJECT_DIR/scripts/cloud_setup.sh"
