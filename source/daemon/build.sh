#!/bin/bash
# Builds the daemon as a static arm64 binary.
# Cairn Pro 1.0.1: build a freestanding ARM64 cnd from this directory's sources.

set -u
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
OUT=${1:-"$HERE/cnd"}
aarch64-linux-gnu-gcc -O2 -static -nostdlib -fno-builtin -fno-stack-protector \
  -fno-tree-loop-distribute-patterns -Wl,-e,_start -Wl,--build-id=none \
  -o "$OUT" "$HERE/cairn_entry.c" "$HERE/cairnd.c" || exit 1
UNDEFINED=$(aarch64-linux-gnu-nm -u "$OUT") || exit 1
[ -z "$UNDEFINED" ] || { printf '%s\n' "$UNDEFINED"; exit 1; }
aarch64-linux-gnu-nm "$OUT" | grep -q ' _start$' || exit 1
printf '%s\n' 'BUILD OK: cnd; no installation performed'
sha256sum "$OUT"
