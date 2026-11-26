# Building

There are two separate build steps: the kernel image, and the manager application. You
need a stock image for your own device; this repository contains none.

## Prerequisites

- A stock `boot.img` for the target device, matching the running system.
- Rust (stable) with the `aarch64-linux-android` target for the on-device daemon.
- An Android SDK, a JDK, and Kotlin and Compose compiler jars.
- `aarch64-linux-gnu-gcc` for the native components.
- `apksigner`, `zipalign`, `aapt2`, and R8/D8 from the SDK build tools.

## Kernel image

```
cd source/kernel-builder
cargo build --release
```

The builder reads the stock image, identifies its kernel family from the image contents,
and either plans the patch or **refuses the family**. A refusal is a supported outcome:
if a needed value cannot be established from the image, the tool does not substitute a
guess.

The generated examples under `source/kernel-builder/p17/` show the profile format.

## Manager application

`tools/build_app_local.py` compiles the app from local sources and dependency jars:

```
python3 tools/build_app_local.py --output /tmp/out --shell-apk /path/to/baseline.apk
```

Notes:

- The script **does not download anything**; point it at a dependency directory and an
  SDK that you already have, and read the paths at the top of the file.
- A baseline APK is required because one native library (`libcairnsh.so`) is taken from
  it. That library's source is not part of this repository.
- The output is **unsigned**. Sign it with your own key:
  ```
  apksigner sign --ks <your.keystore> --out signed.apk cairn-shell-path-aligned.apk
  ```
- Verify before installing:
  ```
  apksigner verify signed.apk
  zipalign -c 4 signed.apk
  ```

## Verifying a build

There is no substitute for testing on real hardware. An emulator or a generic
`-M virt -cpu max` QEMU target exercises the loader and the hook logic, but it is not the
target device's hardware or kernel configuration. A build that boots under emulation has
**not** been shown to boot on a phone; treat the two as different claims.
