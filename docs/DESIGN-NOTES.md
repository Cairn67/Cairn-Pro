# Design notes

These are the decisions that took work to get right. Each entry records the constraint,
not just the conclusion, so that a future change can be judged against it.

## Kernel-side

### The frame layout is derived from the target, never assumed

When Cairn Pro re-enters a target function so that it can run in front of it, the payload must
present the same stack frame shape the target expects: the same total depth, and the
target's locals at the same `x29`-relative offset. Getting the depth right but the offset
wrong puts the target's local variables at the wrong addresses.

The order of operations differs between kernel families — some adjust `sp` first and then
store registers, others store first. A parser that assumes one order silently produces a
wrong frame on the other. The layout is therefore read from the target's own instructions.

### The authentication slot is filled by the loader, not compiled in

The payload compares the caller's two halves against `cairn_hash`, which the loader writes
from the bootstrap's authentication slot. No key is compiled into the payload, and the
repository contains no key.

### The allowlist fails closed

The table is a fixed 128 slots at a `vmalloc` address. Readers take a sequence number
before and after scanning; if it changed, the read is refused. There is no retry and no
partial answer — a concurrent update means "not authorised", never "probably authorised".
Writers take a non-sleeping lock and return `EBUSY` rather than blocking.

The table lives in kernel memory and is therefore gone after a reboot. The persistent
source is a file, and the daemon rescans it in full and pushes the whole table on every
change so the two cannot drift.

### Some kernel families are refused outright

If a value required to perform an operation cannot be established from the static image,
the family is rejected. The concrete case: an enumeration that exists only in runtime BSS
and reads as all zeros in the image. The choice is between refusing to support that family
and executing with a guessed value. The project refuses.

This is a deliberate position, and it should not be relaxed by inserting a plausible
constant.

### Payload activation must replay the instruction it overwrote

An activation path that jumps into the payload without first executing the instructions it
replaced will corrupt the target. The replay is inline, in the hook site itself, so that
every path through the hook preserves the original behaviour.

## Userspace

### Press feedback scales the surface, not the label

A press grows the control by scaling it. If the engine is attached to a row that has no
background of its own, only that row's text scales and the surrounding card stays put.
The engine therefore attaches to the element that actually carries the surface, and rows
forward their touch stream to it.

### Scaling draws outside the parent, so clipping has to be opened — carefully

A scaled control paints beyond its own bounds, and every container clips by default, so
the grown part gets cut off. Clearing the clip up the ancestor chain fixes that, but a
**scrolling container is where it must stop**: opening one lets rows that have scrolled
out of view keep painting over the pinned header and the floating bar. The rules are
therefore "open the ancestors, stop at the first scroller".

### A held press lasts until the finger is lifted

Releasing on movement makes a press feel fragile. The engine keeps the press open for as
long as the stream is alive; the endings are the up event, a cancel from an ancestor that
takes the gesture for scrolling, and detach. It does not call
`requestDisallowInterceptTouchEvent`, because doing that on a sideways drift locks the
enclosing list out of the gesture.

### Only an element that consumes the gesture gets feedback

A view that is focusable but does not consume events receives the down event and then
nothing else. If the engine starts a press there, it never sees the up event and the
surface stays enlarged. The check is therefore on whether the host actually consumes
events, not on whether it is focusable.

### Modals are pages, not overlays

A panel that is added on top of the current page leaves the page visible behind it, which
reads as a floating layer rather than a destination, and it invites dropping the dim to
near nothing to keep the page legible. Making it a real page removes the whole problem:
its own window, its own back handling, nothing behind it to show through.
