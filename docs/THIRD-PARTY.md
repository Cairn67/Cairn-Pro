# Third-party components and licences

Licence texts are in [../LICENSES/](../LICENSES/). No upstream source tree is
redistributed in this repository.

## Used as a dependency, or ported with attribution

| Component | Version / commit | What was used | Licence |
|---|---|---|---|
| [lz4/lz4](https://github.com/lz4/lz4) | `v1.9.4`, `lib/lz4.c` and `lib/lz4.h` | LZ4 legacy frame container for the kernel image. Vendored verbatim. The thin wrapper that frames the container is Cairn Pro code and adds no compression code. | BSD-2-Clause, Copyright (C) 2011-2020 Yann Collet |
| [KernelSU](https://github.com/tiann/KernelSU) | `dbb73665d103da40c7ecbd21ba3b5ac76eb95784` | Bottom-bar capsule, drag damping, touch highlight, gesture observation. Adapted. | GPL-3.0 |
| [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) | `0.9.4` | Backdrop / blur runtime API. | Apache-2.0 |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | commit not recorded upstream | Glass lens and vibrancy, adapted through the Miuix example. | Apache-2.0 |
| [QWEA0/Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android) | `ea7908990247c56d6a9635e89ed36cb022434508` | Rounded-corner SDF, edge refraction, highlight, and the press-scale animation. Adapted to Android Views. | MIT, Copyright (c) 2025-2026 pandadog |
| [liquid_design](https://pub.dev/packages/liquid_design) | `0.4.3` ([hareerapp/fluter-liquid-glass](https://github.com/hareerapp/fluter-liquid-glass)) | Jelly-press motion model and the iOS-style switch geometry and springs. Upstream is Flutter/Dart, so the motion model was translated rather than linked. | MIT, Copyright (c) 2026 Mohammed Al-Jaf |

## Mechanism references — idea only, no code

| Project | Licence | What was taken |
|---|---|---|
| [bmax121/KernelPatch](https://github.com/bmax121/KernelPatch) | GPL-2.0 | The *approach* of protecting privileged kernel operations with a single authentication credential, and of maintaining a UID allowlist in kernel memory that userspace grants and revokes into. |

No KernelPatch code is present in this repository. A whole-tree search for `KernelPatch`,
`kernelpatch`, or `APatch` returns nothing, and no file carries its copyright header.
The implementation differs in mechanism:

| Aspect | KernelPatch | Cairn Pro |
|---|---|---|
| Credential path | hooks `execve`; credential is the first argument to `/system/bin/truncate` | `prctl` with a private op; credential is two 64-bit halves compared against the payload's stored hash |
| Allowlist storage | variable-length container | fixed 128 slots in a `vmalloc` region: `lock`, `sequence`, `count`, then the entries |
| Concurrent reads | not described in upstream documentation | sequence number read twice; any mismatch is refused, with no retry |
| Persistent source | maintained through `sumgr` subcommands | `/data/adb/cairn/allowlist`, one UID per line, rescanned in full after every change |
| On a hit | switch to the requested uid / scontext and execute | rewrite credentials to uid 0 and redirect to the daemon |

Because KernelPatch is GPL-2.0 and the Cairn Pro kernel-side files are GPL-2.0-only, the
licences are compatible — but there is no code to license. The relationship is a citation
of method, not a reuse of expression.

## Read but not used

| Project | Status |
|---|---|
| [opiumfive/Liquid-glass-android](https://github.com/opiumfive/Liquid-glass-android) | README only; not read further, not integrated. |
| [Citrussss/AndroidViewLiquidGlass](https://github.com/Citrussss/AndroidViewLiquidGlass) | README only; not read further, not integrated. |
| [LottieFiles/motion-design-skill](https://github.com/LottieFiles/motion-design-skill) | `SKILL.md` principles reviewed as a design reference. MIT. Not a code or runtime dependency. |
| PrismalAGSL | An earlier implementation derived from this project was **removed** from Cairn Pro. No PrismalAGSL code remains in the tree. |

## Adding a component

Record the repository, a verifiable version or commit, exactly what was read or used,
whether it was referenced / adapted / depended on, and the licence. If you copy or adapt
code, keep the upstream notice, mark your changes, and make sure the distributed artifact
carries the required statements.
