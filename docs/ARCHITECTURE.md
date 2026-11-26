# Architecture

Cairn Pro has three parts that run in different privilege domains, plus a build tool that
produces the kernel-side one.

```
  stock boot.img
        │
        ▼
  kernel-builder (userspace, Rust + C + asm)
        │  produces
        ▼
  patched boot.img ──► kernel with Cairn Pro payload resident
                          │
                          ├── execve hook ──► allowlist ──► cred rewrite ──► /data/adb/cnd
                          ├── prctl hook  ──► privileged ops (key-gated)
                          └── path hooks  ──► per-UID visibility rules
                                                   ▲
                                                   │ rules pushed from
  app (manager, Java/Kotlin) ──► daemon (cnd) ─────┘
```

## `source/kernel-builder/` — the image patcher

A userspace tool that takes a stock kernel image and produces a patched one. It does not
run on the device's kernel; it edits the image before it is flashed.

- **`src/`** (Rust) — image parsing, profile selection, symbol resolution, site planning,
  fixups, payload packaging. `interactive.rs` identifies the image from its own contents
  rather than from a hash or a device name.
- **`payload*.S`** — the kernel-side payload, assembled for the target kernel family.
  These files are GPL-2.0-only.
- **`loader/`** — the bootstrap that runs early and maps the payload.
- **`access_*`, `path_*`, `root_domain.*`** — kernel-side subsystems: access decisions,
  path visibility, and the root credential path.

The payload's frame layout is **derived from the target**, not assumed: the total stack
depth and the `x29`-relative offset of the target's locals are read from the target's own
instructions. Two kernel families can differ in whether the frame stores registers before
or after adjusting `sp`, so assuming an order breaks one of them.

## `source/daemon/` — `cnd`

Runs as a root process on the device. Responsibilities:

- maintain the persistent allowlist at `/data/adb/cairn/allowlist` and push it into the
  kernel after every change;
- apply and persist path-visibility rules;
- manage module lifecycle (install, enable, run at boot).

The daemon holds no network listener. Authorization is handled by the kernel-side
allowlist and by the CLI.

## `source/app/` — the manager

An Android application. It stores the operator's key in the platform keystore, sends it to
the kernel through a `prctl` op to authenticate privileged requests, and presents the UI
for allowlist and path-rule management.

Notable pieces:

- `CairnCore` — the transport to `cnd` and the privileged-op client;
- `AppList` — enumerates installed applications for the allowlist UI;
- `PathPolicy` — reads and writes path visibility rules;
- `redesign/` — the current UI layer (tokens, frosted surfaces, motion).

## `source/visibility/` — path visibility

Defines how per-UID path visibility rules are expressed and evaluated, with a small test
harness. Rules are persisted by `cnd` and enforced by the kernel-side path hooks.

## Data flow for a privileged request

1. The manager obtains the key (keystore, or the operator types it).
2. It issues `prctl(0x43414952, k0, k1, op, arg)`.
3. The kernel payload compares the two 64-bit halves against `cairn_hash`, which the
   loader wrote from the bootstrap's authentication slot.
4. Only on a match does the requested op run. Mismatches fall through to the original
   syscall.

For `su`, the path is different: the `execve` hook checks the caller's UID against the
in-kernel allowlist, and only for a listed UID rewrites the credentials and redirects to
the daemon.
