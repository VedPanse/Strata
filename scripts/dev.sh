#!/usr/bin/env bash
# Strata Dev Runner with Hot Reload (Compose Multiplatform Desktop)
# Usage: ./scripts/dev.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"
./gradlew :composeApp:hotRunJvm --auto "$@"
