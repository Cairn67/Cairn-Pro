# Cairn Pro

Kernel-level access control for Android, built from a stripped kernel image.

Cairn Pro patches a stock `boot.img` so that a small, self-contained payload runs inside the
kernel. The payload watches `execve` for `/system/bin/su`, consults an in-kernel UID
allowlist, and — only for authorised callers — re-credits the process and redirects it to
a daemon. Everything else keeps the kernel's original behaviour.

> **This project is inherently dual-use and can damage your device.** It rewrites boot
> images and installs kernel code. A wrong build can prevent the device from booting or
> leave it in a state that needs a full reflash. Read [docs/LEGAL.md](docs/LEGAL.md)
> before doing anything with it. See [SECURITY.md](SECURITY.md) for how to report
> concerns.
>
> Publish it as source to be read and studied. GitHub's Acceptable Use Policies treat
> research into vulnerabilities and exploits as an accepted use; they do not extend that
> treatment to package registries, where software is installed and run rather than read.

---

## What it does

| Capability | Where it lives |
|---|---|
| Inline hooks placed into the stock kernel image | `source/kernel-builder/` |
| `execve` interception and the in-kernel UID allowlist | `source/kernel-builder/payload*.S` |
| Privileged operation dispatcher (`prctl` with a private op) | `source/kernel-builder/payload*.S` |
| Path visibility rules per application UID | `source/kernel-builder/path_*`, `source/visibility/` |
| On-device daemon and module lifecycle | `source/daemon/` |
| Android manager application | `source/app/` |

The design is described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The reasoning
behind specific decisions — why the frame layout is derived from the target, why the
allowlist fails closed, why a kernel family can be rejected outright — is collected in
[docs/DESIGN-NOTES.md](docs/DESIGN-NOTES.md).

## Downloads (v1.0.1)

Prebuilt artifacts are attached to the
[GitHub Releases](https://github.com/Cairn67/Cairn-Pro/releases) page. **No kernel/boot
image is published** — you build your own patched image for your own device from your own
stock `boot.img`.

| Artifact | What it is | SHA-256 |
|---|---|---|
| `cairn-kernel-builder-linux-arm64` | The dynamic patcher binary (AArch64 Linux). Point it at a stock `boot.img`; it identifies the kernel version, derives every hook site from the image itself, and writes the patched image plus a matching zero-key `core.cld` | `4c1389002dde8b89fd8ff247a8683c2974bcc5dd971356e4a50ecbcefaac12fd` |
| `core-V1.0.1.cld` | Kernel payload, detached form (64 KiB + 48-byte header). Only compatible with images whose bootstrap pins digest `be32ec9b…`; a mismatched pair fails closed at load time by design | `d2d05a42f006a4e597770ae77394dd65c6ac60ff60d3a14577d682f6c720d8c3` |
| `Cairn-Pro-V1.0.1-Turbo6-matched.apk` | The manager app (versionName 1.0.1), bundled with the matching core for recovery. Works out of the box with the OnePlus Turbo 6 (PLU110) 17.0.0.100 image built by the above binary | `eae11c9dd5dbb40d88039ab4ae83ebe034eb3b840cd942d43d65c46d0bdd19ff` |

### Compatibility warning — read before using the patcher

The dynamic patcher is **experimental and unstable**. It supports only a small number of
devices, not a general "one binary for every phone" tool:

- Verified end-to-end (QEMU boot + payload load) only on **OnePlus Turbo 6 (PLU110),
  ColorOS 17.0.0.100**, kernel `6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k`.
- It has **no database of supported devices**. For any other phone it may refuse the
  kernel outright (this is the intended fail-closed behaviour), or — if the structure
  looks familiar enough to pass a heuristic — produce an image that was never tested on
  real hardware. **Both outcomes are treated as "unsupported"**; do not flash an image
  the tool accepts for an unverified device without assuming full responsibility.
- Kernel-family rejection is by design: when a series cannot be described by stable
  structural rules (e.g. 6.12), the tool refuses rather than guess.
- Every output is stamped `posture: experimental-qemu-only` and
  `real_device_validated: false` in its `verification-status.json` until someone tests it
  on the actual hardware.
- The output directory is currently hardcoded to `/storage/emulated/0/OPPO/适配` (the
  author's device layout); it requires an interactive terminal and is not scriptable yet.

AVB is **not** re-signed by the patcher. On locked bootloaders with verification enabled a
patched image will not boot; unlocking the bootloader is a precondition, and downgrades
are a separate AVB rollback concern entirely.

## How the split is licensed

This repository distributes more than one body of code, and they do **not** share a
single licence:

| Part | Licence | Why |
|---|---|---|
| `source/app/`, `source/daemon/`, `source/visibility/` | GPL-3.0-only | Userspace programs |
| `source/kernel-builder/src/`, `loader/load-core.c` | GPL-3.0-only | Userspace build tooling |
| `source/kernel-builder/` kernel-side files (`payload*.S`, `access_*`, `path_*`, `loader/*` except `load-core.c`) | **GPL-2.0-only** | This code occupies the same work boundary as Linux, which is GPL-2.0-only |

Each file carries an `SPDX-License-Identifier` header, and that header governs the file.
Licence texts are collected in [LICENSES/](LICENSES/) and the root [LICENSE](LICENSE)
covers the repository as a whole. Third-party components and their terms are listed in
[docs/THIRD-PARTY.md](docs/THIRD-PARTY.md).

## Building

See [docs/BUILDING.md](docs/BUILDING.md). Short version: you need a matching stock
`boot.img`, an Android SDK, and a Java/Kotlin toolchain. Nothing is downloaded at build
time by the provided script, and it does not sign the result.

## What is not in this repository

The published tree contains source and licences only. It deliberately excludes:

- device-specific boot images, and any packaged build with authentication material in it;
- plaintext keys, signing keystores, or anything that carries a user's credential;
- upstream project source trees — only their licence texts are kept, with links to the
  originals (see [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md)).

If you are looking for a ready-to-flash image: there is none here. Build it for your own
device, from your own vendor image.

## Support and contributing

- Questions about the design: open an issue.
- Security or abuse concerns: see [SECURITY.md](SECURITY.md).
- Contributions: see [CONTRIBUTING.md](CONTRIBUTING.md).

## AI assistance disclosure

Parts of this repository were written with AI assistance (interactive, session-based
tooling under the author's direction). The author reviewed, tested, and takes
responsibility for everything published here. AI-assisted work includes documentation,
build tooling, and portions of the application and patcher code.