# Contributing

## Before you start

Read [docs/LEGAL.md](docs/LEGAL.md) — this project patches kernels and touches
privileged paths. Contributions that make it easier to use against third-party devices,
or that bypass any other product's licensing, will not be accepted.

## Ground rules

1. **No guessing.** If a value (an enumeration, an offset, a field layout) cannot be
   established from the image, the SDK, or the vendor's own metadata, the correct answer
   is "unsupported", not a plausible constant. Several kernel families are rejected on
   exactly this principle; do not "fix" that by filling in a guess.
2. **No device-specific constants.** Sites are derived from the target's own symbols and
   instruction structure. Absolute addresses for one device do not belong in the source.
3. **State evidence.** A change that claims a behaviour must say how it was verified —
   build output, boot log, or a reproducible test. Virtual-machine results and on-device
   results are different claims; do not present one as the other.
4. **Keep the licence headers.** Every file's `SPDX-License-Identifier` must stay
   accurate. Files that link into the kernel are GPL-2.0-only and cannot be relicensed.

## Style

- Comments explain **why**, not what. A comment that restates the next line should be
  deleted; a comment that records a constraint discovered the hard way should stay.
- Keep licence and provenance headers verbatim, in English.
- Match the surrounding code's formatting; do not reformat unrelated lines.

## Submitting

Open a pull request describing the problem, the change, and how you verified it. If the
change affects kernel-side code, say which kernel version and device profile you tested.
