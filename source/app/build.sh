#!/bin/bash
# Builds the manager app with an external SDK, dependency set, and baseline APK.
# Cairn Pro 1.0.1: delegate to the actual local-source APK packaging pipeline.

set -u
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd) || exit 1
exec python3 "$ROOT/tools/build_app_local.py" "$@"
