# Legal notes, licensing, and dual-use statement

This document states what the project is, which licence governs which file, and the
limits of what is claimed. It is written to be auditable, not to be persuasive.

## 1. Dual-use nature

Cairn Pro patches a Linux kernel on a device the operator owns, in order to control how that
kernel handles privilege and path visibility. The same techniques are used both by device
owners doing their own research and by security researchers. The project is published for
those purposes.

What the project does **not** do, and does not intend to enable:

- no target other than the operator's own hardware;
- no bypass of any other product's licence, activation, or payment checks;
- no credential harvesting, no data exfiltration, no remote control channel;
- no concealment of activity from the device's owner.

If you intend to use this against a device you do not own, this project is not for you and
its licence gives you no permission to do so.

### Where this kind of project may be published

GitHub's Acceptable Use Policies explicitly allow dual-use content and support
publishing material used for research into vulnerabilities, malware, or exploits; GitHub
states that it assumes positive intention for such projects. Work of this kind is
therefore within the platform's normal terms, and the two steps GitHub recommends for it
are both taken here:

- the disclaimer in [../README.md](../README.md), which identifies and describes the
  potentially harmful content;
- [../SECURITY.md](../SECURITY.md), which gives a preferred contact method for third-party
  abuse enquiries.

One boundary is worth stating, because it is easy to miss: GitHub's guidance treats the
**npm registry** as a platform for installation and run-time use of code rather than for
research. This repository is not published to npm, and it is not intended to be — the
package would have to be installed and run rather than read. Anyone considering a package
index should assume that the research exception does not extend there.

## 2. Which licence applies to which file

The repository is a **mixed-licence distribution**. The governing rule is simple: the
`SPDX-License-Identifier` at the top of a file is the licence for that file.

| Path | SPDX |
|---|---|
| `source/app/**` | GPL-3.0-only |
| `source/daemon/**` | GPL-3.0-only |
| `source/visibility/**` | GPL-3.0-only |
| `source/kernel-builder/src/**` | GPL-3.0-only |
| `source/kernel-builder/loader/load-core.c` | GPL-3.0-only |
| `source/kernel-builder/payload*.S` | GPL-2.0-only |
| `source/kernel-builder/access_*` | GPL-2.0-only |
| `source/kernel-builder/path_*` | GPL-2.0-only |
| `source/kernel-builder/root_domain.*` | GPL-2.0-only |
| `source/kernel-builder/loader/**` (except `load-core.c`) | GPL-2.0-only |

### Why the kernel side is GPL-2.0-only and not GPL-3.0-only

Linux is distributed under **GPL-2.0-only**, and the kernel's own licensing rules require
that any file carrying a different licence must be **compatible with GPL-2.0**
(`process/license-rules`, kernel documentation: *"individual source files can have a
different license which is required to be compatible with the GPL-2.0"*).

**GPL-2.0-only and GPL-3.0-only are not compatible with each other.** Both are strong
copyleft, and GPL-2.0-only has no "or later" clause that would allow upgrading to v3 —
which is precisely why Linux itself has remained on v2. A file that sits inside the same
work as kernel code therefore cannot be GPL-3.0-only.

### Why the build tooling can be GPL-3.0-only

`source/kernel-builder/src/**` and `loader/load-core.c` are **userspace** programs. They
do not run inside the kernel and are not linked into it. The kernel documentation is
explicit that the **syscall interface is a clear boundary** and does not extend GPL
requirements to software that uses it to talk to the kernel.

One thing this document does **not** assert: whether producing a modified kernel image
constitutes a derivative work. That depends on facts and jurisdiction, and is not for the
project to decide. If you intend to redistribute a built image commercially, take your
own advice on the kernel-side boundary. The layered arrangement above is the project's
engineering choice, not a legal opinion.

## 3. Third-party material

No third-party source tree is redistributed here. Component names, versions, commits,
what was used, and the applicable licence text are recorded in
[docs/THIRD-PARTY.md](THIRD-PARTY.md) and the texts live in `LICENSES/`. Where material
was read but not copied — the KernelPatch mechanism in particular — that is stated
explicitly, with the differences between the two implementations listed.

## 4. What is deliberately absent

- Device-specific boot images and any artifact with authentication material.
- Plaintext keys and signing keystores.
- Personal data.
- Upstream project source trees.

## 5. No warranty

The software is provided as is, without warranty of any kind. Patching a kernel can brick
a device. You accept that risk by using it.
