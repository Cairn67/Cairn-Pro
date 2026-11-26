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

