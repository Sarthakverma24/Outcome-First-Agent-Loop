#!/usr/bin/env bash
# Formi Outcome-First Controller Runner
# Delegates to the Java controller in controller/run.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/controller/run.sh" "$@"
